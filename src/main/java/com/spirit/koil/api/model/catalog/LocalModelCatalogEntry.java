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
        LocalModelCanonicalMetadata canonical,
        List<ModelRuntimeCompatibility> runtimeCompatibility
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
                        artifacts != null && !artifacts.isEmpty()), null);
    }

    /** Source-compatible constructor for catalog producers created before runtime metadata. */
    public LocalModelCatalogEntry(
            String id, String displayName, String providerId, String runtimeId, String modelId,
            String parameterCount, String quantization, String license, int contextTokens,
            long estimatedMinimumMemoryBytes, long estimatedRecommendedMemoryBytes,
            int complexReasoningEstimatePercent, boolean toolCalling,
            List<LocalModelCapabilityTag> capabilityTags, String summary, List<ModelArtifact> artifacts,
            LocalModelCanonicalMetadata canonical
    ) {
        this(id, displayName, providerId, runtimeId, modelId, parameterCount, quantization, license,
                contextTokens, estimatedMinimumMemoryBytes, estimatedRecommendedMemoryBytes,
                complexReasoningEstimatePercent, toolCalling, capabilityTags, summary, artifacts,
                canonical, null);
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
        runtimeCompatibility = runtimeCompatibility == null || runtimeCompatibility.isEmpty()
                ? defaultRuntimeCompatibility(providerId, runtimeId, canonical, toolCalling, contextTokens)
                : List.copyOf(runtimeCompatibility);
        if (id.isEmpty() || displayName.isEmpty() || providerId.isEmpty() || runtimeId.isEmpty()
                || modelId.isEmpty() || canonical.runnable() && artifacts.isEmpty()
                && runtimeCompatibility.stream().noneMatch(ModelRuntimeCompatibility::installsRepositorySnapshot)) {
            throw new IllegalArgumentException("catalog entry is incomplete");
        }
    }

    private static List<ModelRuntimeCompatibility> defaultRuntimeCompatibility(
            String providerId,
            String runtimeId,
            LocalModelCanonicalMetadata canonical,
            boolean toolCalling,
            int contextTokens
    ) {
        if ("llama_cpp".equals(providerId)) {
            if (canonical.architecture() == LocalModelCanonicalMetadata.Architecture.EMBEDDING) {
                return List.of(ModelRuntimeCompatibility.llamaCppEmbedding(runtimeId, canonical, contextTokens));
            }
            return List.of(ModelRuntimeCompatibility.llamaCpp(runtimeId, canonical, toolCalling, contextTokens));
        }
        return List.of();
    }

    /** Returns a copy with the exact runtime compatibility list replaced (additive catalog metadata). */
    public LocalModelCatalogEntry withRuntimeCompatibility(List<ModelRuntimeCompatibility> compatibility) {
        boolean snapshotRuntime = compatibility != null && compatibility.stream()
                .anyMatch(ModelRuntimeCompatibility::installsRepositorySnapshot);
        long minimum = estimatedMinimumMemoryBytes;
        long recommended = estimatedRecommendedMemoryBytes;
        if (snapshotRuntime && minimum == 0L && canonical.totalParametersBillions() > 0.0D) {
            // Colibri containers remain fully stored even when only a subset of
            // experts is active. A conservative half-byte/parameter floor keeps
            // frontier models catalogued without falsely recommending them to
            // consumer hardware. `coli plan` supplies the authoritative split.
            double floor = canonical.totalParametersBillions() * 500_000_000.0D;
            minimum = floor >= Long.MAX_VALUE ? Long.MAX_VALUE : (long)Math.ceil(floor);
            recommended = minimum >= Long.MAX_VALUE / 5L * 4L
                    ? Long.MAX_VALUE
                    : Math.max(minimum, minimum + minimum / 4L);
        }
        return new LocalModelCatalogEntry(
                id, displayName, providerId, runtimeId, modelId, parameterCount, quantization, license,
                contextTokens, minimum, recommended,
                complexReasoningEstimatePercent, toolCalling, capabilityTags, summary, artifacts,
                canonical, compatibility
        );
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
        return !this.artifacts.isEmpty()
                || this.runtimeCompatibility.stream().anyMatch(ModelRuntimeCompatibility::installsRepositorySnapshot);
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
