package com.spirit.koil.api.model.retrieval;

import com.spirit.koil.api.model.ModelToolDefinition;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Selects already-registered tool schemas from the shared hybrid knowledge
 * engine. It is deliberately only a relevance filter: registry membership,
 * availability, and approval policy remain authoritative elsewhere.
 */
public final class SemanticToolCapabilityRetriever {
    private static final int CANDIDATE_LIMIT = 8;
    private static final int CONTEXT_TOKEN_BUDGET = 192;

    private SemanticToolCapabilityRetriever() {
    }

    public static CompletableFuture<List<ModelToolDefinition>> retrieve(
            KoilRetrievalEngine engine,
            String objective,
            Collection<ModelToolDefinition> allowedTools,
            String requestId
    ) {
        Objects.requireNonNull(engine, "engine");
        Map<String, ModelToolDefinition> allowed = new LinkedHashMap<>();
        if (allowedTools != null) for (ModelToolDefinition tool : allowedTools) {
            if (tool != null) allowed.putIfAbsent(tool.id(), tool);
        }
        if (allowed.isEmpty() || objective == null || objective.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }
        KnowledgeFilter filter = new KnowledgeFilter(
                java.util.Set.of(KnowledgeType.TOOL),
                java.util.Set.of(),
                java.util.Set.of(),
                Map.of("sourceId", "koil.tools"),
                0L
        );
        return engine.retrieve(new KnowledgeQuery(
                        objective,
                        filter,
                        Math.min(CANDIDATE_LIMIT, allowed.size()),
                        CONTEXT_TOKEN_BUDGET,
                        requestId,
                        KnowledgeTrust.HISTORICAL_CONTEXT
                ))
                .thenApply(result -> result.selected().stream()
                        .map(candidate -> candidate.entry().metadata().get("toolId"))
                        .map(allowed::get)
                        .filter(Objects::nonNull)
                        .distinct()
                        .limit(CANDIDATE_LIMIT)
                        .toList())
                .exceptionally(ignored -> List.of());
    }
}
