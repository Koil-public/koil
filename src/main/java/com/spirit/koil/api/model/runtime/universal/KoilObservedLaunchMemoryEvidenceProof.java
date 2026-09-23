package com.spirit.koil.api.model.runtime.universal;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-light proof that adapter launch-memory evidence stays observational. */
public final class KoilObservedLaunchMemoryEvidenceProof {
    private static final long MIB = 1024L * 1024L;

    private KoilObservedLaunchMemoryEvidenceProof() {}

    public static void main(String[] args) {
        KoilExecutionAdapterDescriptor adapter = new KoilExecutionAdapterDescriptor(
                "proof_adapter", "openai_tool_calls", false, Map.of());
        KoilExecutionSettings settings = new KoilExecutionSettings(
                KoilPlacementPolicy.GPU, "gpu0", -1,
                8, 8, 2048, 256, 100, 1,
                KoilStatePlacement.ACCELERATOR, KoilOperatorPlacement.ACCELERATOR,
                KoilFeatureMode.AUTO, true, 512L * MIB);
        KoilExecutionPlan original = new KoilExecutionPlan(
                adapter, KoilComputeMode.AUTOMATIC, KoilRuntimeBackend.VULKAN,
                "proof-hardware", "proof", settings,
                Map.of("memoryAvailableBytes", Long.toString(570L * MIB)));
        KoilHardwareProfile hardware = new KoilHardwareProfile(
                "proof-hardware", "linux", "amd64", 8, 4,
                16L * 1024L * MIB, 570L * MIB,
                Set.of(), List.of(), new KoilRuntimeArtifactInventory("proof", null, Set.of(KoilRuntimeBackend.CPU), false, "proof"));

        KoilExecutionPlan observed = KoilExecutionPlanner.reconcileObservedLaunchMemoryEvidence(
                original, hardware, Map.of(
                        "launchMemoryAvailableBytes", Long.toString(1864L * MIB),
                        "launchMemoryModelBytes", Long.toString(146L * MIB),
                        "launchMemoryProfile", "critical_low_memory"));

        require(observed.settings().memoryBudgetBytes() == original.settings().memoryBudgetBytes(),
                "observed launch memory must not rewrite launch-policy memory budget");
        require("1864".equals(Long.toString(Long.parseLong(
                observed.decisions().get("observedLaunchMemoryAvailableBytes")) / MIB)),
                "expected authoritative 1864 MiB observed launch memory");
        require("execution_adapter".equals(observed.decisions().get("observedLaunchMemorySource")),
                "expected execution-adapter provenance");
        require("critical_low_memory".equals(observed.decisions().get("observedLaunchMemoryProfile")),
                "expected adapter launch profile to survive normalization");
        require("570".equals(Long.toString(Long.parseLong(
                observed.decisions().get("memoryAvailableBytes")) / MIB)),
                "policy snapshot must remain intact");

        System.out.println("Koil observed launch memory evidence proof passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
