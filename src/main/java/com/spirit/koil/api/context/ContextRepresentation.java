package com.spirit.koil.api.context;

/** A bounded model-facing projection of a stable canonical artifact. */
public record ContextRepresentation(
        String artifactId,
        ContextRepresentationLevel level,
        String content,
        int originalCharacters,
        int activeCharacters,
        String strategy
) {
    public ContextRepresentation {
        artifactId = artifactId == null ? "" : artifactId;
        level = level == null ? ContextRepresentationLevel.L0_EXACT : level;
        content = content == null ? "" : content;
        originalCharacters = Math.max(0, originalCharacters);
        activeCharacters = Math.max(0, activeCharacters);
        strategy = strategy == null ? "" : strategy;
    }
}
