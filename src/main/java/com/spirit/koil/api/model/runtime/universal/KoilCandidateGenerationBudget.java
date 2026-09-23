package com.spirit.koil.api.model.runtime.universal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded memory guidance for benchmark candidate generation.
 *
 * This is intentionally conservative. It controls optional benchmark geometry only; it is not
 * permission to migrate tensors/state or to violate an adapter's own hard safety checks.
 */
public record KoilCandidateGenerationBudget(
        KoilMemoryPressureSnapshot.Pressure pressure,
        long availableBytes,
        long discretionaryBudgetBytes,
        int baselineBatchSize,
        int baselineMicroBatchSize,
        boolean allowLargeMicroBatch,
        boolean allowLargeBatch
) {
    private static final long MIB = 1024L * 1024L;

    public KoilCandidateGenerationBudget {
        pressure = pressure == null ? KoilMemoryPressureSnapshot.Pressure.UNKNOWN : pressure;
        availableBytes = Math.max(0L, availableBytes);
        discretionaryBudgetBytes = Math.max(0L, discretionaryBudgetBytes);
        baselineBatchSize = Math.max(128, baselineBatchSize);
        baselineMicroBatchSize = Math.max(64, Math.min(baselineMicroBatchSize, baselineBatchSize));
    }

    public static KoilCandidateGenerationBudget unknown() {
        return new KoilCandidateGenerationBudget(
                KoilMemoryPressureSnapshot.Pressure.UNKNOWN,
                0L, 0L, 2048, 512, true, true);
    }

    public static KoilCandidateGenerationBudget from(KoilMemoryPressureSnapshot snapshot) {
        if (snapshot == null || snapshot.pressure() == KoilMemoryPressureSnapshot.Pressure.UNKNOWN) {
            return unknown();
        }
        long budget = Math.max(0L, snapshot.discretionaryBytes());
        return switch (snapshot.pressure()) {
            case CRITICAL -> new KoilCandidateGenerationBudget(
                    snapshot.pressure(), snapshot.availableBytes(), budget,
                    1024, 256, false, false);
            case CONSTRAINED -> new KoilCandidateGenerationBudget(
                    snapshot.pressure(), snapshot.availableBytes(), budget,
                    1024, 256, false, false);
            case MODERATE -> new KoilCandidateGenerationBudget(
                    snapshot.pressure(), snapshot.availableBytes(), budget,
                    2048, 256,
                    budget >= 1536L * MIB,
                    budget >= 768L * MIB);
            case HEALTHY -> new KoilCandidateGenerationBudget(
                    snapshot.pressure(), snapshot.availableBytes(), budget,
                    2048, 512, true, true);
            case UNKNOWN -> unknown();
        };
    }

    public Map<String, String> attributes() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("memory.pressure", pressure.name().toLowerCase(java.util.Locale.ROOT));
        values.put("memory.available_bytes", Long.toString(availableBytes));
        values.put("memory.discretionary_budget_bytes", Long.toString(discretionaryBudgetBytes));
        values.put("memory.baseline_batch", Integer.toString(baselineBatchSize));
        values.put("memory.baseline_ubatch", Integer.toString(baselineMicroBatchSize));
        return Map.copyOf(values);
    }
}
