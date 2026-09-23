package com.spirit.koil.api.model.tool;

import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;

import java.net.URI;

/** Explicit live-provider probe; unlike deterministic proofs it requires configured Scrapling and public network. */
public final class ScraplingMcpIntegrationProbe {
    private ScraplingMcpIntegrationProbe() { }

    public static void main(String[] args) {
        try (ScraplingMcpProvider provider = ScraplingMcpProvider.managed()) {
            InternetResearchModelToolRegistry.configureScrapingProvider(provider);
            JsonObject arguments = new JsonObject();
            arguments.addProperty("url", URI.create("https://example.com/").toString());
            var result = InternetResearchModelToolRegistry.execute(new ModelToolCall(
                    "scrapling-live", InternetResearchModelToolRegistry.SCRAPE, arguments)).join();
            if (!"completed".equals(result.status()) || result.output().getAsJsonArray("records").isEmpty()) {
                throw new IllegalStateException("Scraping model tool returned no usable public page: " + result.detail());
            }
            System.out.println("Scrapling MCP integration probe passed.");
        } finally {
            InternetResearchModelToolRegistry.configureScrapingProvider(null);
        }
    }
}
