package com.spirit.koil.api.model.retrieval;

import java.util.List;

/** Retrieval evidence only; it deliberately contains no model reasoning or hidden prompt data. */
public record RetrievalProvenance(
        String requestId,
        long timestampMillis,
        String backend,
        boolean semanticAvailable,
        int exactCandidates,
        int semanticCandidates,
        List<Long> candidateIds,
        List<Long> selectedIds,
        List<Long> rejectedIds,
        int contextTokens
) {
    public RetrievalProvenance {
        requestId = requestId == null ? "" : requestId.strip();
        backend = backend == null || backend.isBlank() ? "unknown" : backend.strip();
        candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
        selectedIds = selectedIds == null ? List.of() : List.copyOf(selectedIds);
        rejectedIds = rejectedIds == null ? List.of() : List.copyOf(rejectedIds);
        if (timestampMillis < 0L || exactCandidates < 0 || semanticCandidates < 0 || contextTokens < 0) {
            throw new IllegalArgumentException("retrieval provenance counts must not be negative");
        }
    }
}
