package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ToolExecutionPolicy;
import com.spirit.koil.api.model.codeintelligence.CodeIntelligenceOperation;
import com.spirit.koil.api.model.codeintelligence.CodeIntelligenceProvenance;
import com.spirit.koil.api.model.codeintelligence.CodeIntelligenceRequest;
import com.spirit.koil.api.model.codeintelligence.CodeIntelligenceResult;
import com.spirit.koil.api.model.codeintelligence.CodeIntelligenceService;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Stable read-only model tools. Provider-specific MCP names and schemas remain internal. */
public final class CodeIntelligenceModelToolRegistry {
    private static final Map<String, CodeIntelligenceOperation> OPERATIONS = operations();
    private static final List<ModelToolDefinition> TOOLS = OPERATIONS.entrySet().stream().map(entry -> definition(entry.getKey())).toList();
    private CodeIntelligenceModelToolRegistry() { }

    public static String version() { return "code-intelligence-tools-v1"; }
    public static List<ModelToolDefinition> modelTools() { return TOOLS; }
    public static boolean supports(String id) { return id != null && OPERATIONS.containsKey(id); }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        return execute("", call);
    }

    public static CompletableFuture<ModelToolResult> execute(java.util.UUID sessionId, ModelToolCall call) {
        return execute(sessionId == null ? "" : sessionId.toString(), call);
    }

    private static CompletableFuture<ModelToolResult> execute(String sessionId, ModelToolCall call) {
        long startedAt = System.currentTimeMillis();
        if (call == null || !supports(call.toolId())) return recorded(sessionId, call, null, startedAt,
            result(call, "unsupported", "unknown_code_tool", "Unknown code-intelligence tool.", new JsonObject(), false));
        try {
            JsonObject arguments = call.arguments();
            Map<String, ModelWorkspaceRegistry.Workspace> available = ModelWorkspaceRegistry.workspaces();
            String workspace = ModelWorkspaceRegistry.preferredCodeWorkspaceId(string(arguments, "workspace"), available);
            ModelWorkspaceRegistry.Workspace selected = available.get(workspace);
            if (selected == null) return recorded(sessionId, call, null, startedAt,
                result(call, "unsupported", "workspace_unavailable", "Choose one named Koil workspace; arbitrary paths are not accepted.", new JsonObject(), false));
            String query = string(arguments, "query");
            if (requiresQuery(OPERATIONS.get(call.toolId())) && query.isBlank()) return recorded(sessionId, call, selected.root(), startedAt,
                result(call, "failed", "missing_query", "This code-intelligence operation requires a query.", new JsonObject(), false));
            int limit = bounded(arguments, "limit", 20, 1, 50);
            int depth = bounded(arguments, "depth", 3, 1, 5);
            return CodeIntelligenceService.instance().query(new CodeIntelligenceRequest(
                OPERATIONS.get(call.toolId()), selected.root(), query, limit, depth, 24_000
            )).handle((value, failure) -> {
                ModelToolResult resolved = failure == null ? adapt(call, value) : result(call, "failed", "code_intelligence_failed", message(failure), new JsonObject(), true);
                recordProvenance(sessionId, call, selected.root(), startedAt, resolved);
                return resolved;
            });
        } catch (RuntimeException failure) {
            return recorded(sessionId, call, null, startedAt,
                result(call, "failed", "invalid_code_intelligence_arguments", message(failure), new JsonObject(), false));
        }
    }

    private static ModelToolDefinition definition(String id) {
        CodeIntelligenceOperation operation = OPERATIONS.get(id);
        boolean query = requiresQuery(operation);
        Map<String, JsonObject> fields = new LinkedHashMap<>();
        fields.put("workspace", stringSchema(32));
        if (query) fields.put("query", stringSchema(8_000));
        fields.put("limit", integerSchema(1, 50));
        if (operation == CodeIntelligenceOperation.TRACE || operation == CodeIntelligenceOperation.IMPACT || operation == CodeIntelligenceOperation.CHANGES) fields.put("depth", integerSchema(1, 5));
        ToolExecutionPolicy.CostClass cost = switch (operation) {
            case ARCHITECTURE, SYMBOLS, CHANGES, SCHEMA -> ToolExecutionPolicy.CostClass.MODERATE;
            case SEARCH, SEMANTIC_SEARCH, TRACE, IMPACT, SNIPPET, QUERY -> ToolExecutionPolicy.CostClass.EXPENSIVE;
        };
        return new ModelToolDefinition(id, description(operation), objectSchema(fields, query ? List.of("workspace", "query") : List.of("workspace")),
            List.of("named_workspace_available"), Set.of(), false, Duration.ofMinutes(12), false, false,
            Set.of("completed", "failed", "unavailable", "unsupported"),
            ToolExecutionPolicy.readOnly(ToolExecutionPolicy.FreshnessMode.WORKSPACE, cost));
    }

    private static ModelToolResult adapt(ModelToolCall call, CodeIntelligenceResult source) {
        JsonObject output = source == null ? new JsonObject() : source.data().deepCopy();
        output.addProperty("provider", source == null ? "" : source.providerId());
        output.addProperty("truncated", source != null && source.truncated());
        output.addProperty("currentSourceOutranksHistoricalMemory", true);
        return result(call, source == null ? "failed" : source.status(), source == null ? "code_intelligence_failed" : source.failureCode(),
            source == null ? "Code intelligence returned no result." : source.detail(), output,
            source == null || !"completed".equals(source.status()));
    }

    private static void recordProvenance(String sessionId, ModelToolCall call, Path workspace, long startedAt, ModelToolResult result) {
        JsonObject output = result == null ? new JsonObject() : result.output();
        JsonObject arguments = call == null ? new JsonObject() : call.arguments();
        CodeIntelligenceProvenance.record(new CodeIntelligenceProvenance.Entry(
            call == null ? "" : call.id(), sessionId, workspace == null ? "" : workspace.toString(), call == null ? "" : call.toolId(), string(output, "externalTool"),
            "workspace=" + string(arguments, "workspace") + ", queryLength=" + string(arguments, "query").length(),
            startedAt, System.currentTimeMillis(), result == null ? "failed" : result.status(), integer(output, "resultCount"),
            output.has("truncated") && output.get("truncated").getAsBoolean(), CodeIntelligenceService.instance().health().state().name(),
            string(output, "provider"), string(output, "providerVersion")
        ));
    }

    private static CompletableFuture<ModelToolResult> recorded(String sessionId, ModelToolCall call, Path workspace, long startedAt, ModelToolResult result) {
        recordProvenance(sessionId, call, workspace, startedAt, result);
        return CompletableFuture.completedFuture(result);
    }

    private static Map<String, CodeIntelligenceOperation> operations() {
        Map<String, CodeIntelligenceOperation> values = new LinkedHashMap<>();
        values.put("code.architecture", CodeIntelligenceOperation.ARCHITECTURE);
        values.put("code.symbols", CodeIntelligenceOperation.SYMBOLS);
        values.put("code.search", CodeIntelligenceOperation.SEARCH);
        values.put("code.semantic_search", CodeIntelligenceOperation.SEMANTIC_SEARCH);
        values.put("code.trace", CodeIntelligenceOperation.TRACE);
        values.put("code.impact", CodeIntelligenceOperation.IMPACT);
        values.put("code.changes", CodeIntelligenceOperation.CHANGES);
        values.put("code.snippet", CodeIntelligenceOperation.SNIPPET);
        values.put("code.schema", CodeIntelligenceOperation.SCHEMA);
        values.put("code.query", CodeIntelligenceOperation.QUERY);
        return Map.copyOf(values);
    }

    private static boolean requiresQuery(CodeIntelligenceOperation operation) { return operation != CodeIntelligenceOperation.ARCHITECTURE && operation != CodeIntelligenceOperation.CHANGES && operation != CodeIntelligenceOperation.SCHEMA; }
    private static String description(CodeIntelligenceOperation operation) { return switch (operation) {
        case ARCHITECTURE -> "Inspect project architecture, packages, entry points, hotspots, boundaries, and clusters before broad file exploration.";
        case SYMBOLS -> "Find structural symbols such as classes, methods, interfaces, implementations, and packages.";
        case SEARCH -> "Find exact code text or graph-ranked code matches.";
        case SEMANTIC_SEARCH -> "Find source code by intent when exact symbol names are unknown.";
        case TRACE -> "Trace bounded callers, callees, and dependencies of a symbol.";
        case IMPACT -> "Analyze the git-diff blast radius and affected symbols before or after a change.";
        case CHANGES -> "Map current workspace changes to affected code symbols and validation risk.";
        case SNIPPET -> "Retrieve one bounded, relevant symbol-level source snippet.";
        case SCHEMA -> "Inspect the read-only code graph schema and coverage summary.";
        case QUERY -> "Run a bounded read-only structural graph query when simpler code tools are insufficient.";
    }; }
    private static ModelToolResult result(ModelToolCall call, String status, String code, String detail, JsonObject output, boolean retryable) { return new ModelToolResult(call == null ? "" : call.id(), call == null ? "" : call.toolId(), status, output, code == null ? "" : code, detail == null ? "" : detail, System.currentTimeMillis(), System.currentTimeMillis(), "completed".equals(status) ? "passed" : "not_required", List.of(), retryable, false, "not_required"); }
    private static String string(JsonObject object, String key) { try { return object.get(key).getAsString().strip(); } catch (Exception ignored) { return ""; } }
    private static int integer(JsonObject object, String key) { try { return Math.max(0, object.get(key).getAsInt()); } catch (Exception ignored) { return 0; } }
    private static int bounded(JsonObject object, String key, int fallback, int minimum, int maximum) { try { return Math.max(minimum, Math.min(maximum, object.get(key).getAsInt())); } catch (Exception ignored) { return fallback; } }
    private static String message(Throwable failure) { Throwable cause = failure.getCause() == null ? failure : failure.getCause(); return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(); }
    private static JsonObject objectSchema(Map<String, JsonObject> properties, List<String> required) { JsonObject schema = new JsonObject(); schema.addProperty("type", "object"); schema.addProperty("additionalProperties", false); JsonObject fields = new JsonObject(); properties.forEach(fields::add); schema.add("properties", fields); JsonArray values = new JsonArray(); required.forEach(values::add); schema.add("required", values); return schema; }
    private static JsonObject stringSchema(int maximum) { JsonObject schema = new JsonObject(); schema.addProperty("type", "string"); schema.addProperty("maxLength", maximum); return schema; }
    private static JsonObject integerSchema(int minimum, int maximum) { JsonObject schema = new JsonObject(); schema.addProperty("type", "integer"); schema.addProperty("minimum", minimum); schema.addProperty("maximum", maximum); return schema; }
}
