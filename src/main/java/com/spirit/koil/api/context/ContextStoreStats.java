package com.spirit.koil.api.context;

/** Bounded, scope-local store diagnostics that never expose another request's artifacts. */
public record ContextStoreStats(long artifactCount, long pinnedArtifactCount, long canonicalCharacters) {
}
