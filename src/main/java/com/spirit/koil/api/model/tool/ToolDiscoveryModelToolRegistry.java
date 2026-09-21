package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ToolExecutionPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Small control-plane tools that let a model discover Koil capabilities without
 * preloading every tool schema into provider context.
 *
 * <p>Discovery never grants execution authority. Returned tools remain subject
 * to their original schema, confirmation, preflight, side-effect, and executor
 * policies when Koil exposes them on a later provider round.</p>
 */
public final class ToolDiscoveryModelToolRegistry {
    public static final String SEARCH = "tool.search";
    public static final String INSPECT = "tool.inspect";

    private static final List<ModelToolDefinition> TOOLS = List.of(
            new ModelToolDefinition(
                    SEARCH,
                    "Search every tool currently registered with Koil. Use this when the initially supplied tools may not cover the objective, when a better capability may exist, or before concluding that Koil cannot perform an action.",
                    searchSchema(),
                    List.of(), Set.of(), true, Duration.ofSeconds(3), false, false,
                    Set.of("completed", "failed"),
                    ToolExecutionPolicy.readOnly(ToolExecutionPolicy.FreshnessMode.WORKSPACE, ToolExecutionPolicy.CostClass.CHEAP)
            ),
            new ModelToolDefinition(
                    INSPECT,
                    "Inspect one registered Koil tool and return its authoritative description, input schema, confirmation requirement, side effects, and execution policy metadata. Inspection does not execute the tool.",
                    inspectSchema(),
                    List.of(), Set.of(), true, Duration.ofSeconds(3), false, false,
                    Set.of("completed", "failed"),
                    ToolExecutionPolicy.readOnly(ToolExecutionPolicy.FreshnessMode.WORKSPACE, ToolExecutionPolicy.CostClass.CHEAP)
            )
    );

    private ToolDiscoveryModelToolRegistry() {
    }

    public static String version() {
        return "tool-discovery-v1";
    }

    public static List<ModelToolDefinition> modelTools() {
        return TOOLS;
    }

