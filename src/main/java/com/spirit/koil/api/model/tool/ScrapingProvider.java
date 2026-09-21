package com.spirit.koil.api.model.tool;

import com.spirit.koil.api.mcp.McpRuntimeHealth;
import com.google.gson.JsonObject;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Provider-neutral advanced public-page extraction boundary. */
public interface ScrapingProvider extends AutoCloseable {
    CompletableFuture<Page> fetch(URI url, String cssSelector, boolean dynamic);
    default CompletableFuture<Screenshot> screenshot(URI url, boolean fullPage, String imageType, int quality) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Screenshot is unavailable from this scraping provider."));
    }
    McpRuntimeHealth health();
    default JsonObject diagnostics() { return new JsonObject(); }

    record Screenshot(URI url, String artifactPath, String mimeType, long bytes, String provider) { }

    record Page(URI url, int statusCode, List<String> content, String provider) {
        public Page {
            url = url == null ? URI.create("https://invalid.invalid/") : url;
            statusCode = Math.max(0, statusCode);
            content = content == null ? List.of() : List.copyOf(content);
            provider = provider == null ? "" : provider;
        }
    }
}
