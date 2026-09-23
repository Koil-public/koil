package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactFormat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Standalone proof for measured-profile reuse and stable hardware identity. */
public final class KoilMeasuredTuningProof {
    private KoilMeasuredTuningProof() {}

    public static void main(String[] args) {
        KoilExecutionAdapterDescriptor adapter = new KoilExecutionAdapterDescriptor(
                "llama_cpp", "openai_tool_calls", true, Map.of());
        KoilArchitectureDescriptor architecture = new KoilArchitectureDescriptor(
                "lfm2", KoilArchitectureCategory.HYBRID_ATTENTION,
                Set.of(KoilOperatorKind.GEMM), Set.of(KoilModelStateKind.ATTENTION_KV), false, "proof");
        KoilModelProfile model = new KoilModelProfile(
                true, ModelArtifactFormat.GGUF_FILE, "proof", "lfm2", architecture, "",
                16384, 1, 0, 0, Set.of(), Map.of(), "proof");
        KoilRuntimeArtifactInventory inventory = new KoilRuntimeArtifactInventory(
                "llama.cpp-proof", null, Set.of(KoilRuntimeBackend.CPU, KoilRuntimeBackend.VULKAN), true, "proof");
        KoilHardwareProfile base = new KoilHardwareProfile(
                "stable-proof-hardware", "Linux proof", "amd64", 8, 4,
                16L << 30, 8L << 30, Set.of(), List.of(), inventory);
        KoilHardwareProfile observed = KoilHardwareProfiler.reconcileRuntimeObservations(base, Map.of(
                "actualComputePlacement", "hybrid",
                "resolvedComputeDevice", "Vulkan0"));
        require(base.fingerprint().equals(observed.fingerprint()),
                "hardware fingerprint changed after accelerator observation");

        KoilMeasuredTuningProfile measured = new KoilMeasuredTuningProfile(
                "tune-1", "llama_cpp_max", Instant.now(), "llama_cpp", "model",
                "lfm2", "runtime", base.fingerprint(), KoilComputeMode.AUTOMATIC,
                KoilRuntimeBackend.UNKNOWN, 12.5,
                Map.of("generationTokensPerSecond", 25.0),
                Map.of("placement", "hybrid", "gpuLayers", "14", "batchSize", "2048", "ubatchSize", "512"));

        KoilExecutionPlan automatic = KoilExecutionPlanner.plan(
                adapter, model, observed, KoilComputeMode.AUTOMATIC, measured);
        require(automatic.primaryBackend() == KoilRuntimeBackend.VULKAN,
                "automatic measured hybrid did not resolve to observed Vulkan backend");
        require("llama_cpp_max".equals(automatic.decisions().get("tuningSource")),
                "measured tuning source was not attached to execution plan");
        require("14".equals(automatic.decisions().get("tuned.gpuLayers")),
                "measured GPU-layer decision was not preserved");
        KoilExecutionPlan runtimeConfirmed = KoilExecutionPlanner.reconcileRuntimeEvidence(automatic, observed, Map.of(
                "actualComputePlacement", "hybrid",
                "resolvedComputeDevice", "Vulkan0",
                "gpuLayers", "14/15",
                "activeContextTokens", "4096"));
        require(runtimeConfirmed.primaryBackend() == KoilRuntimeBackend.VULKAN,
                "runtime-confirmed Vulkan placement did not become authoritative");
        require("14/15".equals(runtimeConfirmed.decisions().get("runtimeGpuLayers")),
                "runtime GPU layer telemetry was not attached to the plan");
        require("4096".equals(runtimeConfirmed.decisions().get("runtimeContextTokens")),
                "runtime context telemetry was not attached to the plan");

        KoilExecutionPlan explicitCpu = KoilExecutionPlanner.plan(
                adapter, model, observed, KoilComputeMode.CPU, measured);
        require(explicitCpu.primaryBackend() == KoilRuntimeBackend.CPU,
                "explicit CPU was overridden by measured automatic tuning");
        require(explicitCpu.decisions().containsKey("tuningRejected"),
                "explicit-mode tuning rejection was not observable");

        KoilMeasuredTuningProfile wrongHardware = new KoilMeasuredTuningProfile(
                "tune-2", "llama_cpp_max", Instant.now(), "llama_cpp", "model",
                "lfm2", "runtime", "different-machine", KoilComputeMode.AUTOMATIC,
                KoilRuntimeBackend.VULKAN, 99.0, Map.of(), Map.of("placement", "gpu"));
        KoilExecutionPlan incompatible = KoilExecutionPlanner.plan(
                adapter, model, observed, KoilComputeMode.AUTOMATIC, wrongHardware);
        require(!incompatible.decisions().containsKey("tuningSource"),
                "incompatible hardware tuning was reused");
        require(incompatible.decisions().containsKey("tuningRejected"),
                "incompatible tuning rejection was not observable");

        System.out.println("Koil measured tuning proof passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
