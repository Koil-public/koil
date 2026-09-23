package com.spirit.koil.api.model.runtime.universal;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Standalone proof for typed universal execution policy derivation and reconciliation. */
public final class KoilExecutionPolicyProof {
    private KoilExecutionPolicyProof() {}

    public static void main(String[] args) {
        KoilExecutionAdapterDescriptor adapter = new KoilExecutionAdapterDescriptor(
                "llama_cpp", "openai_tool_calls", true, Map.of());
        KoilRuntimeArtifactInventory inventory = new KoilRuntimeArtifactInventory(
                "llama.cpp-b10173", null, Set.of(KoilRuntimeBackend.CPU, KoilRuntimeBackend.VULKAN), true, "proof");
        KoilAcceleratorProfile accelerator = new KoilAcceleratorProfile(
                "Vulkan0", KoilRuntimeBackend.VULKAN, "proof", 0, true, true, "proof");
        KoilHardwareProfile hardware = new KoilHardwareProfile(
                "proof-hardware", "Linux", "amd64", 16, 8,
                16L << 30, 8L << 30, Set.of(), java.util.List.of(accelerator), inventory);
        KoilMeasuredTuningProfile measured = new KoilMeasuredTuningProfile(
                "proof-profile", "llama_cpp_max", Instant.now(), "llama_cpp", "proof-model", "lfm2",
                "llama.cpp-b10173", "proof-hardware", KoilComputeMode.AUTOMATIC, KoilRuntimeBackend.VULKAN, 10.0,
                Map.of("generationTokensPerSecond", 42.0),
                Map.of(
                        "placement", "hybrid",
                        "device", "Vulkan0",
                        "gpuLayers", "14",
                        "generationThreads", "0",
                        "batchThreads", "0",
                        "poll", "100",
                        "pollBatch", "1",
                        "batchSize", "2048",
                        "ubatchSize", "512"));

        KoilExecutionPlan automatic = KoilExecutionPlanner.plan(adapter, null, hardware, KoilComputeMode.AUTOMATIC, measured);
        require(automatic.primaryBackend() == KoilRuntimeBackend.VULKAN, "measured Vulkan backend missing");
        require(automatic.settings().placement() == KoilPlacementPolicy.HYBRID, "hybrid placement missing");
        require(automatic.settings().gpuLayers() == 14, "GPU layer count missing");
        require(automatic.settings().batchSize() == 2048, "batch size missing");
        require(automatic.settings().microBatchSize() == 512, "microbatch missing");
        require(automatic.settings().pollPercent() == 100 && automatic.settings().batchPollMode() == 1, "poll geometry missing");
        require(automatic.settings().statePlacement() == KoilStatePlacement.HOST, "hybrid state must remain on host");
        require(automatic.settings().operatorPlacement() == KoilOperatorPlacement.HOST, "hybrid operators must remain on host");

        KoilExecutionPlan cpu = KoilExecutionPlanner.plan(adapter, null, hardware, KoilComputeMode.CPU, measured);
        require(cpu.primaryBackend() == KoilRuntimeBackend.CPU, "explicit CPU was overridden");
        require(cpu.settings().placement() == KoilPlacementPolicy.CPU, "CPU placement missing");
        require(cpu.settings().gpuLayers() == 0, "CPU plan has GPU layers");

        KoilExecutionPlan reconciled = KoilExecutionPlanner.reconcileRuntimeEvidence(
                automatic, hardware, Map.of(
                        "actualComputePlacement", "hybrid",
                        "resolvedComputeDevice", "Vulkan0",
                        "gpuLayers", "4096",
                        "actualGpuLayers", "13/15",
                        "activeContextTokens", "2048"));
        require(reconciled.primaryBackend() == KoilRuntimeBackend.VULKAN, "runtime Vulkan evidence lost");
        require(reconciled.settings().gpuLayers() == 13, "verified actualGpuLayers did not override ambiguous gpuLayers telemetry");
        require("2048".equals(reconciled.decisions().get("runtimeContextTokens")), "runtime context evidence missing");

        System.out.println("Koil execution policy proof passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
