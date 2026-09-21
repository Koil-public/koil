package com.spirit.koil.api.model.skill;

import java.util.List;
import java.util.Set;

/**
 * Bounded Koil-owned procedure metadata. A Skill can describe procedures and bundled
 * resources, but never grants tool authority by itself.
 */
public record KoilSkillDefinition(
        String id,
        String version,
        String description,
        String semanticSummary,
        List<String> exactTerms,
        Set<KoilSkillMode> modes,
        int maximumSideEffectClass,
        String compactGuidance,
        Trust trust,
        String source,
        String root,
        List<String> declaredTools
) {
    public KoilSkillDefinition {
        id = required(id, "id");
        version = required(version, "version");
        description = required(description, "description");
        semanticSummary = required(semanticSummary, "semanticSummary");
        exactTerms = exactTerms == null ? List.of() : exactTerms.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip).map(String::toLowerCase).distinct().toList();
        modes = modes == null || modes.isEmpty() ? Set.of() : Set.copyOf(modes);
        if (maximumSideEffectClass < 0) {
            throw new IllegalArgumentException("maximumSideEffectClass must not be negative");
        }
        compactGuidance = required(compactGuidance, "compactGuidance");
        trust = trust == null ? Trust.KOIL_BUILT_IN : trust;
        source = clean(source, trust == Trust.KOIL_BUILT_IN ? "koil" : "user");
        root = root == null ? "" : root.strip();
        declaredTools = declaredTools == null ? List.of() : declaredTools.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip).distinct().toList();
    }

    /** Compatibility constructor for Koil-owned Skills that have no external package metadata. */
    public KoilSkillDefinition(
            String id,
            String version,
            String description,
            String semanticSummary,
            List<String> exactTerms,
            Set<KoilSkillMode> modes,
            int maximumSideEffectClass,
            String compactGuidance,
            Trust trust
    ) {
        this(id, version, description, semanticSummary, exactTerms, modes, maximumSideEffectClass,
                compactGuidance, trust, "koil", "", List.of());
    }

    public boolean external() {
        return trust != Trust.KOIL_BUILT_IN;
    }

    private static String required(String value, String name) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }

    private static String clean(String value, String fallback) {
        String normalized = value == null ? "" : value.strip();
        return normalized.isBlank() ? fallback : normalized;
    }

    public enum Trust { KOIL_BUILT_IN, USER_AUTHORED, REVIEWED_EXTERNAL, UNTRUSTED_EXTERNAL }
}