    public static boolean supports(String toolId) {
        return SEARCH.equals(toolId) || INSPECT.equals(toolId);
    }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        try {
            return CompletableFuture.completedFuture(switch (call.toolId()) {
                case SEARCH -> search(call);
                case INSPECT -> inspect(call);
                default -> failure(call, "unknown_tool_discovery_tool", "Unknown tool-discovery capability: " + call.toolId());
            });
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(failure(call, "tool_discovery_failed", message(exception)));
        }
    }

    private static ModelToolResult search(ModelToolCall call) {
        String query = string(call.arguments(), "query");
        int limit = integer(call.arguments(), "limit", 8, 1, 24);
        boolean includeSideEffects = bool(call.arguments(), "includeSideEffects", true);
        boolean includeConfirmation = bool(call.arguments(), "includeConfirmationRequired", true);
        Set<String> queryTokens = tokens(query);

        List<ScoredTool> scored = new ArrayList<>();
        for (ModelToolDefinition tool : LocalModelToolCatalog.allRegisteredTools()) {
            if (supports(tool.id())) continue;
            if (!includeSideEffects && !tool.sideEffects().isEmpty()) continue;
            if (!includeConfirmation && tool.confirmationRequired()) continue;
            int score = score(tool, query, queryTokens);
            if (score > 0 || query.isBlank()) scored.add(new ScoredTool(tool, score));
        }
        scored.sort(Comparator.comparingInt(ScoredTool::score).reversed()
                .thenComparing(candidate -> candidate.tool().id()));

        JsonArray tools = new JsonArray();
        scored.stream().limit(limit).forEach(candidate -> tools.add(summary(candidate.tool(), false)));
        JsonObject output = new JsonObject();
        output.addProperty("query", query);
        output.addProperty("registeredCount", LocalModelToolCatalog.allRegisteredTools().size());
        output.addProperty("resultCount", tools.size());
        output.add("tools", tools);
        return completed(call, output,
                "The complete Koil tool registry was searched. Matching tool schemas may be exposed on the next provider round.");
    }

    private static ModelToolResult inspect(ModelToolCall call) {
        String id = string(call.arguments(), "tool");
        ModelToolDefinition tool = LocalModelToolCatalog.definition(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown registered tool: " + id));
        return completed(call, summary(tool, true),
                "Registered tool inspected. Inspection does not execute or authorize the tool.");
    }

    private static int score(ModelToolDefinition tool, String rawQuery, Set<String> queryTokens) {
        if (rawQuery == null || rawQuery.isBlank()) return 1;
        String id = tool.id().toLowerCase(Locale.ROOT);
        String description = tool.description().toLowerCase(Locale.ROOT);
        String query = rawQuery.toLowerCase(Locale.ROOT).strip();
        int score = 0;
        if (id.equals(query)) score += 100;
        if (id.contains(query)) score += 40;
        if (description.contains(query)) score += 25;
        for (String token : queryTokens) {
            if (id.equals(token) || id.endsWith("." + token)) score += 12;
            else if (id.contains(token)) score += 7;
            if (description.contains(token)) score += 3;
        }
        return score;
    }

    private static Set<String> tokens(String value) {
        LinkedHashSet<String> output = new LinkedHashSet<>();
        if (value == null) return output;
        for (String token : value.toLowerCase(Locale.ROOT).split("[^a-z0-9_.:-]+")) {
            if (!token.isBlank()) output.add(token);
        }
        return output;
    }

    private static JsonObject summary(ModelToolDefinition tool, boolean includeSchema) {
        JsonObject output = new JsonObject();
        output.addProperty("id", tool.id());
        output.addProperty("description", tool.description());
        output.addProperty("confirmationRequired", tool.confirmationRequired());
        output.addProperty("readOnly", tool.sideEffects().isEmpty());
        JsonArray effects = new JsonArray();
        tool.sideEffects().stream().sorted().forEach(effects::add);
        output.add("sideEffects", effects);
        output.addProperty("speculativeReadAllowed", tool.executionPolicy().allowsSpeculativeRead());
        output.addProperty("preparationAllowed", tool.executionPolicy().allowsPreparation());
        if (includeSchema) output.add("inputSchema", tool.inputSchema().deepCopy());
        return output;
    }

    private static JsonObject searchSchema() {
        JsonObject schema = objectSchema();
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.add("query", stringSchema(0, 512));
        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("minimum", 1);
        limit.addProperty("maximum", 24);
        properties.add("limit", limit);
        properties.add("includeSideEffects", booleanSchema());
        properties.add("includeConfirmationRequired", booleanSchema());
        require(schema, "query");
        return schema;
    }

    private static JsonObject inspectSchema() {
        JsonObject schema = objectSchema();
        schema.getAsJsonObject("properties").add("tool", stringSchema(1, 256));
        require(schema, "tool");
        return schema;
    }

    private static JsonObject objectSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        schema.add("properties", new JsonObject());
        return schema;
    }

    private static JsonObject stringSchema(int minimum, int maximum) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("minLength", minimum);
        schema.addProperty("maxLength", maximum);
        return schema;
    }

    private static JsonObject booleanSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "boolean");
        return schema;
    }

    private static void require(JsonObject schema, String... keys) {
        JsonArray required = new JsonArray();
        for (String key : keys) required.add(key);
        schema.add("required", required);
    }

    private static String string(JsonObject object, String key) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsString().strip() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static int integer(JsonObject object, String key, int fallback, int minimum, int maximum) {
        try {
            int value = object != null && object.has(key) ? object.get(key).getAsInt() : fallback;
            return Math.max(minimum, Math.min(maximum, value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static ModelToolResult completed(ModelToolCall call, JsonObject output, String detail) {
        return new ModelToolResult(call.id(), call.toolId(), "completed", output, "", detail);
    }

    private static ModelToolResult failure(ModelToolCall call, String code, String detail) {
        return new ModelToolResult(call == null ? "" : call.id(), call == null ? "" : call.toolId(),
                "failed", new JsonObject(), code, detail == null ? "" : detail);
    }

    private static String message(Throwable failure) {
        return failure == null || failure.getMessage() == null ? "unknown tool discovery failure" : failure.getMessage();
    }

    private record ScoredTool(ModelToolDefinition tool, int score) {
    }
}
