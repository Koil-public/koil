package com.spirit.koil.api.model.retrieval;

/** Persistable per-tool speculation evidence. Counts describe one observed sample window. */
public record AutomationToolCalibration(
        String toolId,
        long started,
        long used,
        long discarded,
        long hiddenLatencyMillis,
        long timestampMillis,
        double retrievalScore
) {
    public AutomationToolCalibration {
        toolId = toolId == null ? "" : toolId.strip();
        started = Math.max(0L, started);
        used = Math.max(0L, used);
        discarded = Math.max(0L, discarded);
        hiddenLatencyMillis = Math.max(0L, hiddenLatencyMillis);
        timestampMillis = Math.max(0L, timestampMillis);
        if (!Double.isFinite(retrievalScore)) retrievalScore = 0.0D;
    }

    public double usefulness() {
        return started <= 0L ? 0.0D : Math.min(1.0D, (double) used / (double) started);
    }
}
