package com.spirit.koil.api.model.tool;

import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;

/** Explicit live check for the Koil-owned MCP discovery tool and its public source normalizer. */
public final class McpCatalogueIntegrationProbe {
    private McpCatalogueIntegrationProbe() { }

    public static void main(String[] args) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("operation", "search");
        arguments.addProperty("query", "postgres");
        arguments.addProperty("limit", 3);
        var result = McpCatalogueModelToolRegistry.execute(new ModelToolCall(
                "catalogue-live", McpCatalogueModelToolRegistry.TOOL_ID, arguments)).join();
        if (!"completed".equals(result.status()) || result.output().getAsJsonArray("entries").isEmpty()) {
            throw new IllegalStateException("MCP catalogue returned no usable discovery result: " + result.detail());
        }
        System.out.println("MCP catalogue integration probe passed.");
    }
}
