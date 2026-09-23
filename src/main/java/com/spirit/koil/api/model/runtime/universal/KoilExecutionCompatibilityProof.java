package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactFormat;
import com.spirit.koil.api.model.catalog.ModelArtifactInspection;
import com.spirit.koil.api.model.catalog.ModelTensorDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-light proofs for tensor roles and adapter/backend executability analysis. */
public final class KoilExecutionCompatibilityProof {
    private KoilExecutionCompatibilityProof() {}

    public static void main(String[] args) {
        proveTensorRoles();
        proveExecutableLlamaCppVulkanGraph();
        proveUnknownOperatorBlocksExecution();
        proveUnsupportedBackendBlocksExecution();
        System.out.println("Koil execution compatibility proof passed");
    }

    private static void proveTensorRoles() {
        ModelArtifactInspection inspection = denseInspection();
        KoilExecutionRepresentation representation = KoilModelImporter.build(inspection);
        require(representation.tensorResolution().valid(), "tensor role resolution must validate");
        require(hasRole(representation, KoilTensorRole.TOKEN_EMBEDDING), "embedding role resolved");
        require(hasRole(representation, KoilTensorRole.ATTENTION_QUERY), "query role resolved");
        require(hasRole(representation, KoilTensorRole.ATTENTION_KEY), "key role resolved");
        require(hasRole(representation, KoilTensorRole.ATTENTION_VALUE), "value role resolved");
        require(hasRole(representation, KoilTensorRole.ATTENTION_OUTPUT), "attention output role resolved");
        require(hasRole(representation, KoilTensorRole.OUTPUT_PROJECTION), "output role resolved");
    }

    private static void proveExecutableLlamaCppVulkanGraph() {
        KoilExecutionRepresentation representation = KoilModelImporter.build(denseInspection());
        KoilExecutionAdapterDescriptor adapter = new KoilExecutionAdapterDescriptor(
                "llama_cpp", "openai_tool_calls", true, Map.of("proof", "r42"));
        KoilExecutionCompatibility compatibility = KoilExecutionCompatibilityAnalyzer.analyze(
                representation, adapter, KoilRuntimeBackend.VULKAN);
        require(compatibility.graphValid(), "graph valid");
        require(compatibility.tensorBindingsValid(), "tensor bindings valid");
        require(compatibility.executable(), "known llama.cpp Vulkan primitive graph must be executable");
        require(compatibility.blockers().isEmpty(), "executable graph has no blockers");
    }

    private static void proveUnknownOperatorBlocksExecution() {
        KoilExecutionRepresentation base = KoilModelImporter.build(denseInspection());
        Set<KoilOperatorKind> operators = new java.util.LinkedHashSet<>(base.requiredOperators());
        operators.add(KoilOperatorKind.DELTA_NET);
        KoilExecutionRepresentation representation = new KoilExecutionRepresentation(
                base.readiness(), base.model(), base.tensors(), base.quantization(), base.tokenizer(), base.graph(),
                base.tensorResolution(), operators, base.persistentState(), base.evidence());
        KoilExecutionCompatibility compatibility = KoilExecutionCompatibilityAnalyzer.analyze(
                representation,
                new KoilExecutionAdapterDescriptor("llama_cpp", "openai_tool_calls", true, Map.of()),
                KoilRuntimeBackend.VULKAN);
        require(!compatibility.executable(), "unknown operator coverage must block execution claim");
        require(compatibility.blockers().stream().anyMatch(value -> value.contains("DELTA_NET")), "delta-net blocker exposed");
    }

    private static void proveUnsupportedBackendBlocksExecution() {
        KoilExecutionRepresentation representation = KoilModelImporter.build(denseInspection());
        KoilExecutionCompatibility compatibility = KoilExecutionCompatibilityAnalyzer.analyze(
                representation,
                new KoilExecutionAdapterDescriptor("llama_cpp", "openai_tool_calls", true, Map.of()),
                KoilRuntimeBackend.OPENCL);
        require(!compatibility.executable(), "undeclared backend must block execution claim");
        require(!compatibility.blockers().isEmpty(), "backend blockers exposed");
    }

    private static boolean hasRole(KoilExecutionRepresentation representation, KoilTensorRole role) {
        return representation.tensorResolution().bindings().stream().anyMatch(binding -> binding.role() == role);
    }

    private static ModelArtifactInspection denseInspection() {
        List<ModelTensorDescriptor> tensors = List.of(
                tensor("token_embd.weight", 32, 8),
                tensor("blk.0.attn_norm.weight", 8),
                tensor("blk.0.attn_q.weight", 8, 8),
                tensor("blk.0.attn_k.weight", 8, 8),
                tensor("blk.0.attn_v.weight", 8, 8),
                tensor("blk.0.attn_output.weight", 8, 8),
                tensor("blk.0.ffn_norm.weight", 8),
                tensor("blk.0.ffn_gate.weight", 16, 8),
                tensor("blk.0.ffn_up.weight", 16, 8),
                tensor("blk.0.ffn_down.weight", 8, 16),
                tensor("output_norm.weight", 8),
                tensor("output.weight", 32, 8));
        return new ModelArtifactInspection(true, ModelArtifactFormat.SAFETENSORS_DIRECTORY, 1, tensors.size(),
                "llama", "proof", "proof", "proof", "", 4096, 0, 0, Set.of(), tensors,
                Map.of("llama.block_count", "1", "llama.embedding_length", "8", "llama.attention.head_count_kv", "2",
                        "tensor_manifest.complete", "true", "tensor_manifest.tensor_count", Integer.toString(tensors.size())),
                "r42 compatibility proof");
    }

    private static ModelTensorDescriptor tensor(String name, long... shape) {
        return new ModelTensorDescriptor(name, java.util.Arrays.stream(shape).boxed().toList(),
                "F16", 0, 0, "proof.safetensors");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("proof failed: " + message);
    }
}
