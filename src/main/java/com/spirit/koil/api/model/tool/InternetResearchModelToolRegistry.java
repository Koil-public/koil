package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ToolExecutionPolicy;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
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
    public static final String SCRAPE = "internet.scrape";
    public static final String CRAWL = "internet.crawl";
    public static final String SCREENSHOT = "internet.screenshot";
    private static final int MAXIMUM_BODY_CHARACTERS = 16_000;
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final Map<String, ModelToolDefinition> DEFINITIONS = definitions();
    private static final Pattern RESULT_LINK = Pattern.compile(
            "(?is)<a[^>]+(?:class=\"result__a\"|data-testid=\"result-title-a\")[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>"
    );
    private static final Pattern RESULT_SNIPPET = Pattern.compile(
            "(?is)<(?:a|div)[^>]+(?:class=\"result__snippet\"|data-result=\"snippet\")[^>]*>(.*?)</(?:a|div)>"
    );
    private static final Pattern HREF = Pattern.compile("(?is)\\bhref\\s*=\\s*[\\\"']([^\\\"'#]+)");
    private static final InternetSearchRouter SEARCH_ROUTER = new InternetSearchRouter(List.of(new InternetSearchProvider() {
        @Override public String id() { return "native_public_html"; }
        @Override public List<InternetSearchResult> search(String query, int maximum) throws Exception {
            return nativePublicSearch(query, maximum);
        }
    }));
    private static volatile ScrapingProvider SCRAPING_PROVIDER = ScraplingMcpProvider.managed();

    private InternetResearchModelToolRegistry() {}

    public static List<ModelToolDefinition> modelTools() { return List.copyOf(DEFINITIONS.values()); }
    public static boolean supports(String id) { return id != null && DEFINITIONS.containsKey(id); }
    public static Map<String, InternetSearchRouter.ProviderHealth> searchProviderHealth() { return SEARCH_ROUTER.health(); }
    public static void configureScrapingProvider(ScrapingProvider provider) {
        ScrapingProvider previous = SCRAPING_PROVIDER;
        SCRAPING_PROVIDER = provider == null ? ScraplingMcpProvider.managed() : provider;
        if (previous != null && previous != SCRAPING_PROVIDER) try { previous.close(); }
        catch (Exception ignored) { }
    }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        if (call == null || !supports(call.toolId())) return CompletableFuture.completedFuture(failure(call, "unknown_tool", "Unknown internet research tool."));
        return CompletableFuture.supplyAsync(() -> {
            try {
                return switch (call.toolId()) {
                    case SEARCH -> search(call);
                    case FETCH -> fetch(call);
                    case SCRAPE -> scrape(call);
                    case CRAWL -> crawl(call);
                    case SCREENSHOT -> screenshot(call);
                    default -> failure(call, "unknown_tool", "Unknown internet research tool.");
                };
            } catch (Exception exception) {
                return failure(call, "internet_research_failed", message(exception));
            }
        });
    }

    private static ModelToolResult search(ModelToolCall call) throws Exception {
        String query = required(call.arguments(), "query");
        int maximum = integer(call.arguments(), "maxResults", 5, 1, 8);
        InternetSearchRouter.SearchResponse response = SEARCH_ROUTER.search(query, maximum);
        JsonArray results = new JsonArray();
        for (InternetSearchResult candidate : response.results()) {
            JsonObject result = new JsonObject();
            result.addProperty("title", candidate.title());
            result.addProperty("url", candidate.url());
            result.addProperty("snippet", candidate.snippet());
            result.addProperty("publishedAt", candidate.publishedAt());
            result.addProperty("rank", candidate.providerRank());
            results.add(result);
        }
        JsonObject output = new JsonObject();
        output.addProperty("query", query);
        output.addProperty("provider", response.providerId());
        output.add("results", results);
        output.addProperty("resultCount", results.size());
        output.addProperty("informationalOnly", true);
        return completed(call, output, results.isEmpty()
                ? "The public search returned no parsed result links."
                : "Public search results were retrieved for read-only research.");
    }

    private static List<InternetSearchResult> nativePublicSearch(String query, int maximum) throws Exception {
        URI uri = URI.create("https://html.duckduckgo.com/html/?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8));
        HttpResponse<String> response = request(uri);
        List<String> snippets = new java.util.ArrayList<>();
        Matcher snippetMatcher = RESULT_SNIPPET.matcher(response.body());
        while (snippetMatcher.find() && snippets.size() < maximum) snippets.add(cleanHtml(snippetMatcher.group(1), 480));
        List<InternetSearchResult> results = new java.util.ArrayList<>();
        Matcher matcher = RESULT_LINK.matcher(response.body());
        while (matcher.find() && results.size() < maximum) {
            String url = decodeSearchUrl(matcher.group(1));
            if (url.isBlank()) continue;
            results.add(new InternetSearchResult(cleanHtml(matcher.group(2), 240), url,
                    snippets.size() > results.size() ? snippets.get(results.size()) : "", "", results.size() + 1));
        }
        return List.copyOf(results);
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

    private static ModelToolResult scrape(ModelToolCall call) throws Exception {
        URI uri = validatedPublicUri(required(call.arguments(), "url"));
        boolean dynamic = bool(call.arguments(), "dynamic", false);
        JsonObject selectors = call.arguments() != null && call.arguments().has("selectors")
                && call.arguments().get("selectors").isJsonObject() ? call.arguments().getAsJsonObject("selectors") : null;
        JsonArray records = new JsonArray();
        if (selectors != null && !selectors.entrySet().isEmpty()) {
            if (selectors.size() > 8) throw new IllegalArgumentException("At most 8 named selectors are allowed.");
            for (Map.Entry<String, com.google.gson.JsonElement> entry : selectors.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) throw new IllegalArgumentException("Every selector must be text.");
                ScrapingProvider.Page page = SCRAPING_PROVIDER.fetch(uri, entry.getValue().getAsString(), dynamic).join();
                records.add(scrapeRecord(entry.getKey(), page));
            }
        } else {
            records.add(scrapeRecord("content", SCRAPING_PROVIDER.fetch(uri, optional(call.arguments(), "selector"), dynamic).join()));
        }
        JsonObject output = new JsonObject();
        output.addProperty("url", uri.toString());
        output.addProperty("provider", "advanced_scraping");
        output.addProperty("dynamic", dynamic);
        output.add("records", records);
        output.addProperty("recordCount", records.size());
        attachProviderDiagnostics(output);
        output.addProperty("informationalOnly", true);
        return completed(call, output, "Structured public-page extraction completed through the configured advanced scraping provider.");
    }

    private static JsonObject scrapeRecord(String field, ScrapingProvider.Page page) {
        JsonArray values = new JsonArray();
        for (String part : page.content()) values.add(cleanHtml(part, 4_000));
        JsonObject record = new JsonObject();
        record.addProperty("field", field == null ? "content" : field);
        record.addProperty("url", page.url().toString());
        record.addProperty("statusCode", page.statusCode());
        record.add("values", values);
        return record;
    }

    private static ModelToolResult crawl(ModelToolCall call) throws Exception {
        URI start = validatedPublicUri(required(call.arguments(), "startUrl"));
        int maxPages = integer(call.arguments(), "maxPages", 0, 1, 16);
        int maxDepth = integer(call.arguments(), "maxDepth", -1, 0, 4);
        if (maxPages == 0 || maxDepth < 0) throw new IllegalArgumentException("maxPages and maxDepth are required explicit crawl bounds.");
        String domain = optional(call.arguments(), "allowedDomain");
        if (domain == null || domain.isBlank()) domain = start.getHost();
        String pathPrefix = optional(call.arguments(), "allowedPathPrefix");
        if (pathPrefix == null || pathPrefix.isBlank()) pathPrefix = "/";
        int maxCharacters = integer(call.arguments(), "maxCharacters", 8_000, 256, 16_000);
        ArrayDeque<CrawlTarget> pending = new ArrayDeque<>();
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        pending.add(new CrawlTarget(start, 0));
        JsonArray pages = new JsonArray();
        while (!pending.isEmpty() && pages.size() < maxPages) {
            CrawlTarget target = pending.removeFirst();
            if (!visited.add(target.uri().toString())) continue;
            ScrapingProvider.Page page = SCRAPING_PROVIDER.fetch(target.uri(), null, false).join();
            JsonObject result = new JsonObject();
            result.addProperty("url", page.url().toString());
            result.addProperty("statusCode", page.statusCode());
            result.addProperty("depth", target.depth());
            String joined = compact(String.join("\n", page.content()), maxCharacters);
            result.addProperty("extract", cleanHtml(joined, maxCharacters));
            pages.add(result);
            if (target.depth() >= maxDepth) continue;
            for (URI next : links(target.uri(), page.content(), domain, pathPrefix)) {
                if (visited.size() + pending.size() >= maxPages) break;
                pending.addLast(new CrawlTarget(next, target.depth() + 1));
            }
        }
        JsonObject output = new JsonObject();
        output.addProperty("startUrl", start.toString());
        output.addProperty("allowedDomain", domain);
        output.addProperty("allowedPathPrefix", pathPrefix);
        output.addProperty("maxPages", maxPages);
        output.addProperty("maxDepth", maxDepth);
        output.add("pages", pages);
        output.addProperty("pageCount", pages.size());
        attachProviderDiagnostics(output);
        output.addProperty("informationalOnly", true);
        return completed(call, output, "Bounded same-domain crawl completed through the configured advanced scraping provider.");
    }


    private static ModelToolResult screenshot(ModelToolCall call) throws Exception {
        URI uri = validatedPublicUri(required(call.arguments(), "url"));
        boolean fullPage = bool(call.arguments(), "fullPage", false);
        String format = optional(call.arguments(), "format");
        if (format == null || format.isBlank()) format = "png";
        format = "jpeg".equalsIgnoreCase(format) ? "jpeg" : "png";
        int quality = integer(call.arguments(), "quality", 80, 1, 100);
        ScrapingProvider.Screenshot shot = SCRAPING_PROVIDER.screenshot(uri, fullPage, format, quality).join();
        JsonObject output = new JsonObject();
        output.addProperty("url", shot.url().toString());
        output.addProperty("artifactPath", shot.artifactPath());
        output.addProperty("mimeType", shot.mimeType());
        output.addProperty("bytes", shot.bytes());
        output.addProperty("provider", shot.provider());
        output.addProperty("fullPage", fullPage);
        attachProviderDiagnostics(output);
        output.addProperty("informationalOnly", true);
        return completed(call, output, "A bounded public-page screenshot was captured into Koil-owned temporary storage.");
    }

    private static void attachProviderDiagnostics(JsonObject output) {
        if (output == null || SCRAPING_PROVIDER == null) return;
        try {
            JsonObject diagnostics = SCRAPING_PROVIDER.diagnostics();
            if (diagnostics == null) return;
            for (Map.Entry<String, com.google.gson.JsonElement> entry : diagnostics.entrySet()) {
                if (!output.has(entry.getKey())) output.add(entry.getKey(), entry.getValue().deepCopy());
            }
        } catch (RuntimeException ignored) {
            // Diagnostics are fail-open and never allowed to break the actual tool result.
        }
    }

    private static List<URI> links(URI base, List<String> content, String domain, String pathPrefix) {
        LinkedHashSet<URI> found = new LinkedHashSet<>();
        Matcher matcher = HREF.matcher(String.join("\n", content));
        while (matcher.find() && found.size() < 32) try {
            URI uri = PublicNetworkPolicy.validate(base.resolve(matcher.group(1)));
            if (uri.getHost().equalsIgnoreCase(domain) && uri.getPath().startsWith(pathPrefix)) found.add(uri);
        } catch (RuntimeException ignored) { }
        return List.copyOf(found);
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

    private static URI validatedPublicUri(String raw) {
        return PublicNetworkPolicy.validate(URI.create(raw.strip()));
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

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsBoolean() : fallback;
    }

    private static String optional(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString().strip() : null;
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
        JsonObject scrape = objectSchema();
        scrape.getAsJsonObject("properties").add("url", stringSchema());
        scrape.getAsJsonObject("properties").add("selector", stringSchema());
        JsonObject selectorMap = objectSchema();
        selectorMap.addProperty("additionalProperties", true);
        scrape.getAsJsonObject("properties").add("selectors", selectorMap);
        scrape.getAsJsonObject("properties").add("dynamic", booleanSchema());
        scrape.add("required", array("url"));
        definitions.put(SCRAPE, definition(SCRAPE,
                "Extract bounded named CSS-selected records from one public HTTPS page. Uses the advanced scraping provider only when structured extraction or rendered content is needed.", scrape));
        JsonObject crawl = objectSchema();
        crawl.getAsJsonObject("properties").add("startUrl", stringSchema());
        crawl.getAsJsonObject("properties").add("allowedDomain", stringSchema());
        crawl.getAsJsonObject("properties").add("allowedPathPrefix", stringSchema());
        crawl.getAsJsonObject("properties").add("maxPages", integerSchema(1, 16));
        crawl.getAsJsonObject("properties").add("maxDepth", integerSchema(0, 4));
        crawl.getAsJsonObject("properties").add("maxCharacters", integerSchema(256, 16_000));
        crawl.add("required", array("startUrl", "maxPages", "maxDepth"));
        definitions.put(CRAWL, definition(CRAWL,
                "Crawl a strictly bounded public HTTPS scope through the advanced scraping provider. Requires explicit page and depth limits and never leaves the allowed domain/path.", crawl));
        JsonObject screenshot = objectSchema();
        screenshot.getAsJsonObject("properties").add("url", stringSchema());
        screenshot.getAsJsonObject("properties").add("fullPage", booleanSchema());
        JsonObject format = stringSchema(); JsonArray formats = new JsonArray(); formats.add("png"); formats.add("jpeg"); format.add("enum", formats);
        screenshot.getAsJsonObject("properties").add("format", format);
        screenshot.getAsJsonObject("properties").add("quality", integerSchema(1, 100));
        screenshot.add("required", array("url"));
        definitions.put(SCREENSHOT, definition(SCREENSHOT,
                "Capture a bounded screenshot of one public HTTPS page when rendered visual state matters. Uses an isolated scraping browser session and stores only the resulting Koil-owned artifact.", screenshot));
        return Map.copyOf(definitions);
    }

    private static ModelToolDefinition definition(String id, String description, JsonObject schema) {
        return new ModelToolDefinition(id, description, schema, List.of("public_network_available"),
                Set.of(), true, Duration.ofSeconds(25), true, false,
                Set.of("completed", "failed", "unsupported"),
                ToolExecutionPolicy.readOnly(
                        ToolExecutionPolicy.FreshnessMode.REMOTE,
                        ToolExecutionPolicy.CostClass.EXPENSIVE
                ));
    }

    private static JsonObject objectSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject stringSchema() { JsonObject schema = new JsonObject(); schema.addProperty("type", "string"); return schema; }
    private static JsonObject booleanSchema() { JsonObject schema = new JsonObject(); schema.addProperty("type", "boolean"); return schema; }
    private static JsonObject integerSchema(int min, int max) { JsonObject schema = new JsonObject(); schema.addProperty("type", "integer"); schema.addProperty("minimum", min); schema.addProperty("maximum", max); return schema; }
    private static JsonArray array(String... values) { JsonArray array = new JsonArray(); for (String value : values) array.add(value); return array; }
    private record CrawlTarget(URI uri, int depth) {}
}
