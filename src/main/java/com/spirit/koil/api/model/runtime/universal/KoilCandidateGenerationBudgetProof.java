package com.spirit.koil.api.model.runtime.universal;

/** Dependency-light proof for memory-aware candidate geometry. */
public final class KoilCandidateGenerationBudgetProof {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private KoilCandidateGenerationBudgetProof() {}

    public static void main(String[] args) {
        KoilCandidateGenerationBudget critical = KoilCandidateGenerationBudget.from(
                KoilMemoryBudgetPlanner.from(16L * GIB, 700L * MIB));
        require(critical.baselineBatchSize() == 1024 && critical.baselineMicroBatchSize() == 256,
                "critical pressure should avoid aggressive baseline batch geometry");
        require(!critical.allowLargeBatch() && !critical.allowLargeMicroBatch(),
                "critical pressure must not generate optional large candidates");

        KoilCandidateGenerationBudget moderate = KoilCandidateGenerationBudget.from(
                KoilMemoryBudgetPlanner.from(16L * GIB, 4195L * MIB));
        require(moderate.baselineBatchSize() == 2048 && moderate.baselineMicroBatchSize() == 256,
                "observed 4.2 GiB launch headroom should use moderate geometry");
        require(moderate.allowLargeBatch(), "moderate pressure with >768 MiB discretionary budget may test 2048 batch");

        KoilCandidateGenerationBudget healthy = KoilCandidateGenerationBudget.from(
                KoilMemoryBudgetPlanner.from(16L * GIB, 8L * GIB));
        require(healthy.baselineMicroBatchSize() == 512 && healthy.allowLargeMicroBatch(),
                "healthy headroom should retain large microbatch exploration");

        System.out.println("Koil candidate generation budget proof passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
