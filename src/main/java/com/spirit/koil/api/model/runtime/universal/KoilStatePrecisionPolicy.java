package com.spirit.koil.api.model.runtime.universal;

/**
 * Universal launch-time state-precision policy.
 *
 * Quantization is admitted only when the projected state savings are material for the current
 * launch-memory deficit. The policy remains launch-only and never reformats live conversation
 * state. V state stays FP16 until adapters prove a quantized-V attention path independently.
 */
public final class KoilStatePrecisionPolicy {
    private static final long MIB = 1024L * 1024L;
    private static final long MIN_MATERIAL_SAVINGS = 64L * MIB;
    private static final long MODERATE_MATERIAL_SAVINGS = 128L * MIB;
    private static final long LONG_CONTEXT_MATERIAL_SAVINGS = 256L * MIB;
    private static final long VERY_LONG_CONTEXT_MIN_FULL_STATE = 1024L * MIB;
    private static final double VERY_LONG_CONTEXT_MIN_SAVINGS_FRACTION = 0.20;

    private KoilStatePrecisionPolicy() {}

    public static KoilStatePrecisionDecision select(
            KoilMemoryPressureSnapshot memory,
            int contextTokens,
            KoilRuntimeBackend backend
    ) {
        return select(memory, contextTokens, backend, KoilStateMemoryEstimate.unknown("state geometry not supplied"));
    }

    public static KoilStatePrecisionDecision select(
            KoilMemoryPressureSnapshot memory,
            int contextTokens,
            KoilRuntimeBackend backend,
            KoilStateMemoryEstimate estimate
    ) {
        int context = Math.max(1, contextTokens);
        KoilMemoryPressureSnapshot.Pressure pressure = memory == null
                ? KoilMemoryPressureSnapshot.Pressure.UNKNOWN : memory.pressure();
        long discretionary = memory == null ? 0L : Math.max(0L, memory.inferenceBudgetBytes());
        long available = memory == null ? 0L : Math.max(0L, memory.availableBytes());
        long reserve = memory == null ? 0L : Math.max(0L, memory.reservedHostBytes());
        KoilStateMemoryEstimate geometry = estimate == null
                ? KoilStateMemoryEstimate.unknown("state geometry unavailable") : estimate;

        if (context <= 4096) {
            return fp16(geometry, "small context keeps full FP16 state; expected savings do not justify adaptive quantization");
        }
        if (!supportsConservativeQ8Key(backend)) {
            return fp16(geometry, "backend capability is not proven for conservative 8-bit K-state quantization");
        }

        if (geometry.known()) {
            long savings = geometry.q8KeySavingsBytes();
            long reserveDeficit = Math.max(0L, reserve - available);
            long constrainedNeed = Math.max(MIN_MATERIAL_SAVINGS, Math.min(256L * MIB, reserveDeficit / 2L));

            if ((pressure == KoilMemoryPressureSnapshot.Pressure.CRITICAL
                    || pressure == KoilMemoryPressureSnapshot.Pressure.CONSTRAINED)
                    && savings >= constrainedNeed) {
                return q8Key(geometry, "projected K-state savings are material for the launch-memory deficit"
                        + " (savings=" + mib(savings) + "MiB, target>=" + mib(constrainedNeed) + "MiB)");
            }

            if (pressure == KoilMemoryPressureSnapshot.Pressure.MODERATE
                    && savings >= MODERATE_MATERIAL_SAVINGS
                    && (discretionary < 768L * MIB || context >= 32768)) {
                return q8Key(geometry, "projected K-state savings are material under moderate launch headroom"
                        + " (savings=" + mib(savings) + "MiB)");
            }

            if (pressure == KoilMemoryPressureSnapshot.Pressure.HEALTHY
                    && context >= 65536
                    && savings >= LONG_CONTEXT_MATERIAL_SAVINGS
                    && geometry.fullPrecisionBytes() >= VERY_LONG_CONTEXT_MIN_FULL_STATE
                    && savingsFraction(geometry) >= VERY_LONG_CONTEXT_MIN_SAVINGS_FRACTION) {
                return q8Key(geometry, "very long context has material absolute K-state savings"
                        + " (savings=" + mib(savings) + "MiB, full=" + mib(geometry.fullPrecisionBytes())
                        + "MiB, fraction=" + percent(savingsFraction(geometry)) + ")");
            }

            return fp16(geometry, "projected K-state savings are not material enough to justify quantization"
                    + " (savings=" + mib(savings) + "MiB)");
        }

        // Conservative compatibility fallback for artifacts that do not expose enough geometry.
        if (pressure == KoilMemoryPressureSnapshot.Pressure.CRITICAL
                || pressure == KoilMemoryPressureSnapshot.Pressure.CONSTRAINED) {
            return q8Key(geometry, "state geometry unavailable; conservative pressure fallback reduces K precision");
        }
        if (pressure == KoilMemoryPressureSnapshot.Pressure.MODERATE
                && (discretionary < 768L * MIB || context >= 32768)) {
            return q8Key(geometry, "state geometry unavailable; moderate long-context fallback reduces K precision");
        }
        return fp16(geometry, "launch headroom supports full FP16 state; state geometry unavailable for byte estimate");
    }

    private static KoilStatePrecisionDecision fp16(KoilStateMemoryEstimate estimate, String reason) {
        long full = estimate == null ? 0L : estimate.fullPrecisionBytes();
        return new KoilStatePrecisionDecision(
                KoilStatePrecision.FP16, KoilStatePrecision.FP16, false, 1.0,
                full, full, 0L,
                estimate == null ? KoilStateMemoryEstimate.Confidence.UNKNOWN : estimate.confidence(),
                reason);
    }

    private static KoilStatePrecisionDecision q8Key(KoilStateMemoryEstimate estimate, String reason) {
        long full = estimate == null ? 0L : estimate.fullPrecisionBytes();
        long chosen = full;
        long savings = 0L;
        double relative = 0.75;
        KoilStateMemoryEstimate.Confidence confidence = KoilStateMemoryEstimate.Confidence.UNKNOWN;
        if (estimate != null && estimate.known()) {
            chosen = Math.max(0L, estimate.q8KeyBytes() + estimate.valueFp16Bytes());
            savings = Math.max(0L, estimate.q8KeySavingsBytes());
            relative = full <= 0L ? 0.75 : Math.max(0.0, Math.min(1.0, (double) chosen / (double) full));
            confidence = estimate.confidence();
        }
        return new KoilStatePrecisionDecision(
                KoilStatePrecision.Q8_BLOCK, KoilStatePrecision.FP16, true, relative,
                full, chosen, savings, confidence, reason);
    }

    private static double savingsFraction(KoilStateMemoryEstimate estimate) {
        if (estimate == null || estimate.fullPrecisionBytes() <= 0L) return 0.0;
        return Math.max(0.0, Math.min(1.0,
                (double) estimate.q8KeySavingsBytes() / (double) estimate.fullPrecisionBytes()));
    }

    private static String percent(double fraction) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", fraction * 100.0);
    }

    private static long mib(long bytes) {
        return bytes <= 0L ? 0L : bytes / MIB;
    }

    private static boolean supportsConservativeQ8Key(KoilRuntimeBackend backend) {
        if (backend == null) return false;
        return switch (backend) {
            case CPU, CUDA, VULKAN, METAL, ROCM, HIP -> true;
            default -> false;
        };
    }
}
