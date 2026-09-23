package com.spirit.koil.api.model.runtime.universal;

import java.util.Map;

/** Adapter-neutral resource-safety evidence attached to benchmark results. */
public final class KoilBenchmarkResourceEvidence {
    public static final String HEADROOM_SCORE = "resource.headroom_score";
    public static final String AVAILABLE_MEMORY_BYTES = "resource.available_memory_bytes";
    public static final String HARD_FLOOR_BYTES = "resource.hard_floor_bytes";

    private KoilBenchmarkResourceEvidence() {}

    /**
     * Returns a normalized [0,1] headroom score when measured, otherwise 1.0 (neutral).
     * Unknown resource telemetry must not invent a penalty.
     */
    public static double headroomScore(KoilBenchmarkResult result) {
        if (result == null) return 1.0D;
        Map<String, Double> metrics = result.metrics();
        if (metrics == null || metrics.isEmpty()) return 1.0D;
        Double value = metrics.get(HEADROOM_SCORE);
        if (value == null || !Double.isFinite(value)) return 1.0D;
        return clamp01(value);
    }

    public static double clamp01(double value) {
        if (!Double.isFinite(value)) return 1.0D;
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
