package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.knowledge.BundledKoilKnowledgeService;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Model-only read access to the curated Koil knowledge bundled in the jar. */
public final class KoilDocumentationModelToolRegistry {
    public static final String TOOL_ID = "koil.documentation";
    private static final String VERSION = "koil-bundled-knowledge-v1";
    private static final ModelToolDefinition DEFINITION = new ModelToolDefinition(
        TOOL_ID,
        "Search or read Koil's bundled self-documentation. Use this for Koil modes, tools, KTL, Executor, permissions, and exact file workflows. Start with search; read only the returned document/section. It is read-only and cannot access arbitrary jar or user files.",
        schema(),
        List.of("bundled_koil_knowledge_available"),
        Set.of(),
        true,
        Duration.ofSeconds(3),
        false,
        false,
        Set.of("completed", "failed", "not_found")
    );

    private KoilDocumentationModelToolRegistry() {
    }

    public static String version() {
        return VERSION;
    }

    public static List<ModelToolDefinition> modelTools() {
        return List.of(DEFINITION);
    }

    public static boolean supports(String toolId) {
        return TOOL_ID.equals(toolId);
    }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        if (call == null || !supports(call.toolId())) {
            return CompletableFuture.completedFuture(failure(call, "unknown_tool", "Unknown Koil documentation tool."));
        }
        try {
            String operation = string(call.arguments(), "operation", "search");
            BundledKoilKnowledgeService.KnowledgeResult result = switch (operation) {
                case "catalog" -> BundledKoilKnowledgeService.catalog();
                case "search" -> BundledKoilKnowledgeService.search(
                    string(call.arguments(), "query", ""),
                    integer(call.arguments(), "maxResults", 5)
                );
                case "read" -> BundledKoilKnowledgeService.read(
                    string(call.arguments(), "document", ""),
                    string(call.arguments(), "section", ""),
                    integer(call.arguments(), "startLine", 1),
                    integer(call.arguments(), "maxLines", 40)
                );
                default -> throw new IllegalArgumentException("operation must be catalog, search, or read.");
            };
            return CompletableFuture.completedFuture(new ModelToolResult(
                call.id(), call.toolId(), "completed", result.output(), "", result.detail()
            ));
        } catch (Exception failure) {
            String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            String code = detail.startsWith("Unknown bundled document") || detail.startsWith("Unknown section")
                ? "not_found" : "koil_documentation_failed";
            return CompletableFuture.completedFuture(failure(call, code, detail));
        }
    }

    private static JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject();
        properties.add("operation", enumeration("catalog", "search", "read"));
        properties.add("query", describedString("For search: the smallest exact Koil concept needed."));
        properties.add("document", describedString("For read: an exact document id returned by catalog/search."));
        properties.add("section", describedString("For read: optional exact section returned by catalog/search."));
        properties.add("startLine", integerSchema(1, Integer.MAX_VALUE));
        properties.add("maxLines", integerSchema(1, 120));
        properties.add("maxResults", integerSchema(1, 8));
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("operation");
        schema.add("required", required);
        return schema;
    }

    private static JsonObject enumeration(String... values) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        JsonArray choices = new JsonArray();
        for (String value : values) choices.add(value);
        schema.add("enum", choices);
        return schema;
    }

    private static JsonObject describedString(String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("description", description);
        schema.addProperty("maxLength", 512);
        return schema;
    }

    private static JsonObject integerSchema(int minimum, int maximum) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("minimum", minimum);
        schema.addProperty("maximum", maximum);
        return schema;
    }

    private static String string(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        return object.get(key).getAsString().strip();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        return object.get(key).getAsInt();
    }

    private static ModelToolResult failure(ModelToolCall call, String code, String detail) {
        return new ModelToolResult(
            call == null ? "" : call.id(),
            call == null ? "" : call.toolId(),
            "failed",
            new JsonObject(),
            code,
            detail
        );
    }
}
