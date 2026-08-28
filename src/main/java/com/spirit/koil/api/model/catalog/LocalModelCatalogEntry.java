package com.spirit.koil.api.model.catalog;

import java.util.List;

public record LocalModelCatalogEntry(
        String id,
        String displayName,
        String providerId,
        String runtimeId,
        String modelId,
        String parameterCount,
        String quantization,
        String license,
        int contextTokens,
        long estimatedMinimumMemoryBytes,
        long estimatedRecommendedMemoryBytes,
        int complexReasoningEstimatePercent,
        boolean toolCalling,
        List<LocalModelCapabilityTag> capabilityTags,
        String summary,
        List<ModelArtifact> artifacts,
        LocalModelCanonicalMetadata canonical
) {
    public LocalModelCatalogEntry(
            String id, String displayName, String providerId, String runtimeId, String modelId,
            String parameterCount, String quantization, String license, int contextTokens,
            long estimatedMinimumMemoryBytes, long estimatedRecommendedMemoryBytes,
            int complexReasoningEstimatePercent, boolean toolCalling,
            List<LocalModelCapabilityTag> capabilityTags, String summary, List<ModelArtifact> artifacts
    ) {
        this(id, displayName, providerId, runtimeId, modelId, parameterCount, quantization, license,
                contextTokens, estimatedMinimumMemoryBytes, estimatedRecommendedMemoryBytes,
                complexReasoningEstimatePercent, toolCalling, capabilityTags, summary, artifacts,
                LocalModelCanonicalMetadata.legacy(displayName, parameterCount, quantization, contextTokens,
                        artifacts != null && !artifacts.isEmpty()));
    }

    public LocalModelCatalogEntry {
        id = safe(id);
        displayName = safe(displayName);
        providerId = safe(providerId);
        runtimeId = safe(runtimeId);
        modelId = safe(modelId);
        parameterCount = safe(parameterCount);
        quantization = safe(quantization);
        license = safe(license);
        contextTokens = Math.max(0, contextTokens);
        estimatedMinimumMemoryBytes = Math.max(0L, estimatedMinimumMemoryBytes);
        estimatedRecommendedMemoryBytes = Math.max(estimatedMinimumMemoryBytes, estimatedRecommendedMemoryBytes);
        complexReasoningEstimatePercent = Math.max(0, Math.min(100, complexReasoningEstimatePercent));
        capabilityTags = capabilityTags == null ? List.of() : List.copyOf(capabilityTags);
        summary = summary == null ? "" : summary.strip();
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        canonical = canonical == null
                ? LocalModelCanonicalMetadata.legacy(displayName, parameterCount, quantization, contextTokens, !artifacts.isEmpty())
                : canonical;
        if (id.isEmpty() || displayName.isEmpty() || providerId.isEmpty() || runtimeId.isEmpty()
                || modelId.isEmpty() || canonical.runnable() && artifacts.isEmpty()) {
            throw new IllegalArgumentException("catalog entry is incomplete");
        }
    }

    public long downloadBytes() {
        long total = 0L;
        for (ModelArtifact artifact : this.artifacts) {
            total = Math.addExact(total, artifact.sizeBytes());
        }
        return total;
    }

    public String primaryFileName() {
        return this.artifacts.isEmpty() ? "" : this.artifacts.get(0).fileName();
    }

    public boolean runnable() {
        return this.canonical.runnable() && !this.artifacts.isEmpty();
    }

    public String family() {
        return this.canonical.family();
    }

    public String capabilityLabel() {
        return this.capabilityTags.stream()
                .map(LocalModelCapabilityTag::label)
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    public String complexReasoningLabel() {
        return complexReasoningEstimatePercent + "% complex-intent estimate";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
