package com.spirit.koil.api.model.runtime.universal;

import java.util.List;

/** Verifies memory pressure changes optional benchmark exploration without removing safe core candidates. */
public final class KoilMemoryAwareCandidateGenerationProof {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private KoilMemoryAwareCandidateGenerationProof() {}

    public static void main(String[] args) {
        KoilCandidateGenerationBudget constrained = KoilCandidateGenerationBudget.from(
                KoilMemoryBudgetPlanner.from(16L * GIB, 1500L * MIB));
        List<KoilBenchmarkCandidate> tight = KoilCandidateGenerator.baseline(
                KoilRuntimeBackend.VULKAN, "Vulkan0", 15, 14, 4, 8, "ctx<=8k",
                ignored -> new int[]{2048, 512}, constrained);
        require(tight.stream().anyMatch(v -> v.settings().placement() == KoilPlacementPolicy.CPU),
                "constrained generation must retain CPU candidates");
        require(tight.stream().anyMatch(v -> v.validationMode() == KoilBenchmarkCandidate.ValidationMode.ACCELERATOR_FIT),
                "constrained generation must retain one safe GPU-fit candidate");
        require(tight.stream().noneMatch(v -> v.settings().microBatchSize() > 256),
                "constrained generation must clamp optional microbatch pressure");
        require(tight.stream().noneMatch(v -> v.label().equals("gpu-logical-hot")),
                "constrained generation must omit the optional hot accelerator candidate");

        KoilCandidateGenerationBudget moderate = KoilCandidateGenerationBudget.from(
                KoilMemoryBudgetPlanner.from(16L * GIB, 4195L * MIB));
        List<KoilBenchmarkCandidate> roomy = KoilCandidateGenerator.baseline(
                KoilRuntimeBackend.VULKAN, "Vulkan0", 15, 14, 4, 8, "ctx<=8k",
                ignored -> new int[]{2048, 512}, moderate);
        require(roomy.stream().anyMatch(v -> v.settings().batchSize() == 2048),
                "moderate launch headroom should still explore 2048 batch");
        require(roomy.stream().allMatch(v -> v.settings().memoryBudgetBytes() == moderate.discretionaryBudgetBytes()),
                "candidate settings must carry the universal discretionary memory budget");

        System.out.println("Koil memory-aware candidate generation proof passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
