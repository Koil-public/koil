package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.catalog.LocalModelCanonicalMetadata;
import com.spirit.koil.api.model.catalog.LocalModelCatalog;
import com.spirit.koil.api.model.catalog.LocalModelCatalogEntry;
import com.spirit.koil.api.model.catalog.LocalModelToolCapabilityResolver;
import com.spirit.koil.api.model.catalog.ModelArtifactInspection;
import com.spirit.koil.api.model.catalog.ModelArtifactInspector;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Effective chat/runtime protocol negotiated from catalog metadata and the
 * actual GGUF artifact. The artifact wins for architecture/template details.
 */
record LlamaCppProtocolProfile(
        String family,
        String architectureId,
        ComputeTopology computeTopology,
        int expertCount,
        int activeExpertCount,
        String tokenizerFamily,
        String chatTemplateAdapter,
        String reasoningAdapter,
        String toolAdapter,
        SystemPolicy systemPolicy,
        RolePolicy rolePolicy,
        StopPolicy stopPolicy,
        boolean toolCalling,
        boolean reasoning,
        int artifactContextTokens,
        boolean artifactInspected,
        String evidence
) {
    LlamaCppProtocolProfile {
        family = safe(family);
        architectureId = safe(architectureId);
        computeTopology = computeTopology == null ? ComputeTopology.UNKNOWN : computeTopology;
        tokenizerFamily = safe(tokenizerFamily);
        chatTemplateAdapter = safe(chatTemplateAdapter);
        reasoningAdapter = safe(reasoningAdapter);
        toolAdapter = safe(toolAdapter);
        systemPolicy = systemPolicy == null ? SystemPolicy.NATIVE : systemPolicy;
        rolePolicy = rolePolicy == null ? RolePolicy.NATIVE : rolePolicy;
        stopPolicy = stopPolicy == null ? StopPolicy.TEMPLATE_OWNED : stopPolicy;
        expertCount = Math.max(0, expertCount);
        activeExpertCount = Math.max(0, activeExpertCount);
        artifactContextTokens = Math.max(0, artifactContextTokens);
        evidence = safe(evidence);
    }

    static LlamaCppProtocolProfile resolve(String modelId, Path modelFile) {
        LocalModelCatalogEntry entry = LocalModelCatalog.entries().stream()
                .filter(candidate -> candidate.modelId().equals(modelId) || candidate.id().equals(modelId))
                .findFirst()
                .orElseGet(() -> LocalModelCatalog.entries().stream()
                        .filter(candidate -> modelFile != null && modelFile.getFileName() != null
                                && candidate.artifacts().stream().anyMatch(artifact -> artifact.fileName().equals(modelFile.getFileName().toString())))
                        .findFirst().orElse(null));
        LocalModelCanonicalMetadata canonical = entry == null ? null : entry.canonical();
        ModelArtifactInspection artifact = ModelArtifactInspector.inspect(modelFile);

        String family = canonical == null ? "" : canonical.family();
        String architecture = artifact.architectureId();
        if (architecture.isBlank() && canonical != null) architecture = normalize(canonical.family());
        if (family.isBlank()) family = inferFamily(architecture, modelId, modelFile);

        int experts = artifact.expertCount();
        int activeExperts = artifact.activeExpertCount();
        ComputeTopology topology;
        boolean artifactHybrid = artifact.declaredCapabilities().contains("hybrid_sequence");
        boolean artifactMoe = experts > 0 || activeExperts > 0;
        if (artifactHybrid && artifactMoe) {
            topology = ComputeTopology.HYBRID_MOE;
        } else if (artifactMoe || (canonical != null && canonical.architecture() == LocalModelCanonicalMetadata.Architecture.MOE)) {
            topology = ComputeTopology.MOE;
        } else if (artifactHybrid || (canonical != null && canonical.architecture() == LocalModelCanonicalMetadata.Architecture.HYBRID)) {
            topology = ComputeTopology.HYBRID;
        } else if (canonical != null && canonical.architecture() == LocalModelCanonicalMetadata.Architecture.DENSE) {
            topology = ComputeTopology.DENSE;
        } else {
            topology = ComputeTopology.UNKNOWN;
        }

        String templateAdapter = canonical == null ? "" : canonical.chatTemplateAdapter();
        String reasoningAdapter = canonical == null ? "" : canonical.reasoningParserAdapter();
        String toolAdapter = canonical == null ? "" : canonical.toolParserAdapter();
        String template = artifact.chatTemplate();
        String tokenizer = artifact.tokenizerFamily();
        if (tokenizer.isBlank() && canonical != null) tokenizer = normalize(canonical.family()) + "/gguf_embedded";

        String lowerFamily = family.toLowerCase(Locale.ROOT);
        String lowerTemplate = template.toLowerCase(Locale.ROOT);
        boolean gemmaLike = lowerFamily.contains("gemma") || lowerTemplate.contains("<start_of_turn>");
        boolean mistralLike = lowerFamily.contains("mistral") || lowerFamily.contains("mixtral")
                || lowerTemplate.contains("[inst]") || lowerTemplate.contains("[/inst]");
        boolean templateDemandsAlternation = lowerTemplate.contains("conversation roles must alternate")
                || lowerTemplate.contains("roles must alternate")
                || lowerTemplate.contains("alternating user/assistant")
                || lowerTemplate.contains("alternating user and assistant");
        SystemPolicy systemPolicy = gemmaLike ? SystemPolicy.CONTROL_TURN_BRIDGE : SystemPolicy.NATIVE;
        RolePolicy rolePolicy = gemmaLike || mistralLike || templateDemandsAlternation
                ? RolePolicy.STRICT_ALTERNATING
                : RolePolicy.NATIVE;
        boolean toolCalling = entry != null && entry.toolCalling();
        if (!toolAdapter.isBlank() && !"unknown".equalsIgnoreCase(toolAdapter)) toolCalling = true;
        if (LocalModelToolCapabilityResolver.templateDeclaresTools(template)) {
            toolCalling = true;
        }
        boolean reasoning = canonical != null && ("Thinking".equalsIgnoreCase(canonical.modelType())
                || "Reasoning".equalsIgnoreCase(canonical.modelType())
                || !canonical.reasoningParserAdapter().isBlank());
        boolean templateReasoning = lowerTemplate.contains("enable_thinking")
                || lowerTemplate.contains("reasoning_content")
                || lowerTemplate.contains("supports_thinking")
                || lowerTemplate.contains("supports_reasoning")
                || lowerTemplate.contains("<think>")
                || lowerTemplate.contains("[think]")
                || lowerTemplate.contains(":think>")
                || lowerTemplate.contains("<reasoning>")
                || lowerTemplate.contains("<analysis>")
                || lowerTemplate.contains("reasoning_summary")
                || lowerTemplate.contains("thinking_start_tag")
                || lowerTemplate.contains("thinking_end_tag");
        if (templateReasoning
                || lowerFamily.contains("deepseek")
                || lowerFamily.contains("qwen3")
                || lowerFamily.contains("gpt-oss")) {
            reasoning = true;
        }

        String evidence = artifact.present()
                ? "catalog+artifact metadata; artifact architecture/template/tokenizer take precedence"
                : canonical != null ? "catalog metadata; GGUF metadata unavailable" : "generic llama.cpp OpenAI protocol";
        return new LlamaCppProtocolProfile(
                family,
                architecture,
                topology,
                experts,
                activeExperts,
                tokenizer,
                templateAdapter,
                reasoningAdapter,
                toolAdapter,
                systemPolicy,
                rolePolicy,
                StopPolicy.TEMPLATE_OWNED,
                toolCalling,
                reasoning,
                artifact.contextTokens(),
                artifact.present(),
                evidence
        );
    }

    static LlamaCppProtocolProfile generic() {
        return new LlamaCppProtocolProfile(
                "unknown", "", ComputeTopology.UNKNOWN, 0, 0, "", "", "", "",
                SystemPolicy.NATIVE, RolePolicy.NATIVE, StopPolicy.TEMPLATE_OWNED,
                false, false, 0, false, "generic llama.cpp OpenAI protocol"
        );
    }

    boolean permitsTools() {
        return toolCalling;
    }

    private static String inferFamily(String architecture, String modelId, Path modelFile) {
        String combined = safe(architecture) + " " + safe(modelId) + " "
                + (modelFile == null ? "" : modelFile.getFileName().toString());
        String lower = combined.toLowerCase(Locale.ROOT);
        if (lower.contains("qwen")) return "Qwen";
        if (lower.contains("deepseek")) return "DeepSeek";
        if (lower.contains("gemma")) return "Gemma";
        if (lower.contains("gpt-oss")) return "GPT-OSS";
        if (lower.contains("glm")) return "GLM";
        if (lower.contains("kimi") || lower.contains("moonshot")) return "Kimi";
        if (lower.contains("mistral") || lower.contains("mixtral")) return "Mistral";
        if (lower.contains("llama")) return "Llama";
        if (lower.contains("granite")) return "Granite";
        if (lower.contains("phi")) return "Phi";
        return architecture.isBlank() ? "unknown" : architecture;
    }

    private static String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    enum ComputeTopology { DENSE, MOE, HYBRID, HYBRID_MOE, UNKNOWN }
    enum SystemPolicy { NATIVE, CONTROL_TURN_BRIDGE }
    enum RolePolicy { NATIVE, STRICT_ALTERNATING }
    enum StopPolicy { TEMPLATE_OWNED }
}
