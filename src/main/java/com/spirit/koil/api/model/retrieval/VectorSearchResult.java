package com.spirit.koil.api.model.retrieval;

/** A dense result expressed only in Koil's stable external ID space. */
public record VectorSearchResult(long id, float score) {
    public VectorSearchResult {
        if (id <= 0L) throw new IllegalArgumentException("id must be positive");
        if (!Float.isFinite(score)) throw new IllegalArgumentException("score must be finite");
    }
}
