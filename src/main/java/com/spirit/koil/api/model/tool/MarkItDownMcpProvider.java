package com.spirit.koil.api.model.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.mcp.McpRuntimeHealth;
import com.spirit.koil.api.mcp.McpRuntimeManager;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Persistent MCP adapter for rich-file normalization. Paths/URIs must be authorized by Koil before reaching this class. */
public final class MarkItDownMcpProvider implements ContentProvider {
    private final McpRuntimeManager runtime;

    public MarkItDownMcpProvider(Supplier<Process> starter) {
        runtime = new McpRuntimeManager("content", starter, Set.of("convert_to_markdown"));
    }

    public static MarkItDownMcpProvider managed() {
        String configured = System.getProperty("koil.markitdown.mcp.executable", "").strip();
        String executable = configured.isBlank() ? "markitdown-mcp" : configured;
        return new MarkItDownMcpProvider(() -> {
            try { return new ProcessBuilder(executable).start(); }
            catch (java.io.IOException e) { throw new IllegalStateException("Content normalization runtime is unavailable: " + e.getMessage(), e); }
        });
    }

    @Override public String id() { return "rich-content"; }
    @Override public CompletableFuture<JsonObject> normalize(Path localFile) { return convert(localFile.toUri()); }
    @Override public CompletableFuture<JsonObject> normalize(URI remoteUri) { return convert(PublicNetworkPolicy.validate(remoteUri)); }
    @Override public JsonObject diagnostics() { return runtime.lifecycleSnapshot(); }
    @Override public void close() { runtime.close(); }

    private CompletableFuture<JsonObject> convert(URI uri) {
        return runtime.start().thenCompose(health -> {
            if (health.state() != McpRuntimeHealth.State.READY) {
                return CompletableFuture.failedFuture(new IllegalStateException(health.detail()));
            }
            JsonObject args = new JsonObject();
            args.addProperty("uri", uri.toString());
            return runtime.callTool("convert_to_markdown", args, Duration.ofSeconds(60)).thenApply(MarkItDownMcpProvider::normalizeResult);
        });
    }

    private static JsonObject normalizeResult(JsonObject result) {
        JsonObject out = new JsonObject();
        String markdown = "";
        if (result != null && result.has("structuredContent") && result.get("structuredContent").isJsonObject()) {
            JsonObject structured = result.getAsJsonObject("structuredContent");
            markdown = firstString(structured, "markdown", "text", "content");
            out.add("providerResult", structured.deepCopy());
        }
        if (markdown.isBlank() && result != null && result.has("content") && result.get("content").isJsonArray()) {
            StringBuilder joined = new StringBuilder();
            for (JsonElement element : result.getAsJsonArray("content")) {
                if (!element.isJsonObject()) continue;
                JsonObject block = element.getAsJsonObject();
                if (block.has("text") && block.get("text").isJsonPrimitive()) {
                    if (!joined.isEmpty()) joined.append('\n');
                    joined.append(block.get("text").getAsString());
                }
            }
            markdown = joined.toString();
        }
        out.addProperty("markdown", markdown);
        out.addProperty("characters", markdown.length());
        return out;
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) if (object.has(key) && object.get(key).isJsonPrimitive()) return object.get(key).getAsString();
        return "";
    }
}
