package com.spirit.koil.api.model.runtime.universal;

/**
 * Predicts the launch-time state-precision regime used to key reusable tuning evidence.
 * The predictor delegates the actual choice to the same universal state-precision policy used
 * by execution adapters; it only packages model geometry, memory evidence and backend evidence
 * into a stable tuning identity.
 */
public final class KoilStatePrecisionRegimePredictor {
    private KoilStatePrecisionRegimePredictor() {}

    public static Prediction predict(
            KoilModelProfile model,
            KoilHardwareProfile hardware,
            int contextTokens,
            long availableMemoryBytes,
            KoilRuntimeBackend backend
    ) {
        int context = Math.max(1, contextTokens);
        long installed = hardware == null ? 0L : hardware.installedMemoryBytes();
        KoilMemoryPressureSnapshot memory = KoilMemoryBudgetPlanner.from(
                installed, Math.max(0L, availableMemoryBytes), KoilMemoryPressureSnapshot.Phase.LAUNCH);
        KoilStateMemoryEstimate estimate = model == null
                ? KoilStateMemoryEstimate.unknown("model profile unavailable")
                : KoilStateMemoryEstimate.fromMetadata(model.metadata(), context);
        KoilRuntimeBackend resolvedBackend = backend == null ? KoilRuntimeBackend.UNKNOWN : backend;
        KoilStatePrecisionDecision decision = KoilStatePrecisionPolicy.select(
                memory, context, resolvedBackend, estimate);
        String regime = KoilStatePrecisionEvidence.regime(
                decision.keyPrecision().name(), decision.valuePrecision().name());
        return new Prediction(regime, decision, estimate, memory, resolvedBackend);
    }

    public record Prediction(
            String regime,
            KoilStatePrecisionDecision decision,
            KoilStateMemoryEstimate estimate,
            KoilMemoryPressureSnapshot memory,
            KoilRuntimeBackend backend
    ) {
        public Prediction {
            regime = KoilStatePrecisionEvidence.normalizeRegime(regime);
            if (decision == null) throw new IllegalArgumentException("decision is required");
            if (estimate == null) estimate = KoilStateMemoryEstimate.unknown("state estimate unavailable");
            if (memory == null) throw new IllegalArgumentException("memory snapshot is required");
            backend = backend == null ? KoilRuntimeBackend.UNKNOWN : backend;
        }
    }
}
