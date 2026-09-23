package com.spirit.koil.api.context;

import java.util.Optional;
import java.util.List;

/** Koil-owned canonical artifact authority; storage implementation is replaceable without changing model execution. */
public interface ContextStore {
    ContextArtifact register(ContextArtifactRequest request);

    Optional<ContextArtifact> retrieve(String scopeId, String artifactId);

    boolean pin(String scopeId, String artifactId);

    boolean release(String scopeId, String artifactId);

    List<ContextArtifact> search(String scopeId, String query, int limit);

    ContextStoreStats stats(String scopeId);

    default Optional<ContextArtifact> retrieve(String artifactId) {
        return retrieve("local", artifactId);
    }

    default boolean pin(String artifactId) {
        return pin("local", artifactId);
    }

    default boolean release(String artifactId) {
        return release("local", artifactId);
    }
}
