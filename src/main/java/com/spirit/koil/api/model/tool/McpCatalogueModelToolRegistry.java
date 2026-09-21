package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ToolExecutionPolicy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Koil-owned, read-only discovery surface for untrusted external MCP entries. */
public final class McpCatalogueModelToolRegistry {
    public static final String TOOL_ID = "mcp-catalogue";
    private static final ModelToolDefinition DEFINITION = definition();

    private McpCatalogueModelToolRegistry() {}
    public static List<ModelToolDefinition> modelTools() { return List.of(DEFINITION); }
    public static boolean supports(String id) { return TOOL_ID.equals(id); }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        if (call == null || !supports(call.toolId())) return CompletableFuture.completedFuture(failed(call, "unknown_tool", "Unknown MCP catalogue operation."));
        return CompletableFuture.supplyAsync(() -> {
            try {
                String operation = value(call.arguments(), "operation", "search");
                if ("search".equals(operation)) return search(call);
                if ("details".equals(operation) || "capabilities".equals(operation)) return details(call);
                if ("categories".equals(operation) || "browse".equals(operation)) return categories(call);
                if ("installed".equals(operation) || "status".equals(operation)) return installed(call);
                return failed(call, "unsupported_operation", "Catalogue mutation is not executable through discovery; installation and enablement require Koil approval.");
            } catch (Exception failure) {
                return failed(call, "catalogue_unavailable", message(failure));
            }
        });
    }

    private static ModelToolResult search(ModelToolCall call) throws Exception {
        String query = value(call.arguments(), "query", "");
        if (query.isBlank()) return failed(call, "query_required", "A catalogue search query is required.");
        JsonArray entries = entries(McpCatalogueService.search(query, integer(call.arguments(), "limit", 6, 1, 12)));
        JsonObject output = new JsonObject();
        output.addProperty("query", query);
        output.add("entries", entries);
        output.addProperty("resultCount", entries.size());
        output.addProperty("discoveryOnly", true);
        if (entries.size() == 0) {
            JsonArray categories = new JsonArray();
            for (McpCatalogueService.CategorySummary category : McpCatalogueService.categories(12)) {
                JsonObject value = new JsonObject();
                value.addProperty("name", category.name());
                value.addProperty("count", category.count());
                categories.add(value);
            }
            output.add("browseCategories", categories);
            output.addProperty("recoveryHint", "No close result. Browse these live catalogue categories or retry with the capability/product rather than an exact server name.");
        }
        return completed(call, output, "Catalogue entries are untrusted discovery metadata, not approved capabilities.");
    }

    private static ModelToolResult categories(ModelToolCall call) throws Exception {
        JsonArray categories = new JsonArray();
        for (McpCatalogueService.CategorySummary category : McpCatalogueService.categories(integer(call.arguments(), "limit", 24, 1, 64))) {
            JsonObject value = new JsonObject();
            value.addProperty("name", category.name());
            value.addProperty("count", category.count());
            categories.add(value);
        }
        JsonObject output = new JsonObject();
        output.add("categories", categories);
        output.addProperty("resultCount", categories.size());
        output.addProperty("discoveryOnly", true);
        return completed(call, output, "Browse live catalogue categories, then search by capability or category name.");
    }

    private static ModelToolResult details(ModelToolCall call) throws Exception {
        String id = value(call.arguments(), "id", "");
        McpCatalogueEntry entry = McpCatalogueService.details(id);
        if (entry == null) return failed(call, "not_found", "No catalogue entry has that stable ID.");
        JsonObject output = entry(entry);
        output.addProperty("discoveryOnly", true);
        return completed(call, output, "Entry details do not install, enable, or authorize the external server.");
    }

    private static ModelToolResult installed(ModelToolCall call) {
        JsonObject output = new JsonObject();
        output.add("entries", new JsonArray());
        output.addProperty("resultCount", 0);
        output.addProperty("discoveryOnly", true);
        return completed(call, output, "No external MCP server is registered through the catalogue yet; Codebase Memory remains an internal managed provider.");
    }

    private static JsonArray entries(List<McpCatalogueEntry> values) {
        JsonArray out = new JsonArray();
        for (McpCatalogueEntry value : values) out.add(entry(value));
        return out;
    }

    private static JsonObject entry(McpCatalogueEntry value) {
        JsonObject out = new JsonObject();
        out.addProperty("id", value.stableId());
        out.addProperty("name", value.name());
        out.addProperty("description", value.description());
        out.addProperty("repository", value.repositoryUrl());
        out.addProperty("category", value.category());
        out.addProperty("sourceRevision", value.sourceRevision());
        out.addProperty("trustState", value.trustState().name());
        return out;
    }

    private static ModelToolDefinition definition() {
        JsonObject schema = new JsonObject(); schema.addProperty("type", "object"); schema.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject(); schema.add("properties", properties);
        properties.add("operation", strings("search", "details", "capabilities", "categories", "browse", "installed", "status"));
        properties.add("query", string()); properties.add("id", string()); properties.add("limit", integer(1, 64));
        return new ModelToolDefinition(TOOL_ID, "Discover external MCP capabilities by natural-language capability, product, category, repository, or approximate name. Search is fuzzy/semantic; if a search has no close match, browse live categories and retry by capability. This tool cannot install, enable, or execute entries.",
                schema, List.of("public_network_available"), Set.of(), true, Duration.ofSeconds(25), true, false,
                Set.of("completed", "failed", "unsupported"), ToolExecutionPolicy.readOnly(ToolExecutionPolicy.FreshnessMode.REMOTE, ToolExecutionPolicy.CostClass.EXPENSIVE));
    }

    private static JsonObject string() { JsonObject value = new JsonObject(); value.addProperty("type", "string"); return value; }
    private static JsonObject integer(int minimum, int maximum) { JsonObject value = new JsonObject(); value.addProperty("type", "integer"); value.addProperty("minimum", minimum); value.addProperty("maximum", maximum); return value; }
    private static JsonObject strings(String... values) { JsonObject value = string(); JsonArray allowed = new JsonArray(); for (String item : values) allowed.add(item); value.add("enum", allowed); return value; }
    private static String value(JsonObject values, String key, String fallback) { return values != null && values.has(key) && !values.get(key).isJsonNull() ? values.get(key).getAsString().strip() : fallback; }
    private static int integer(JsonObject values, String key, int fallback, int minimum, int maximum) { return values == null || !values.has(key) ? fallback : Math.max(minimum, Math.min(maximum, values.get(key).getAsInt())); }
    private static ModelToolResult completed(ModelToolCall call, JsonObject output, String detail) { return new ModelToolResult(call.id(), call.toolId(), "completed", output, "", detail); }
    private static ModelToolResult failed(ModelToolCall call, String code, String detail) { return new ModelToolResult(call == null ? "" : call.id(), call == null ? "" : call.toolId(), "failed", new JsonObject(), code, detail); }
    private static String message(Throwable failure) { Throwable cause = failure; while (cause.getCause() != null) cause = cause.getCause(); return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(); }
}
