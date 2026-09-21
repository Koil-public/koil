package com.spirit.koil.api.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ToolExecutionPolicy;
import com.spirit.koil.api.model.retrieval.KoilKnowledgeRuntime;
import com.spirit.koil.api.util.file.KoilInstancePaths;

import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Koil-owned context operations; model-facing calls remain bounded and scope-bound. */
public final class ContextIntelligenceService {
    public static final String COMPRESS = "context.compress";
    public static final String RETRIEVE = "context.retrieve";
    public static final String EXPAND = "context.expand";
    public static final String INSPECT = "context.inspect";
    public static final String PIN = "context.pin";
    public static final String RELEASE = "context.release";
    public static final String SEARCH = "context.search";
    public static final String STATS = "context.stats";
    private static final List<ModelToolDefinition> TOOLS = List.of(
            definition(COMPRESS, "Register supplied evidence and return a loss-aware extractive representation that preserves exact source fragments, provenance, and a retrievable canonical reference.", true),
            definition(RETRIEVE, "Retrieve a bounded canonical body from a context reference owned by this request.", true),
            definition(EXPAND, "Return a less compressed bounded representation of a context reference owned by this request.", true),
            definition(INSPECT, "Inspect bounded metadata for a context reference owned by this request.", true),
            definition(PIN, "Keep a context reference readily available for the current request scope.", false),
            definition(RELEASE, "Release a current-scope context pin without deleting canonical content.", false),
            definition(SEARCH, "Search bounded context references owned by the current request without loading their bodies.", true),
            definition(STATS, "Inspect bounded current-request context storage and compression diagnostics.", true)
    );
    private static final ContextOptimizer OPTIMIZER = new FailOpenContextOptimizer(
            new EvidencePreservingContextOptimizer(), new DeterministicContextOptimizer());

    private ContextIntelligenceService() {
    }

    public static List<ModelToolDefinition> modelTools() {
        return TOOLS;
    }

    public static boolean supports(String toolId) {
        return TOOLS.stream().anyMatch(tool -> tool.id().equals(toolId));
    }

    /** Automatic assembly retains canonical source but never blocks a model request on optimization. */
    public static ContextRepresentation automaticProjection(
            String scopeId, String sourceType, String sourceId, String content, String objective, int targetCharacters
    ) {
        String canonical = content == null ? "" : content;
        int target = Math.max(1, Math.min(16_000, targetCharacters));
        if (canonical.isBlank()) return new ContextRepresentation("", ContextRepresentationLevel.L1_LOSSLESS, "", 0, 0, "empty");
        try {
            ContextArtifact artifact = register(new ContextArtifactRequest(scopeId,
                    text(sourceType), text(sourceId), canonical, 1_800L));
            ContextRepresentationLevel level = canonical.length() <= target
                    ? ContextRepresentationLevel.L1_LOSSLESS : ContextRepresentationLevel.L2_STRUCTURAL;
            return OPTIMIZER.optimize(artifact, new ContextOptimizationRequest(level, target, text(objective)));
        } catch (RuntimeException unavailable) {
            String projection = bounded(canonical, target);
            return new ContextRepresentation("", ContextRepresentationLevel.L1_LOSSLESS, projection,
                    canonical.length(), projection.length(), "fail-open:bounded-raw");
        }
    }

    public static CompletableFuture<ModelToolResult> execute(String scopeId, ModelToolCall call) {
        if (call == null || !supports(call.toolId())) return CompletableFuture.completedFuture(failed(call, "unknown_tool", "Unknown context operation."));
        return CompletableFuture.supplyAsync(() -> {
            try {
                return switch (call.toolId()) {
                    case COMPRESS -> compress(scopeId, call);
                    case RETRIEVE -> retrieve(scopeId, call);
                    case EXPAND -> expand(scopeId, call);
                    case INSPECT -> inspect(scopeId, call);
                    case PIN -> pin(scopeId, call, true);
                    case RELEASE -> pin(scopeId, call, false);
                    case SEARCH -> search(scopeId, call);
                    case STATS -> stats(scopeId, call);
                    default -> failed(call, "unknown_tool", "Unknown context operation.");
                };
            } catch (RuntimeException failure) {
                return failed(call, "context_operation_failed", message(failure));
            }
        });
    }

    private static ModelToolResult compress(String scope, ModelToolCall call) {
        String content = required(call.arguments(), "content");
        ContextArtifact artifact = register(new ContextArtifactRequest(scope,
                text(call.arguments(), "sourceType", "model-supplied"), text(call.arguments(), "sourceId", "inline"), content,
                integer(call.arguments(), "ttlSeconds", 1800, 60, 86_400)));
        ContextRepresentation representation = OPTIMIZER.optimize(artifact, request(call.arguments(), ContextRepresentationLevel.L3_SEMANTIC));
        JsonObject output = artifact(artifact);
        output.add("representation", representation(representation));
        return completed(call, output, "Canonical content is scope-bound and remains retrievable by its context reference.");
    }

