package com.spirit.koil.api.model.runtime.universal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class KoilExecutionPlannerProof {
    private KoilExecutionPlannerProof() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("koil-runtime-inventory-proof");
        try {
            Path executable = root.resolve("llama-server");
            Files.writeString(executable, "proof");
            Files.writeString(root.resolve("libggml-vulkan.so"), "proof");
            Files.writeString(root.resolve("llama-bench"), "proof");
            KoilRuntimeArtifactInventory inventory = KoilRuntimeArtifactInventory.inspect("llama.cpp-proof", executable);
            require(inventory.compiledBackends().contains(KoilRuntimeBackend.CPU), "CPU backend not discovered");
            require(inventory.compiledBackends().contains(KoilRuntimeBackend.VULKAN), "Vulkan backend not discovered");
            require(inventory.benchmarkToolPresent(), "llama-bench was not discovered");

            String fpA = KoilHardwareProfiler.fingerprint("Linux 6", "amd64", 8, 16L << 30,
                    Set.of(KoilRuntimeBackend.CPU, KoilRuntimeBackend.VULKAN));
            String fpB = KoilHardwareProfiler.fingerprint("Linux 6", "amd64", 8, 16L << 30,
                    Set.of(KoilRuntimeBackend.VULKAN, KoilRuntimeBackend.CPU));
            require(fpA.equals(fpB), "hardware fingerprint depends on set iteration order");

            KoilExecutionAdapterDescriptor adapter = new KoilExecutionAdapterDescriptor(
                    "llama_cpp", "openai_tool_calls", true, Map.of());
            KoilHardwareProfile unproven = new KoilHardwareProfile(fpA, "Linux", "amd64", 8, 4,
                    16L << 30, 8L << 30, Set.of(), List.of(), inventory);
            KoilExecutionPlan auto = KoilExecutionPlanner.plan(adapter, null, unproven, KoilComputeMode.AUTOMATIC);
            require(auto.primaryBackend() == KoilRuntimeBackend.UNKNOWN,
                    "compiled Vulkan was incorrectly treated as observed GPU hardware");
            require(auto.computeMode() == KoilComputeMode.AUTOMATIC, "automatic mode changed unexpectedly");

            KoilAcceleratorProfile deck = new KoilAcceleratorProfile(
                    "Vulkan0", KoilRuntimeBackend.VULKAN, "AMD Custom GPU 0932", 0L, true, true,
                    "llama.cpp --list-devices");
            KoilHardwareProfile observed = new KoilHardwareProfile(fpA, "Linux", "amd64", 8, 4,
                    16L << 30, 8L << 30, Set.of(), List.of(deck), inventory);
            KoilExecutionPlan accelerated = KoilExecutionPlanner.plan(adapter, null, observed, KoilComputeMode.AUTOMATIC);
            require(accelerated.primaryBackend() == KoilRuntimeBackend.VULKAN,
                    "observed Vulkan accelerator was not selected");

            KoilExecutionPlan cpu = KoilExecutionPlanner.plan(adapter, null, observed, KoilComputeMode.CPU);
            require(cpu.primaryBackend() == KoilRuntimeBackend.CPU, "explicit CPU mode was overridden");

            KoilHardwareProfile reconciled = KoilHardwareProfiler.reconcileRuntimeObservations(
                    unproven, Map.of("actualComputePlacement", "gpu", "resolvedComputeDevice", "Vulkan0"));
            require(reconciled.acceleratorObserved(), "runtime GPU observation did not enrich hardware profile");
            require(reconciled.accelerators().get(0).backend() == KoilRuntimeBackend.VULKAN,
                    "runtime Vulkan observation mapped to the wrong backend");

            KoilRuntimeArtifactInventory metalInventory = new KoilRuntimeArtifactInventory(
                    "metal-proof", root, Set.of(KoilRuntimeBackend.CPU, KoilRuntimeBackend.METAL), false, "proof");
            KoilHardwareProfile mismatched = new KoilHardwareProfile(fpA, "macOS", "x86_64", 8, 4,
                    16L << 30, 8L << 30, Set.of(), List.of(deck), metalInventory);
            KoilExecutionPlan gpu = KoilExecutionPlanner.plan(adapter, null, mismatched, KoilComputeMode.GPU);
            require(gpu.primaryBackend() == KoilRuntimeBackend.UNKNOWN,
                    "planner claimed a GPU backend that the runtime does not contain");
            require(gpu.computeMode() == KoilComputeMode.GPU,
                    "planner silently changed user placement instead of leaving validation to adapter safety");

            System.out.println("Koil execution planner proof passed");
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
