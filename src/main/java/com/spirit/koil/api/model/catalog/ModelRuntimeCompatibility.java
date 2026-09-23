package com.spirit.koil.api.model.catalog;

import com.spirit.koil.api.model.ModelCapabilityDescriptor;

import java.util.List;
import java.util.Set;

/**
 * One evidence-backed way an exact catalog variant can execute.
 *
 * <p>This describes compatibility rather than installation state. A model may
 * have several implementations of the same canonical variant; the runtime
 * resolver chooses one without exposing backend/quantization noise in the
 * normal selector.</p>
 */
public record ModelRuntimeCompatibility(
        String runtimeId,
        String providerId,
        String architectureId,
        String engineId,
        ModelArtifactFormat artifactFormat,
        String tokenizerFamily,
        int preference,
        boolean managedRuntime,
        boolean pretokenizedPromptInput,
        boolean streaming,
        boolean cancellation,
        boolean toolCalling,
        boolean reasoning,
        boolean vision,
        boolean audio,
        int maximumContextTokens,
        Set<String> protocolCapabilities,
        List<String> supportedPlatforms,
        String modelRepository,
        String modelRevision,
        String evidence
) {
    public ModelRuntimeCompatibility {
        runtimeId = safe(runtimeId);
        providerId = safe(providerId);
        architectureId = safe(architectureId);
        engineId = safe(engineId);
        artifactFormat = artifactFormat == null ? ModelArtifactFormat.UNKNOWN : artifactFormat;
        tokenizerFamily = safe(tokenizerFamily);
        preference = Math.max(0, preference);
        maximumContextTokens = Math.max(0, maximumContextTokens);
        protocolCapabilities = protocolCapabilities == null ? Set.of() : Set.copyOf(protocolCapabilities);
        supportedPlatforms = supportedPlatforms == null
                ? List.of()
                : supportedPlatforms.stream().map(ModelRuntimeCompatibility::safe)
                        .filter(value -> !value.isBlank()).distinct().sorted().toList();
        modelRepository = safe(modelRepository);
        modelRevision = safe(modelRevision);
        if (modelRepository.isBlank() != modelRevision.isBlank()) {
            throw new IllegalArgumentException("model repository and revision must be supplied together");
        }
        evidence = safe(evidence);
        if (runtimeId.isBlank() || providerId.isBlank() || architectureId.isBlank()) {
            throw new IllegalArgumentException("runtime compatibility identity is incomplete");
        }
    }

    public boolean supportsCurrentPlatform() {
        return supportedPlatforms.isEmpty()
                || supportedPlatforms.contains(LocalModelRuntimePlatform.currentId());
    }

    public ModelCapabilityDescriptor capabilities() {
        return new ModelCapabilityDescriptor(
                streaming,
                toolCalling,
                true,
                cancellation,
                true,
                maximumContextTokens,
                protocolCapabilities
        );
    }

    public static ModelRuntimeCompatibility llamaCpp(
            LocalModelCanonicalMetadata canonical,
            boolean toolCalling,
            int contextTokens
    ) {
        return llamaCpp(ModelRuntimeIds.LLAMA_CPP, canonical, toolCalling, contextTokens);
    }

    /** Dedicated llama-server endpoint role; never a chat/tool-generation runtime. */
    public static ModelRuntimeCompatibility llamaCppEmbedding(
            String runtimeId,
            LocalModelCanonicalMetadata canonical,
            int contextTokens
    ) {
        String architecture = canonical == null ? "gguf/embedding" : "gguf/" + normalize(canonical.family());
        String tokenizer = canonical == null ? "gguf_embedded" : normalize(canonical.family()) + "/gguf_embedded";
        return new ModelRuntimeCompatibility(
                runtimeId, "llama_cpp", architecture, "llama-server-embedding", ModelArtifactFormat.GGUF_FILE,
                tokenizer, 100, true, false, false, true, false, false, false, false,
                contextTokens, Set.of("openai_embeddings"),
                List.of("linux-x86_64", "linux-arm64", "macos-x86_64", "macos-arm64", "windows-x86_64"),
                "", "", "Pinned Koil llama.cpp embedding endpoint and GGUF artifact metadata."
        );
    }

    public static ModelRuntimeCompatibility llamaCpp(
            String runtimeId,
            LocalModelCanonicalMetadata canonical,
            boolean toolCalling,
            int contextTokens
    ) {
        String architecture = canonical == null
                ? "gguf/unknown"
                : "gguf/" + normalize(canonical.family());
        String tokenizer = canonical == null
                ? "gguf_embedded"
                : normalize(canonical.family()) + "/gguf_embedded";
        return new ModelRuntimeCompatibility(
                runtimeId,
                "llama_cpp",
                architecture,
                "llama-server",
                ModelArtifactFormat.GGUF_FILE,
                tokenizer,
                100,
                true,
                false,
                true,
                true,
                toolCalling,
                canonical != null && ("Thinking".equals(canonical.modelType())
                        || "Reasoning".equals(canonical.modelType())),
                canonical != null && canonical.modalities().contains("vision"),
                canonical != null && canonical.modalities().contains("audio"),
                contextTokens,
                toolCalling ? Set.of("openai_chat", "llama_cpp_native_tools") : Set.of("openai_chat"),
                List.of("linux-x86_64", "linux-arm64", "macos-x86_64", "macos-arm64", "windows-x86_64"),
                "",
                "",
                "Pinned Koil llama.cpp runtime and GGUF artifact metadata."
        );
    }

    /**
     * Exact Colibri engine compatibility for one canonical model family.
     *
     * Values below are transcribed from the pinned Colibri v1.10.1
     * {@code family_registry.py} / {@code docs/api.md} at commit
     * 12a5c464b5c1f8292d578c62458706bc32d6ac95. Tool support that the registry
     * and the API document disagree on (Qwen3.8) is conservatively reported as
     * unsupported until a real checkpoint fixture proves it.
     */
    public static ModelRuntimeCompatibility colibri(
            String engineId,
            String architectureId,
            boolean toolCalling,
            boolean reasoning,
            boolean vision,
            boolean audio,
            int maximumContextTokens,
            java.util.Set<String> protocolCapabilities,
            String modelRepository,
            String modelRevision,
            String evidence
    ) {
        return new ModelRuntimeCompatibility(
                ModelRuntimeIds.COLIBRI,
                "colibri",
                architectureId,
                engineId,
                ModelArtifactFormat.COLIBRI_CONTAINER,
                "colibri/" + engineId,
                200,
                true,
                "glm".equals(engineId),
                true,
                true,
                toolCalling,
                reasoning,
                vision,
                audio,
                maximumContextTokens,
                protocolCapabilities,
                java.util.List.of("linux-x86_64", "linux-arm64", "macos-x86_64", "macos-arm64", "windows-x86_64"),
                modelRepository,
                modelRevision,
                evidence
        );
    }

    public boolean installsRepositorySnapshot() {
        return !modelRepository.isBlank() && !modelRevision.isBlank();
    }

    private static String normalize(String value) {
        return safe(value).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
