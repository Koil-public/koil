package com.spirit.koil.api.context;

/** Requested density and bounded active representation budget for one artifact. */
public record ContextOptimizationRequest(ContextRepresentationLevel requestedLevel, int targetCharacters, String objective) {
    public ContextOptimizationRequest {
        requestedLevel = requestedLevel == null ? ContextRepresentationLevel.L0_EXACT : requestedLevel;
        targetCharacters = Math.max(0, targetCharacters);
        objective = objective == null ? "" : objective.strip();
    }
}
