package com.spirit.koil.api.performance;

import java.util.List;

public record PerformanceBenchmarkResult(
        long startedAtMillis,
        long durationMillis,
        PerformanceProfileMode requestedMode,
        PerformanceSnapshot snapshot,
        List<PerformanceRecommendation> recommendations,
        String summary,
        List<String> testNotes,
        List<PerformanceBenchmarkPhaseResult> phaseResults,
        boolean worldUiHidden,
        boolean automationProbeUsed
) {
    public PerformanceBenchmarkResult(long startedAtMillis, long durationMillis, PerformanceProfileMode requestedMode, PerformanceSnapshot snapshot, List<PerformanceRecommendation> recommendations, String summary) {
        this(startedAtMillis, durationMillis, requestedMode, snapshot, recommendations, summary, List.of(), List.of(), false, false);
    }

    public PerformanceBenchmarkResult(long startedAtMillis, long durationMillis, PerformanceProfileMode requestedMode, PerformanceSnapshot snapshot, List<PerformanceRecommendation> recommendations, String summary, List<String> testNotes, boolean worldUiHidden, boolean automationProbeUsed) {
        this(startedAtMillis, durationMillis, requestedMode, snapshot, recommendations, summary, testNotes, List.of(), worldUiHidden, automationProbeUsed);
    }
}
