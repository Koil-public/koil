package com.spirit.koil.api.model.retrieval;

/** Identifies one mathematically compatible embedding space. */
public record EmbeddingIdentity(String providerId, String modelId, String revision, int dimensions, boolean normalized) {
    public EmbeddingIdentity {
        providerId = required(providerId, "providerId");
        modelId = required(modelId, "modelId");
        revision = required(revision, "revision");
        if (dimensions <= 0 || dimensions % 8 != 0) {
            throw new IllegalArgumentException("dimensions must be a positive multiple of 8");
        }
    }

    public String cacheKey() {
        return this.providerId + ':' + this.modelId + ':' + this.revision + ':' + this.dimensions + ':' + this.normalized;
    }

    private static String required(String value, String name) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
