package com.spirit.koil.api.model.retrieval;

import java.util.Objects;

/** Fused candidate and its explainable score components. */
public record RetrievalCandidate(
        KnowledgeEntry entry,
        float semanticScore,
        float exactScore,
        double fusedScore,
        int estimatedTokens
) {
    public RetrievalCandidate {
        entry = Objects.requireNonNull(entry, "entry");
        if (!Float.isFinite(semanticScore) || !Float.isFinite(exactScore) || !Double.isFinite(fusedScore)) {
            throw new IllegalArgumentException("candidate scores must be finite");
        }
        if (estimatedTokens < 0) throw new IllegalArgumentException("estimatedTokens must not be negative");
    }
}