    private static ModelToolResult retrieve(String scope, ModelToolCall call) {
        ContextArtifact artifact = artifact(scope, call);
        int limit = integer(call.arguments(), "maxCharacters", 4_000, 1, 16_000);
        String query = text(call.arguments(), "query", "");
        List<String> selected = query.isBlank() ? List.of() : KoilKnowledgeRuntime.contextArtifactsIfActive()
                .map(adapter -> adapter.select(artifact, query, limit).join()).orElse(List.of());
        JsonObject output = artifact(artifact);
        String content = selected.isEmpty() ? bounded(artifact.canonicalContent(), limit) : String.join("\n\n", selected);
        output.addProperty("content", content);
        output.addProperty("truncated", selected.isEmpty() && artifact.canonicalContent().length() > limit);
        output.addProperty("selective", !selected.isEmpty());
        return completed(call, output, selected.isEmpty()
                ? "Returned bounded canonical content from the current context scope."
                : "Returned bounded query-relevant context segments from the current context scope.");
    }

    private static ModelToolResult expand(String scope, ModelToolCall call) {
        ContextArtifact artifact = artifact(scope, call);
        JsonObject output = artifact(artifact);
        output.add("representation", representation(OPTIMIZER.optimize(artifact, request(call.arguments(), ContextRepresentationLevel.L2_STRUCTURAL))));
        return completed(call, output, "Returned a bounded deeper representation without changing canonical content.");
    }

    private static ModelToolResult inspect(String scope, ModelToolCall call) {
        return completed(call, artifact(artifact(scope, call)), "Context metadata is available; canonical content remains scope-bound.");
    }

    private static ModelToolResult pin(String scope, ModelToolCall call, boolean add) {
        String reference = required(call.arguments(), "ref");
        boolean changed = add ? store().pin(scope, reference) : store().release(scope, reference);
        if (!changed) return failed(call, "context_not_found", "No current-scope context reference is available for that operation.");
        JsonObject output = new JsonObject();
        output.addProperty("ref", reference);
        output.addProperty("pinned", add);
        return completed(call, output, add ? "The context reference is pinned." : "The context pin was released without deleting canonical content.");
    }

    private static ModelToolResult search(String scope, ModelToolCall call) {
        int limit = integer(call.arguments(), "limit", 4, 1, 8);
        String query = required(call.arguments(), "query");
        java.util.LinkedHashMap<String, ContextArtifact> artifacts = new java.util.LinkedHashMap<>();
        KoilKnowledgeRuntime.contextArtifactsIfActive().ifPresent(adapter -> {
            for (String reference : adapter.search(scope, query, limit).join()) {
                store().retrieve(scope, reference).ifPresent(value -> artifacts.put(value.id(), value));
            }
        });
        for (ContextArtifact artifact : store().search(scope, query, limit)) {
            if (artifacts.size() >= limit) break;
            artifacts.putIfAbsent(artifact.id(), artifact);
        }
        JsonArray results = new JsonArray();
        for (ContextArtifact artifact : artifacts.values()) results.add(artifact(artifact));
        JsonObject output = new JsonObject();
        output.add("results", results);
        output.addProperty("resultCount", artifacts.size());
        return completed(call, output, "Returned bounded current-scope context references without loading canonical bodies.");
    }

    private static ModelToolResult stats(String scope, ModelToolCall call) {
        ContextStoreStats stats = store().stats(scope);
        JsonObject output = new JsonObject();
        output.addProperty("artifactCount", stats.artifactCount());
        output.addProperty("pinnedArtifactCount", stats.pinnedArtifactCount());
        output.addProperty("canonicalCharacters", stats.canonicalCharacters());
        return completed(call, output, "Returned bounded diagnostics for the current context scope.");
    }

    private static ContextArtifact artifact(String scope, ModelToolCall call) {
        return store().retrieve(scope, required(call.arguments(), "ref"))
                .orElseThrow(() -> new IllegalArgumentException("No context reference exists in the current request scope."));
    }

    private static ContextOptimizationRequest request(JsonObject arguments, ContextRepresentationLevel fallback) {
        ContextRepresentationLevel level;
        try { level = ContextRepresentationLevel.valueOf(text(arguments, "level", fallback.name())); }
        catch (IllegalArgumentException ignored) { throw new IllegalArgumentException("level must be one of L0_EXACT through L5_EVICTED_RETRIEVABLE."); }
        return new ContextOptimizationRequest(level, integer(arguments, "targetCharacters", 2_000, 0, 16_000), text(arguments, "objective", ""));
    }

    private static ContextStore store() {
        return StoreHolder.INSTANCE;
    }

