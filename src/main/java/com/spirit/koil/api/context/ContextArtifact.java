package com.spirit.koil.api.context;

import java.time.Instant;

/** Immutable canonical context source. Model-facing representations never mutate this content. */
public record ContextArtifact(
        String id,
        String scopeId,
        String contentHash,
        String sourceType,
        String sourceId,
        String canonicalContent,
        ContextRepresentationLevel level,
        Instant createdAt,
        Instant expiresAt
) {
    public ContextArtifact {
        if (id == null || !id.startsWith("ctx-")) throw new IllegalArgumentException("context id");
        scopeId = scopeId == null || scopeId.isBlank() ? "local" : scopeId.strip();
        contentHash = contentHash == null ? "" : contentHash;
        sourceType = sourceType == null ? "unknown" : sourceType;
        sourceId = sourceId == null ? "unknown" : sourceId;
        canonicalContent = canonicalContent == null ? "" : canonicalContent;
        level = level == null ? ContextRepresentationLevel.L0_EXACT : level;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        expiresAt = expiresAt == null ? createdAt : expiresAt;
    }
}
