package com.spirit.koil.api.model.catalog;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable facts recovered directly from an installed model artifact.
 *
 * <p>This is deliberately runtime-neutral. Catalog metadata describes what Koil
 * expects, while artifact inspection records what the selected file actually
 * declares. Runtime providers may then negotiate those facts with their own
 * observed capabilities.</p>
 */
public record ModelArtifactInspection(
        boolean present,
        ModelArtifactFormat format,
        int formatVersion,
        long tensorCount,
        String architectureId,
        String modelName,
        String modelType,
        String tokenizerFamily,
        String chatTemplate,
        int contextTokens,
        int expertCount,
        int activeExpertCount,
        Set<String> declaredCapabilities,
        List<ModelTensorDescriptor> tensors,
        Map<String, String> metadata,
        String evidence
) {
    public ModelArtifactInspection {
        format = format == null ? ModelArtifactFormat.UNKNOWN : format;
        formatVersion = Math.max(0, formatVersion);
        tensorCount = Math.max(0L, tensorCount);
        architectureId = safe(architectureId);
        modelName = safe(modelName);
        modelType = safe(modelType);
        tokenizerFamily = safe(tokenizerFamily);
        chatTemplate = chatTemplate == null ? "" : chatTemplate;
        contextTokens = Math.max(0, contextTokens);
        expertCount = Math.max(0, expertCount);
        activeExpertCount = Math.max(0, activeExpertCount);
        declaredCapabilities = declaredCapabilities == null ? Set.of() : Set.copyOf(declaredCapabilities);
        tensors = tensors == null ? List.of() : List.copyOf(tensors);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        evidence = safe(evidence);
    }

    public static ModelArtifactInspection unavailable(ModelArtifactFormat format, String evidence) {
        return new ModelArtifactInspection(
                false, format, 0, 0L, "", "", "", "", "", 0, 0, 0,
                Set.of(), List.of(), Map.of(), evidence
        );
    }

    public boolean mixtureOfExperts() {
        return expertCount > 0 || activeExpertCount > 0;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
