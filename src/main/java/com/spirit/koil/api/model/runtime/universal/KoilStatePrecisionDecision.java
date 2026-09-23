package com.spirit.koil.api.model.runtime.universal;

/** Launch-time precision decision for attention/recurrent state. */
public record KoilStatePrecisionDecision(
        KoilStatePrecision keyPrecision,
        KoilStatePrecision valuePrecision,
        boolean adaptive,
        double estimatedRelativeBytes,
        long estimatedFullStateBytes,
        long estimatedChosenStateBytes,
        long estimatedSavingsBytes,
        KoilStateMemoryEstimate.Confidence estimateConfidence,
        String reason
) {
    public KoilStatePrecisionDecision {
        keyPrecision = keyPrecision == null ? KoilStatePrecision.FP16 : keyPrecision;
        valuePrecision = valuePrecision == null ? KoilStatePrecision.FP16 : valuePrecision;
        estimatedRelativeBytes = Math.max(0.0, Math.min(1.0, estimatedRelativeBytes));
        estimatedFullStateBytes = Math.max(0L, estimatedFullStateBytes);
        estimatedChosenStateBytes = Math.max(0L, estimatedChosenStateBytes);
        estimatedSavingsBytes = Math.max(0L, estimatedSavingsBytes);
        estimateConfidence = estimateConfidence == null ? KoilStateMemoryEstimate.Confidence.UNKNOWN : estimateConfidence;
        reason = reason == null ? "" : reason.strip();
    }

    /** Compatibility constructor retained for existing adapters/proofs. */
    public KoilStatePrecisionDecision(
            KoilStatePrecision keyPrecision,
            KoilStatePrecision valuePrecision,
            boolean adaptive,
            double estimatedRelativeBytes,
            String reason
    ) {
        this(keyPrecision, valuePrecision, adaptive, estimatedRelativeBytes,
                0L, 0L, 0L, KoilStateMemoryEstimate.Confidence.UNKNOWN, reason);
    }

    public String summary() {
        return "k=" + keyPrecision.name().toLowerCase()
                + ",v=" + valuePrecision.name().toLowerCase()
                + " | adaptive=" + adaptive
                + " | relative_bytes=" + String.format(java.util.Locale.ROOT, "%.2f", estimatedRelativeBytes)
                + (estimatedFullStateBytes > 0L ? " | estimated_full_bytes=" + estimatedFullStateBytes : "")
                + (estimatedSavingsBytes > 0L ? " | estimated_savings_bytes=" + estimatedSavingsBytes : "")
                + " | estimate_confidence=" + estimateConfidence.name().toLowerCase();
    }
}
