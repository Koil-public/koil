package com.spirit.koil.api.model.retrieval;

import java.util.LinkedHashSet;
import java.util.Set;

/** Dense-search limits and optional stable-ID allowlist. */
public record VectorSearchRequest(int limit, Set<Long> allowedIds, int minimumCandidates) {
    public VectorSearchRequest {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        if (minimumCandidates < 0) throw new IllegalArgumentException("minimumCandidates must not be negative");
        Set<Long> normalized = new LinkedHashSet<>();
        if (allowedIds != null) for (Long id : allowedIds) {
            if (id == null || id <= 0L) throw new IllegalArgumentException("allowed IDs must be positive");
            normalized.add(id);
        }
        allowedIds = Set.copyOf(normalized);
    }

    public boolean filtered() {
        return !this.allowedIds.isEmpty();
    }
}
