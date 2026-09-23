package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactFormat;

import java.util.Map;
import java.util.Set;

/** Runtime-neutral profile consumed later by hardware planning and execution adapters. */
public record KoilModelProfile(
        boolean inspectable,
        ModelArtifactFormat sourceFormat,
        String modelName,
        String architectureId,
        KoilArchitectureDescriptor architecture,
        String tokenizerFamily,
        int contextTokens,
        long tensorCount,
        int expertCount,
        int activeExpertCount,
        Set<String> declaredCapabilities,
        Map<String, String> metadata,
        String evidence
) {
    public KoilModelProfile {
        sourceFormat = sourceFormat == null ? ModelArtifactFormat.UNKNOWN : sourceFormat;
        modelName = safe(modelName);
        architectureId = safe(architectureId);
        tokenizerFamily = safe(tokenizerFamily);
        contextTokens = Math.max(0, contextTokens);
        tensorCount = Math.max(0L, tensorCount);
        expertCount = Math.max(0, expertCount);
        activeExpertCount = Math.max(0, activeExpertCount);
        declaredCapabilities = declaredCapabilities == null ? Set.of() : Set.copyOf(declaredCapabilities);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        evidence = safe(evidence);
    }

    public boolean architectureRecognized() {
        return architecture != null && architecture.recognized();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
