package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilStateMemoryEstimate;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecision;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecisionDecision;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecisionEvidence;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-local launch override used only by the MAX benchmark harness.
 *
 * <p>The universal benchmark selects one state-precision regime for the entire sweep, then the
 * llama.cpp adapter forces each candidate launch into exactly that regime. The override is cleared
 * immediately after each process launch so normal runtime starts always use the universal policy.</p>
 */
public final class LlamaCppBenchmarkStatePrecisionOverride {
    private static final AtomicReference<String> ACTIVE = new AtomicReference<>();

    private LlamaCppBenchmarkStatePrecisionOverride() {}

    public static void set(String regime) {
        ACTIVE.set(KoilStatePrecisionEvidence.normalizeRegime(regime));
    }

    public static void clear() {
        ACTIVE.set(null);
    }

    public static Optional<String> current() {
        return Optional.ofNullable(ACTIVE.get());
    }

    public static KoilStatePrecisionDecision apply(KoilStatePrecisionDecision policyDecision) {
        String regime = ACTIVE.get();
        if (regime == null || regime.isBlank()) return policyDecision;
        KoilStatePrecisionDecision fallback = policyDecision == null
                ? new KoilStatePrecisionDecision(KoilStatePrecision.FP16, KoilStatePrecision.FP16,
                false, 1.0D, "benchmark precision fallback")
                : policyDecision;
        KoilStatePrecision key = parse(component(regime, "k="), KoilStatePrecision.FP16);
        KoilStatePrecision value = parse(component(regime, "v="), KoilStatePrecision.FP16);
        long full = fallback.estimatedFullStateBytes();
        long savings = 0L;
        if (key == KoilStatePrecision.Q8_BLOCK && fallback.estimatedSavingsBytes() > 0L) {
            savings = fallback.estimatedSavingsBytes();
        }
        long chosen = full > 0L ? Math.max(0L, full - savings) : 0L;
        double relative = full > 0L ? chosen / (double) full : relativeBytes(key, value);
        return new KoilStatePrecisionDecision(
                key,
                value,
                key != KoilStatePrecision.FP16 || value != KoilStatePrecision.FP16,
                relative,
                full,
                chosen,
                savings,
                fallback.estimateConfidence() == null
                        ? KoilStateMemoryEstimate.Confidence.UNKNOWN
                        : fallback.estimateConfidence(),
                "MAX benchmark precision override: " + regime
        );
    }

    private static String component(String regime, String prefix) {
        String safe = KoilStatePrecisionEvidence.normalizeRegime(regime);
        for (String part : safe.split(",")) {
            String trimmed = part.strip().toLowerCase(Locale.ROOT);
            if (trimmed.startsWith(prefix)) return trimmed.substring(prefix.length());
        }
        return "fp16";
    }

    private static KoilStatePrecision parse(String value, KoilStatePrecision fallback) {
        if (value == null) return fallback;
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "fp32", "f32" -> KoilStatePrecision.FP32;
            case "bf16" -> KoilStatePrecision.BF16;
            case "q8", "q8_0", "q8_block" -> KoilStatePrecision.Q8_BLOCK;
            case "q4", "q4_0", "q4_block" -> KoilStatePrecision.Q4_BLOCK;
            case "auto" -> KoilStatePrecision.AUTO;
            default -> KoilStatePrecision.FP16;
        };
    }

    private static double relativeBytes(KoilStatePrecision key, KoilStatePrecision value) {
        return (relativeComponent(key) + relativeComponent(value)) / 2.0D;
    }

    private static double relativeComponent(KoilStatePrecision precision) {
        return switch (precision) {
            case FP32 -> 2.0D;
            case BF16, FP16, AUTO -> 1.0D;
            case Q8_BLOCK -> 34.0D / 64.0D;
            case Q4_BLOCK -> 18.0D / 64.0D;
        };
    }
}
