package com.spirit.koil.api.context;

import java.util.Objects;

/** Preserves Koil reasoning when an optional context provider is unavailable or malformed. */
public final class FailOpenContextOptimizer implements ContextOptimizer {
    private final ContextOptimizer preferred;
    private final ContextOptimizer fallback;

    public FailOpenContextOptimizer(ContextOptimizer preferred, ContextOptimizer fallback) {
        this.preferred = Objects.requireNonNull(preferred, "preferred");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public ContextRepresentation optimize(ContextArtifact artifact, ContextOptimizationRequest request) {
        try {
            ContextRepresentation preferredResult = this.preferred.optimize(artifact, request);
            if (preferredResult == null) throw new IllegalStateException("Context optimizer returned no representation");
            return preferredResult;
        } catch (RuntimeException ignored) {
            ContextRepresentation fallbackResult = this.fallback.optimize(artifact, request);
            return new ContextRepresentation(
                    fallbackResult.artifactId(), fallbackResult.level(), fallbackResult.content(),
                    fallbackResult.originalCharacters(), fallbackResult.activeCharacters(),
                    "fail-open:" + fallbackResult.strategy()
            );
        }
    }
}
