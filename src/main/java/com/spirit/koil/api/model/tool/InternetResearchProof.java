package com.spirit.koil.api.model.tool;

import com.google.gson.JsonObject;
import com.spirit.koil.api.mcp.McpRuntimeHealth;
import com.spirit.koil.api.model.ModelToolCall;

import java.net.URI;
import java.util.List;

/** Deterministic trust-boundary proof for every future public-network provider. */
public final class InternetResearchProof {
    private InternetResearchProof() {}

    public static void main(String[] args) {
        require(PublicNetworkPolicy.validate(URI.create("https://example.com/read")).getHost().equals("example.com"),
                "a public HTTPS address should be accepted");
        reject("http://example.com", "plain HTTP must not bypass the research policy");
        reject("file:///tmp/private.txt", "file URIs must not bypass the research policy");
        reject("https://localhost", "localhost must be rejected");
        reject("https://127.0.0.1", "loopback must be rejected");
        reject("https://10.0.0.1", "private IPv4 must be rejected");
        reject("https://169.254.169.254", "link-local IPv4 must be rejected");
        reject("https://[::1]", "IPv6 loopback must be rejected");
        proveProviderFallback();
        proveCatalogueNormalization();
        proveCatalogueRouting();
        proveBoundedScrapeAndCrawl();
        System.out.println("Internet research proof passed.");
    }

    private static void proveCatalogueNormalization() {
        List<McpCatalogueEntry> entries = McpCatalogueService.parse("### Databases\n- [Example/Postgres](https://github.com/example/postgres) 🐍 🏠 - Inspect PostgreSQL schemas.\n", "proof");
        require(entries.size() == 1 && entries.get(0).stableId().startsWith("mcp-catalogue-")
                        && entries.get(0).trustState() == McpCatalogueEntry.TrustState.DISCOVERED,
                "catalogue normalization must preserve discovery-only trust");
    }

    private static void proveCatalogueRouting() {
        String prompt = "use the mcp-cataloge tool to find a postgres integration";
        require(LocalModelToolCatalog.toolsForPrompt(prompt).stream()
                        .anyMatch(tool -> McpCatalogueModelToolRegistry.TOOL_ID.equals(tool.id())),
                "the exact catalogue tool name must survive a common spelling correction in automation routing");
        require(LocalModelToolCatalog.informationToolsForPrompt(prompt).stream()
                        .anyMatch(tool -> McpCatalogueModelToolRegistry.TOOL_ID.equals(tool.id())),
                "the exact catalogue tool name must be callable from model ask mode");
    }

    private static void proveBoundedScrapeAndCrawl() {
        ScrapingProvider fixture = new ScrapingProvider() {
            @Override public java.util.concurrent.CompletableFuture<Page> fetch(URI url, String selector, boolean dynamic) {
                return java.util.concurrent.CompletableFuture.completedFuture(new Page(url, 200,
                        List.of("<a href=\"/one\">one</a><a href=\"https://outside.example/two\">outside</a>"), "fixture"));
            }
            @Override public McpRuntimeHealth health() { return new McpRuntimeHealth(McpRuntimeHealth.State.READY, "fixture", "ready"); }
            @Override public void close() { }
        };
        InternetResearchModelToolRegistry.configureScrapingProvider(fixture);
        try {
            JsonObject scrapeArgs = new JsonObject();
            scrapeArgs.addProperty("url", "https://example.com/models");
            JsonObject selectors = new JsonObject();
            selectors.addProperty("model", ".model");
            scrapeArgs.add("selectors", selectors);
            var scraped = InternetResearchModelToolRegistry.execute(new ModelToolCall("scrape", InternetResearchModelToolRegistry.SCRAPE, scrapeArgs)).join();
            require("completed".equals(scraped.status()) && scraped.output().getAsJsonArray("records").size() == 1,
                    "structured scrape did not return bounded named records");
            JsonObject crawlArgs = new JsonObject();
            crawlArgs.addProperty("startUrl", "https://example.com/docs");
            crawlArgs.addProperty("maxPages", 2);
            crawlArgs.addProperty("maxDepth", 1);
            crawlArgs.addProperty("allowedDomain", "example.com");
            crawlArgs.addProperty("allowedPathPrefix", "/");
            var crawled = InternetResearchModelToolRegistry.execute(new ModelToolCall("crawl", InternetResearchModelToolRegistry.CRAWL, crawlArgs)).join();
            require("completed".equals(crawled.status()) && crawled.output().getAsJsonArray("pages").size() <= 2,
                    "crawl did not preserve explicit page bounds");
            require(LocalModelToolCatalog.toolsForPrompt("scrape this website").stream()
                            .anyMatch(tool -> InternetResearchModelToolRegistry.SCRAPE.equals(tool.id())),
                    "exact scraping vocabulary did not select internet.scrape");
        } finally {
            InternetResearchModelToolRegistry.configureScrapingProvider(null);
        }
    }

    private static void proveProviderFallback() {
        InternetSearchRouter router = new InternetSearchRouter(List.of(
                new InternetSearchProvider() {
                    @Override public String id() { return "primary"; }
                    @Override public List<InternetSearchResult> search(String query, int maximum) {
                        throw new IllegalStateException("503 unavailable");
                    }
                },
                new InternetSearchProvider() {
                    @Override public String id() { return "secondary"; }
                    @Override public List<InternetSearchResult> search(String query, int maximum) {
                        return List.of(new InternetSearchResult("Result", "https://example.com", "proof", "", 1));
                    }
                }
        ));
        try {
            InternetSearchRouter.SearchResponse result = router.search("proof", 3);
            require("secondary".equals(result.providerId()) && result.results().size() == 1,
                    "healthy fallback provider was not selected");
            require(router.health().get("primary").state() == InternetProviderState.DEGRADED,
                    "failed provider health was not retained as deterministic state");
        } catch (Exception failure) {
            throw new IllegalStateException("provider fallback proof failed", failure);
        }
    }

    private static void reject(String raw, String message) {
        try {
            PublicNetworkPolicy.validate(URI.create(raw));
            throw new IllegalStateException(message);
        } catch (IllegalArgumentException expected) {
            // Expected public-network rejection.
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
