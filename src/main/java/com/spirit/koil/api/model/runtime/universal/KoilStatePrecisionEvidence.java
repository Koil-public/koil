package com.spirit.koil.api.model.runtime.universal;

import java.util.Locale;
import java.util.Map;

/**
 * Normalizes persistent-state precision evidence for benchmark/tuning identity.
 * Precision belongs to measured evidence because throughput, memory use and stability can differ
 * even when every other execution setting is identical.
 */
public final class KoilStatePrecisionEvidence {
    public static final String LEGACY_FP16 = "k=fp16,v=fp16";

    private KoilStatePrecisionEvidence() {}

    public static String regime(String keyPrecision, String valuePrecision) {
        return "k=" + normalizePrecision(keyPrecision, "fp16")
                + ",v=" + normalizePrecision(valuePrecision, "fp16");
    }

    public static String fromObservations(Map<String, String> observations) {
        if (observations == null || observations.isEmpty()) return LEGACY_FP16;
        return regime(
                observations.getOrDefault("statePrecisionK", "fp16"),
                observations.getOrDefault("statePrecisionV", "fp16"));
    }

    public static String fromResult(KoilBenchmarkResult result) {
        return result == null ? LEGACY_FP16 : fromObservations(result.observations());
    }

    public static String normalizeRegime(String value) {
        String safe = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (safe.isBlank()) return LEGACY_FP16;
        if (safe.startsWith("k=") && safe.contains(",v=")) return safe;
        String[] pair = safe.split("[/,:]", 2);
        if (pair.length == 2) return regime(pair[0], pair[1]);
        return regime(safe, "fp16");
    }

    private static String normalizePrecision(String value, String fallback) {
        String safe = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (safe.isBlank() || "auto".equals(safe) || "unknown".equals(safe)) return fallback;
        return switch (safe) {
            case "q8", "q8_0", "q8-block", "q8_block" -> "q8_block";
            case "q4", "q4_0", "q4-block", "q4_block" -> "q4_block";
            case "f16", "fp16" -> "fp16";
            case "f32", "fp32" -> "fp32";
            case "bf16" -> "bf16";
            default -> safe.replace('-', '_');
        };
    }
}
