package com.spirit.koil.api.context;

import java.util.LinkedHashSet;

/** Cheap no-network baseline for exact, structural, compact, and retrievable context projections. */
public final class DeterministicContextOptimizer implements ContextOptimizer {
    @Override
    public ContextRepresentation optimize(ContextArtifact artifact, ContextOptimizationRequest request) {
        if (artifact == null || request == null) throw new IllegalArgumentException("artifact and request");
        String canonical = artifact.canonicalContent();
        String content;
        String strategy;
        switch (request.requestedLevel()) {
            case L0_EXACT, L1_LOSSLESS -> {
                content = canonical;
                strategy = request.requestedLevel() == ContextRepresentationLevel.L0_EXACT ? "exact" : "lossless-pass-through";
            }
            case L2_STRUCTURAL -> {
                content = distinctLines(canonical, request.targetCharacters());
                strategy = "distinct-line-structural";
            }
            case L3_SEMANTIC -> {
                content = bounded(distinctLines(canonical, request.targetCharacters()), request.targetCharacters());
                strategy = "deterministic-structural-fallback";
            }
            case L4_AGGRESSIVE -> {
                content = "[" + artifact.id() + " | source=" + artifact.sourceType() + " | chars=" + canonical.length() + "]";
                strategy = "aggressive-reference";
            }
            case L5_EVICTED_RETRIEVABLE -> {
                content = "[" + artifact.id() + "]";
                strategy = "evicted-retrievable";
            }
            default -> throw new IllegalStateException("Unhandled level");
        }
        return new ContextRepresentation(artifact.id(), request.requestedLevel(), content, canonical.length(), content.length(), strategy);
    }

    private static String distinctLines(String content, int maximum) {
        if (content.isEmpty()) return content;
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        for (String line : content.split("\\R")) if (!line.isBlank()) lines.add(line.strip());
        return bounded(String.join("\n", lines), maximum);
    }

    private static String bounded(String content, int maximum) {
        return maximum <= 0 || content.length() <= maximum ? content : content.substring(0, Math.max(0, maximum - 1)).stripTrailing() + "…";
    }
}
