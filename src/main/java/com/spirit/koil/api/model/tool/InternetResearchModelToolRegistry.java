package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;

import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, information-only public-web research tools. */
public final class InternetResearchModelToolRegistry {
    public static final String SEARCH = "internet.search";
    public static final String FETCH = "internet.fetch";
    private static final int MAXIMUM_BODY_CHARACTERS = 16_000;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final Map<String, ModelToolDefinition> DEFINITIONS = definitions();
    private static final Pattern RESULT_LINK = Pattern.compile(
            "(?is)<a[^>]+(?:class=\"result__a\"|data-testid=\"result-title-a\")[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>"
    );

    private InternetResearchModelToolRegistry() {}

    public static List<ModelToolDefinition> modelTools() { return List.copyOf(DEFINITIONS.values()); }
    public static boolean supports(String id) { return id != null && DEFINITIONS.containsKey(id); }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        if (call == null || !supports(call.toolId())) return CompletableFuture.completedFuture(failure(call, "unknown_tool", "Unknown internet research tool."));
        return CompletableFuture.supplyAsync(() -> {
            try {
                return SEARCH.equals(call.toolId()) ? search(call) : fetch(call);
            } catch (Exception exception) {
                return failure(call, "internet_research_failed", message(exception));
            }
        });
    }

    private static ModelToolResult search(ModelToolCall call) throws Exception {
        String query = required(call.arguments(), "query");
        int maximum = integer(call.arguments(), "maxResults", 5, 1, 8);
        URI uri = URI.create("https://html.duckduckgo.com/html/?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8));
        HttpResponse<String> response = request(uri);
        JsonArray results = new JsonArray();
        Matcher matcher = RESULT_LINK.matcher(response.body());
        while (matcher.find() && results.size() < maximum) {
            JsonObject result = new JsonObject();
            result.addProperty("title", cleanHtml(matcher.group(2), 240));
            result.addProperty("url", decodeSearchUrl(matcher.group(1)));
            results.add(result);
        }
        JsonObject output = new JsonObject();
        output.addProperty("query", query);
        output.addProperty("provider", "DuckDuckGo public HTML search");
        output.add("results", results);
        output.addProperty("resultCount", results.size());
        output.addProperty("informationalOnly", true);
        if (results.isEmpty()) output.addProperty("pageExtract", cleanHtml(response.body(), 4_000));
        return completed(call, output, results.isEmpty()
                ? "The public search returned no parsed result links; a bounded page extract is included."
                : "Public search results were retrieved for read-only research.");
    }

    private static ModelToolResult fetch(ModelToolCall call) throws Exception {
        URI uri = validatedPublicUri(required(call.arguments(), "url"));
        HttpResponse<String> response = request(uri);
        String contentType = response.headers().firstValue("content-type").orElse("");
        String body = response.body() == null ? "" : response.body();
        String extract = contentType.toLowerCase(Locale.ROOT).contains("html")
                ? cleanHtml(body, MAXIMUM_BODY_CHARACTERS)
                : compact(body, MAXIMUM_BODY_CHARACTERS);
        JsonObject output = new JsonObject();
        output.addProperty("url", uri.toString());
        output.addProperty("statusCode", response.statusCode());
        output.addProperty("contentType", contentType);
        output.addProperty("extract", extract);
        output.addProperty("charactersReturned", extract.length());
        output.addProperty("truncated", extract.length() >= MAXIMUM_BODY_CHARACTERS);
        output.addProperty("informationalOnly", true);
        return completed(call, output, "A bounded public-page extract was retrieved for read-only research.");
    }

    private static HttpResponse<String> request(URI initial) throws Exception {
        URI uri = validatedPublicUri(initial.toString());
        for (int redirects = 0; redirects <= 3; redirects++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Koil-ReadOnly-Research/1.0")
                    .header("Accept", "text/html,text/plain,application/json;q=0.8")
                    .GET().build();
            HttpResponse<String> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 3) return response;
            String location = response.headers().firstValue("location")
                    .orElseThrow(() -> new IllegalArgumentException("Redirect had no destination."));
            uri = validatedPublicUri(uri.resolve(location).toString());
        }
        throw new IllegalArgumentException("Too many redirects.");
    }

    private static URI validatedPublicUri(String raw) throws Exception {
        URI uri = URI.create(raw.strip());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Only public HTTPS URLs are supported.");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalArgumentException("Local and private network addresses are not permitted.");
            }
        }
        return uri;
    }

    private static String decodeSearchUrl(String value) {
        String decoded = value == null ? "" : value.replace("&amp;", "&");
        int marker = decoded.indexOf("uddg=");
        if (marker >= 0) {
            String encoded = decoded.substring(marker + 5).split("&", 2)[0];
            try { return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8); }
            catch (IllegalArgumentException ignored) {}
        }
        return decoded;
    }

    private static String cleanHtml(String html, int maximum) {
        String text = html == null ? "" : html
                .replaceAll("(?is)<script.*?</script>|<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        return compact(text, maximum);
    }

    private static String compact(String value, int maximum) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return clean.length() <= maximum ? clean : clean.substring(0, maximum - 1) + "…";
    }

    private static String required(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()
                || object.get(key).getAsString().isBlank()) throw new IllegalArgumentException(key + " is required.");
        return object.get(key).getAsString().strip();
    }

    private static int integer(JsonObject object, String key, int fallback, int minimum, int maximum) {
        if (object == null || !object.has(key)) return fallback;
        return Math.max(minimum, Math.min(maximum, object.get(key).getAsInt()));
    }

    private static ModelToolResult completed(ModelToolCall call, JsonObject output, String detail) {
        return new ModelToolResult(call.id(), call.toolId(), "completed", output, "", detail);
    }

    private static ModelToolResult failure(ModelToolCall call, String code, String detail) {
        return new ModelToolResult(call == null ? "" : call.id(), call == null ? "" : call.toolId(),
                "failed", new JsonObject(), code, detail);
    }

    private static String message(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static Map<String, ModelToolDefinition> definitions() {
        Map<String, ModelToolDefinition> definitions = new LinkedHashMap<>();
        JsonObject search = objectSchema();
        search.getAsJsonObject("properties").add("query", stringSchema());
        search.getAsJsonObject("properties").add("maxResults", integerSchema(1, 8));
        search.add("required", array("query"));
        definitions.put(SEARCH, definition(SEARCH,
                "Search the public internet for read-only research. Returns bounded result titles and URLs; it cannot manipulate Minecraft, files, or other systems.", search));
        JsonObject fetch = objectSchema();
        fetch.getAsJsonObject("properties").add("url", stringSchema());
        fetch.add("required", array("url"));
        definitions.put(FETCH, definition(FETCH,
                "Retrieve one bounded public HTTPS page extract for read-only research. Local/private addresses and mutations are forbidden.", fetch));
        return Map.copyOf(definitions);
    }

    private static ModelToolDefinition definition(String id, String description, JsonObject schema) {
        return new ModelToolDefinition(id, description, schema, List.of("public_network_available"),
                Set.of(), true, Duration.ofSeconds(25), true, false,
                Set.of("completed", "failed", "unsupported"));
    }

    private static JsonObject objectSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject stringSchema() { JsonObject schema = new JsonObject(); schema.addProperty("type", "string"); return schema; }
    private static JsonObject integerSchema(int min, int max) { JsonObject schema = new JsonObject(); schema.addProperty("type", "integer"); schema.addProperty("minimum", min); schema.addProperty("maximum", max); return schema; }
    private static JsonArray array(String... values) { JsonArray array = new JsonArray(); for (String value : values) array.add(value); return array; }
}
