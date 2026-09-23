package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactFormat;
import com.spirit.koil.api.model.catalog.ModelArtifactInspection;

import java.util.Map;
import java.util.Set;

/** Deterministic proof that architecture discovery is metadata-driven and state is generalized beyond KV. */
public final class KoilArchitectureRegistryProof {
    private KoilArchitectureRegistryProof() {
    }

    public static void main(String[] args) {
        ModelArtifactInspection qwenMoe = inspection("qwen3moe", Map.of(
                "qwen3moe.attention.head_count", "32",
                "qwen3moe.attention.head_count_kv", "8",
                "qwen3moe.rope.freq_base", "1000000"
        ), 128, 8);
        KoilArchitectureDescriptor transformer = KoilArchitectureRegistry.defaultRegistry().identify(qwenMoe);
        require(transformer.category() == KoilArchitectureCategory.MIXTURE_OF_EXPERTS, "MoE transformer category was not identified");
        require(transformer.persistentState().contains(KoilModelStateKind.ATTENTION_KV), "attention state was omitted");
        require(transformer.persistentState().contains(KoilModelStateKind.EXPERT_ROUTING), "expert-routing state was omitted");
        require(transformer.requiredOperators().contains(KoilOperatorKind.GROUPED_QUERY_ATTENTION), "GQA requirement was omitted");

        ModelArtifactInspection mamba = inspection("mamba", Map.of(
                "mamba.ssm.state_size", "16",
                "mamba.conv_kernel", "4"
        ), 0, 0);
        KoilArchitectureDescriptor stateSpace = KoilArchitectureRegistry.defaultRegistry().identify(mamba);
        require(stateSpace.category() == KoilArchitectureCategory.STATE_SPACE, "state-space architecture was not identified");
        require(stateSpace.persistentState().contains(KoilModelStateKind.SSM), "SSM state was omitted");
        require(stateSpace.persistentState().contains(KoilModelStateKind.CONVOLUTION), "convolution state was omitted");
        require(!stateSpace.persistentState().contains(KoilModelStateKind.ATTENTION_KV), "state-space model was incorrectly forced into KV state");

        ModelArtifactInspection unknown = inspection("future_state_mixer_v3", Map.of(), 0, 0);
        KoilArchitectureDescriptor unresolved = KoilArchitectureRegistry.defaultRegistry().identify(unknown);
        require(!unresolved.recognized(), "unknown architecture was falsely reported as recognized");
        System.out.println("Koil architecture registry proof passed");
    }

    private static ModelArtifactInspection inspection(String architecture, Map<String, String> metadata, int experts, int activeExperts) {
        return new ModelArtifactInspection(true, ModelArtifactFormat.GGUF_FILE, 3, 100,
                architecture, "proof", "model", "proof-tokenizer", "", 32768,
                experts, activeExperts, Set.of(), java.util.List.of(), metadata, "proof metadata");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
