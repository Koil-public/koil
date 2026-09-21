package com.spirit.koil.api.model.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.mcp.McpRuntimeHealth;
import com.spirit.koil.api.mcp.McpRuntimeManager;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import com.spirit.koil.api.util.file.KoilInstancePaths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Koil adapter for the persistent Scrapling stdio MCP server. It deliberately
 * never forwards proxy, cookie, authentication, CDP, or arbitrary HTTP-method
 * parameters from model input.
 */
public final class ScraplingMcpProvider implements ScrapingProvider {
    private static final String RUNTIME_ID = "scraping";
    private static final int MAXIMUM_CONTENT_PARTS = 64;
    private static final int MAXIMUM_CONTENT_CHARACTERS = 16_000;
    private final McpRuntimeManager runtime;

    public ScraplingMcpProvider(Supplier<Process> processStarter) {
        runtime = new McpRuntimeManager(RUNTIME_ID, processStarter, Set.of("make_request", "fetch"));
    }

    /** Uses an explicitly configured executable or the upstream stdio command on PATH. */
    public static ScraplingMcpProvider managed() {
        String configured = System.getProperty("koil.scrapling.mcp.executable", "").strip();
        String executable = configured.isBlank() ? "scrapling-mcp" : configured;
        return new ScraplingMcpProvider(() -> {
            try { return new ProcessBuilder(executable).start(); }
            catch (java.io.IOException failure) {
                throw new IllegalStateException("Scraping runtime is unavailable: " + failure.getMessage(), failure);
            }
        });
    }

    @Override public CompletableFuture<Page> fetch(URI url, String cssSelector, boolean dynamic) {
        URI target = PublicNetworkPolicy.validate(url);
        return runtime.start().thenCompose(health -> {
            if (health.state() != McpRuntimeHealth.State.READY) {
                return CompletableFuture.failedFuture(new IllegalStateException(health.detail()));
            }
            JsonObject arguments = new JsonObject();
            arguments.addProperty("url", target.toString());
            arguments.addProperty("extraction_type", "html");
            arguments.addProperty("main_content_only", true);
            arguments.addProperty("timeout", dynamic ? 25_000 : 20);
            if (cssSelector != null && !cssSelector.isBlank()) arguments.addProperty("css_selector", cssSelector.strip());
            if (!dynamic) {
                arguments.addProperty("method", "GET");
                arguments.addProperty("follow_redirects", "safe");
                arguments.addProperty("max_redirects", 3);
                arguments.addProperty("retries", 1);
            } else {
                arguments.addProperty("headless", true);
                arguments.addProperty("disable_resources", true);
                arguments.addProperty("network_idle", true);
            }
            return runtime.callTool(dynamic ? "fetch" : "make_request", arguments, Duration.ofSeconds(dynamic ? 35 : 25))
                    .thenApply(result -> page(target, result));
        });
    }

    @Override public CompletableFuture<Screenshot> screenshot(URI url, boolean fullPage, String imageType, int quality) {
        URI target = PublicNetworkPolicy.validate(url);
        String format = "jpeg".equalsIgnoreCase(imageType) ? "jpeg" : "png";
        return runtime.start().thenCompose(health -> {
            if (health.state() != McpRuntimeHealth.State.READY || !runtime.supports("open_session") || !runtime.supports("screenshot") || !runtime.supports("close_session")) {
                return CompletableFuture.failedFuture(new IllegalStateException("Scraping screenshot capabilities are unavailable."));
            }
            JsonObject open = new JsonObject();
            open.addProperty("session_type", "dynamic");
            open.addProperty("headless", true);
            return runtime.callTool("open_session", open, Duration.ofSeconds(20)).thenCompose(opened -> {
                String sessionId = sessionId(opened);
                if (sessionId.isBlank()) return CompletableFuture.failedFuture(new IllegalStateException("Scraping runtime did not return a browser session ID."));
                JsonObject args = new JsonObject();
                args.addProperty("url", target.toString());
                args.addProperty("session_id", sessionId);
                args.addProperty("image_type", format);
                args.addProperty("full_page", fullPage);
                if ("jpeg".equals(format)) args.addProperty("quality", Math.max(1, Math.min(100, quality)));
                args.addProperty("network_idle", true);
                args.addProperty("timeout", 30000);
                return runtime.callTool("screenshot", args, Duration.ofSeconds(40))
                        .thenApply(result -> persistScreenshot(target, format, result))
                        .whenComplete((ignored, failure) -> {
                            JsonObject close = new JsonObject(); close.addProperty("session_id", sessionId);
                            runtime.callTool("close_session", close, Duration.ofSeconds(5)).exceptionally(x -> null);
                        });
            });
        });
    }

