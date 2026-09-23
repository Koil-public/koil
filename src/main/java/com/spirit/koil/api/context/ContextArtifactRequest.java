package com.spirit.koil.api.context;

/** Authorized canonical content submitted to the Koil context store. */
public record ContextArtifactRequest(String scopeId, String sourceType, String sourceId, String canonicalContent, long ttlSeconds) {
    public ContextArtifactRequest {
        scopeId = clean(scopeId, "local");
        sourceType = clean(sourceType, "unknown");
        sourceId = clean(sourceId, "unknown");
        canonicalContent = canonicalContent == null ? "" : canonicalContent;
        ttlSeconds = Math.max(1L, ttlSeconds);
    }

    public ContextArtifactRequest(String sourceType, String sourceId, String canonicalContent, long ttlSeconds) {
        this("local", sourceType, sourceId, canonicalContent, ttlSeconds);
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
