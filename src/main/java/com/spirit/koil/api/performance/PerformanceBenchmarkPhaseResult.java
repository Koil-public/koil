package com.spirit.koil.api.performance;

import java.util.LinkedHashMap;
import java.util.Map;

public record PerformanceBenchmarkPhaseResult(
        String phaseId,
        String label,
        long startedAtMillis,
        long durationMillis,
        String context,
        boolean uiHidden,
        boolean automationProbeUsed,
        PerformanceSnapshot snapshot,
        PerformanceBottleneck dominantPressure,
        String note
) {
    public Map<String, Object> toJsonMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("phaseId", phaseId);
        map.put("label", label);
        map.put("startedAtMillis", startedAtMillis);
        map.put("durationMillis", durationMillis);
        map.put("context", context);
        map.put("uiHidden", uiHidden);
        map.put("automationProbeUsed", automationProbeUsed);
        map.put("dominantPressure", dominantPressure == null ? PerformanceBottleneck.HEALTHY.name() : dominantPressure.name());
        map.put("note", note);
        map.put("snapshot", PerformanceLearningService.snapshotFeatures(snapshot));
        return map;
    }
}