    private static String sessionId(JsonObject result) {
        if (result != null && result.has("structuredContent") && result.get("structuredContent").isJsonObject()) {
            JsonObject s = result.getAsJsonObject("structuredContent");
            for (String key : List.of("session_id", "sessionId", "id")) if (s.has(key) && s.get(key).isJsonPrimitive()) return s.get(key).getAsString();
        }
        if (result != null && result.has("content") && result.get("content").isJsonArray()) {
            for (JsonElement e : result.getAsJsonArray("content")) if (e.isJsonObject()) {
                JsonObject b=e.getAsJsonObject(); if (b.has("text") && b.get("text").isJsonPrimitive()) {
                    String text=b.get("text").getAsString();
                    try { JsonObject o=com.google.gson.JsonParser.parseString(text).getAsJsonObject(); for(String key:List.of("session_id","sessionId","id")) if(o.has(key)) return o.get(key).getAsString(); } catch(RuntimeException ignored) { }
                }
            }
        }
        return "";
    }

    private static Screenshot persistScreenshot(URI target, String format, JsonObject result) {
        String data = ""; String mime = "png".equals(format) ? "image/png" : "image/jpeg";
        if (result != null && result.has("content") && result.get("content").isJsonArray()) {
            for (JsonElement e : result.getAsJsonArray("content")) if (e.isJsonObject()) {
                JsonObject block=e.getAsJsonObject();
                if (block.has("data") && block.get("data").isJsonPrimitive()) { data=block.get("data").getAsString(); if(block.has("mimeType")) mime=block.get("mimeType").getAsString(); break; }
            }
        }
        if (data.isBlank()) throw new IllegalStateException("Scraping runtime returned no screenshot image block.");
        byte[] bytes; try { bytes=Base64.getDecoder().decode(data); } catch(IllegalArgumentException e) { throw new IllegalStateException("Scraping runtime returned invalid screenshot data.", e); }
        if (bytes.length > 12 * 1024 * 1024) throw new IllegalStateException("Screenshot exceeds the 12 MiB artifact bound.");
        try {
            Path root=KoilInstancePaths.modelRoot().resolve("internet-artifacts"); Files.createDirectories(root);
            String ext="png".equals(format)?"png":"jpg"; Path file=root.resolve("internet-screenshot-"+java.util.UUID.randomUUID().toString().substring(0,12)+"."+ext);
            Files.write(file,bytes); return new Screenshot(target, KoilInstancePaths.modelRoot().relativize(file).toString().replace('\\','/'), mime, bytes.length, RUNTIME_ID);
        } catch(java.io.IOException e) { throw new IllegalStateException("Unable to persist screenshot artifact: "+e.getMessage(), e); }
    }

    @Override public McpRuntimeHealth health() { return runtime.health(); }
    @Override public JsonObject diagnostics() { return runtime.lifecycleSnapshot(); }
    @Override public void close() { runtime.close(); }

    private static Page page(URI fallbackUrl, JsonObject result) {
        JsonObject structured = result != null && result.has("structuredContent") && result.get("structuredContent").isJsonObject()
                ? result.getAsJsonObject("structuredContent") : result == null ? new JsonObject() : result;
        int status = structured.has("status") ? structured.get("status").getAsInt() : 0;
        URI url = fallbackUrl;
        if (structured.has("url")) {
            try { url = PublicNetworkPolicy.validate(URI.create(structured.get("url").getAsString())); }
            catch (RuntimeException ignored) { }
        }
        List<String> parts = new ArrayList<>();
        if (structured.has("content") && structured.get("content").isJsonArray()) {
            for (JsonElement part : structured.getAsJsonArray("content")) add(parts, part.getAsString());
        }
        if (parts.isEmpty() && result != null && result.has("content") && result.get("content").isJsonArray()) {
            for (JsonElement item : result.getAsJsonArray("content")) if (item.isJsonObject()) {
                JsonObject block = item.getAsJsonObject();
                if (block.has("text")) add(parts, block.get("text").getAsString());
            }
        }
        return new Page(url, status, parts, RUNTIME_ID);
    }

    private static void add(List<String> parts, String value) {
        if (parts.size() >= MAXIMUM_CONTENT_PARTS || value == null || value.isBlank()) return;
        int used = parts.stream().mapToInt(String::length).sum();
        int remaining = MAXIMUM_CONTENT_CHARACTERS - used;
        if (remaining <= 0) return;
        parts.add(value.length() <= remaining ? value : value.substring(0, Math.max(0, remaining - 1)) + "…");
    }
}
