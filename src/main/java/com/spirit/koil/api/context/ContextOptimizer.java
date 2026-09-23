package com.spirit.koil.api.context;

/** Provider-neutral representation boundary; callers retain the last valid representation on optimizer failure. */
public interface ContextOptimizer {
    ContextRepresentation optimize(ContextArtifact artifact, ContextOptimizationRequest request);
}
