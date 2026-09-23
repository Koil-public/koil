package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactFormat;
import com.spirit.koil.api.model.catalog.ModelArtifactInspection;
import com.spirit.koil.api.model.catalog.ModelTensorDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-light structural proofs for graph lowering, binding, and diagnostics. */
public final class KoilNormalizedGraphProof {
    private KoilNormalizedGraphProof() {}

    public static void main(String[] args) {
        proveDenseGqa();
        proveMoe();
        proveHybrid();
        proveEncoderDecoder();
        proveMissingTensor();
        proveShapeMismatch();
        System.out.println("Koil normalized graph proof passed");
    }

    private static void proveDenseGqa() {
        ModelArtifactInspection inspection = inspection("llama", 0, Map.of(
                "llama.block_count", "1", "llama.embedding_length", "8", "llama.attention.head_count_kv", "2"
        ), List.of(
                tensor("token_embd.weight", 16, 8), tensor("blk.0.attn_q.weight", 8, 8),
                tensor("blk.0.attn_k.weight", 8, 8), tensor("blk.0.ffn_up.weight", 8, 16), tensor("output.weight", 16, 8)
        ));
        KoilModelGraph graph = build(inspection);
        require(graph.valid(), "dense GQA graph must validate");
        require(graph.requiredOperators().contains(KoilOperatorKind.GROUPED_QUERY_ATTENTION), "GQA operator required");
        require(graph.nodes().stream().anyMatch(n -> n.layerIndex() == 0), "dense layer node exists");
    }

    private static void proveMoe() {
        ModelArtifactInspection inspection = inspection("mixtral", 8, Map.of("mixtral.block_count", "1"), List.of(
                tensor("token_embd.weight", 16, 8), tensor("blk.0.attn_q.weight", 8, 8),
                tensor("blk.0.ffn_gate_exps.weight", 8, 8), tensor("output.weight", 16, 8)
        ));
        KoilModelGraph graph = build(inspection);
        require(graph.valid(), "MoE graph must validate");
        require(graph.requiredOperators().contains(KoilOperatorKind.MOE_ROUTING), "MoE routing required");
        require(graph.nodes().stream().filter(n -> n.layerIndex() == 0)
                .anyMatch(n -> n.operators().contains(KoilOperatorKind.EXPERT_EXECUTION)), "MoE layer marks expert execution");
    }

    private static void proveHybrid() {
        ModelArtifactInspection inspection = inspection("lfm2", 0, Map.of("lfm2.block_count", "1", "lfm2.conv_kernel", "3"), List.of(
                tensor("model.embed_tokens.weight", 16, 8), tensor("model.layers.0.conv.weight", 8, 3),
                tensor("model.layers.0.self_attn.q_proj.weight", 8, 8), tensor("lm_head.weight", 16, 8)
        ));
        KoilModelGraph graph = build(inspection);
        require(graph.valid(), "hybrid graph must validate");
        require(graph.requiredOperators().contains(KoilOperatorKind.SHORT_CONVOLUTION), "hybrid convolution required");
        require(graph.persistentState().contains(KoilModelStateKind.CONVOLUTION), "hybrid convolution state retained");
    }

    private static void proveEncoderDecoder() {
        ModelArtifactInspection inspection = inspection("T5ForConditionalGeneration", 0,
                Map.of("config.num_layers", "1", "config.num_decoder_layers", "1"), List.of(
                        tensor("shared.weight", 16, 8), tensor("encoder.block.0.layer.0.weight", 8, 8),
                        tensor("decoder.block.0.layer.0.weight", 8, 8), tensor("lm_head.weight", 16, 8)
                ));
        KoilModelGraph graph = build(inspection);
        require(graph.valid(), "encoder-decoder graph must validate");
        require(graph.category() == KoilArchitectureCategory.ENCODER_DECODER_TRANSFORMER, "encoder-decoder category");
        require(graph.nodes().stream().anyMatch(n -> n.stage() == KoilGraphStage.ENCODER), "encoder stage exists");
        require(graph.nodes().stream().anyMatch(n -> n.stage() == KoilGraphStage.DECODER), "decoder stage exists");
    }

    private static void proveMissingTensor() {
        ModelArtifactInspection inspection = inspection("llama", 0, Map.of("llama.block_count", "1"), List.of(
                tensor("blk.0.attn_q.weight", 8, 8), tensor("output.weight", 16, 8)
        ));
        KoilModelGraph graph = build(inspection);
        require(!graph.valid(), "missing embedding must fail validation");
        require(graph.diagnostics().stream().anyMatch(d -> d.code().equals("embedding_tensor_missing")), "missing embedding diagnostic");
    }

    private static void proveShapeMismatch() {
        ModelArtifactInspection inspection = inspection("llama", 0,
                Map.of("llama.block_count", "1", "llama.embedding_length", "8"), List.of(
                        tensor("token_embd.weight", 16, 6), tensor("blk.0.attn_q.weight", 8, 8), tensor("output.weight", 16, 8)
                ));
        KoilModelGraph graph = build(inspection);
        require(!graph.valid(), "shape mismatch must fail validation");
        require(graph.diagnostics().stream().anyMatch(d -> d.code().equals("embedding_dimension_mismatch")), "shape mismatch diagnostic");
    }

    private static KoilModelGraph build(ModelArtifactInspection inspection) {
        KoilArchitectureDescriptor architecture = KoilArchitectureRegistry.defaultRegistry().identify(inspection);
        require(architecture.recognized(), "architecture recognized: " + inspection.architectureId());
        return KoilArchitectureGraphRegistry.defaultRegistry().build(architecture, inspection);
    }

    private static ModelArtifactInspection inspection(String architecture, int experts, Map<String, String> metadata,
                                                      List<ModelTensorDescriptor> tensors) {
        return new ModelArtifactInspection(true, ModelArtifactFormat.SAFETENSORS_DIRECTORY, 1, tensors.size(),
                architecture, "proof", "proof", "proof", "", 4096, experts, experts > 0 ? 2 : 0,
                Set.of(), tensors, metadata, "graph proof");
    }

    private static ModelTensorDescriptor tensor(String name, long... shape) {
        List<Long> dims = java.util.Arrays.stream(shape).boxed().toList();
        return new ModelTensorDescriptor(name, dims, "F16", 0, 0, "proof.safetensors");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("proof failed: " + message);
    }
}