    private static ContextArtifact register(ContextArtifactRequest request) {
        ContextArtifact artifact = store().register(request);
        // Index only when knowledge is already active; this must not start an embedding runtime on the request path.
        KoilKnowledgeRuntime.contextArtifactsIfActive().ifPresent(adapter ->
                adapter.record(artifact).exceptionally(ignored -> null));
        return artifact;
    }

    private static JsonObject artifact(ContextArtifact artifact) {
        JsonObject output = new JsonObject();
        output.addProperty("ref", artifact.id());
        output.addProperty("sourceType", artifact.sourceType());
        output.addProperty("sourceId", artifact.sourceId());
        output.addProperty("contentHash", artifact.contentHash());
        output.addProperty("level", artifact.level().name());
        output.addProperty("expiresAt", artifact.expiresAt().toString());
        output.addProperty("canonicalCharacters", artifact.canonicalContent().length());
        return output;
    }

    private static JsonObject representation(ContextRepresentation value) {
        JsonObject output = new JsonObject();
        output.addProperty("level", value.level().name());
        output.addProperty("content", value.content());
        output.addProperty("originalCharacters", value.originalCharacters());
        output.addProperty("activeCharacters", value.activeCharacters());
        output.addProperty("strategy", value.strategy());
        return output;
    }

    private static ModelToolDefinition definition(String id, String description, boolean readOnly) {
        JsonObject schema = new JsonObject(); schema.addProperty("type", "object"); schema.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject(); schema.add("properties", properties);
        properties.add("ref", string()); properties.add("content", string()); properties.add("sourceType", string()); properties.add("sourceId", string());
        properties.add("level", level()); properties.add("targetCharacters", integer(0, 16_000)); properties.add("maxCharacters", integer(1, 16_000));
        properties.add("ttlSeconds", integer(60, 86_400)); properties.add("objective", string());
        properties.add("query", string()); properties.add("limit", integer(1, 8));
        JsonArray required = new JsonArray();
        if (COMPRESS.equals(id)) required.add("content");
        else if (SEARCH.equals(id)) required.add("query");
        else if (!STATS.equals(id)) required.add("ref");
        schema.add("required", required);
        return new ModelToolDefinition(id, description, schema, List.of("current_request_context_scope"), Set.of(), true,
                Duration.ofSeconds(5), false, false, Set.of("completed", "failed"),
                ToolExecutionPolicy.readOnly(ToolExecutionPolicy.FreshnessMode.SESSION, ToolExecutionPolicy.CostClass.CHEAP));
    }

    private static JsonObject string() { JsonObject value = new JsonObject(); value.addProperty("type", "string"); value.addProperty("maxLength", 16_000); return value; }
    private static JsonObject integer(int minimum, int maximum) { JsonObject value = new JsonObject(); value.addProperty("type", "integer"); value.addProperty("minimum", minimum); value.addProperty("maximum", maximum); return value; }
    private static JsonObject level() { JsonObject value = string(); JsonArray values = new JsonArray(); for (ContextRepresentationLevel level : ContextRepresentationLevel.values()) values.add(level.name()); value.add("enum", values); return value; }
    private static String required(JsonObject values, String key) { String value = text(values, key, ""); if (value.isBlank()) throw new IllegalArgumentException(key + " is required."); return value; }
    private static String text(JsonObject values, String key, String fallback) { return values != null && values.has(key) && !values.get(key).isJsonNull() ? values.get(key).getAsString().strip() : fallback; }
    private static String text(String value) { return value == null ? "" : value.strip(); }
    private static int integer(JsonObject values, String key, int fallback, int minimum, int maximum) { return values == null || !values.has(key) ? fallback : Math.max(minimum, Math.min(maximum, values.get(key).getAsInt())); }
    private static String bounded(String value, int maximum) { return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "…"; }
    private static ModelToolResult completed(ModelToolCall call, JsonObject output, String detail) { return new ModelToolResult(call.id(), call.toolId(), "completed", output, "", detail); }
    private static ModelToolResult failed(ModelToolCall call, String code, String detail) { return new ModelToolResult(call == null ? "" : call.id(), call == null ? "" : call.toolId(), "failed", new JsonObject(), code, detail); }
    private static String message(Throwable failure) { return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(); }

    private static final class StoreHolder {
        private static final ContextStore INSTANCE = new SqliteContextStore(storePath(), null, 512);
    }

    private static Path storePath() {
        try {
            return KoilInstancePaths.modelRoot().resolve("context/context-artifacts.db");
        } catch (IllegalStateException unavailableFabricRuntime) {
            // ponytail: standalone proofs lack FabricLoader; use the OS temp root only outside a live client.
            return Path.of(System.getProperty("java.io.tmpdir"), "koil-context", "context-artifacts.db");
        }
    }
}
