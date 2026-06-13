package com.spirit.koil.api.performance;

import net.minecraft.client.MinecraftClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PerformanceReportService {
    private PerformanceReportService() {
    }

    public static Map<String, Object> writeReport(MinecraftClient client, PerformanceProfileMode mode, List<PerformanceRecommendation> recommendations) {
        PerformanceHardwareProfile hardware = PerformanceHardwareScanner.scan(client);
        PerformanceSnapshot snapshot = PerformanceMonitor.latestSnapshot(client);
        PerformanceRuntimeContext runtimeContext = PerformanceRuntimeContextService.capture(client);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAtMillis", System.currentTimeMillis());
        report.put("mode", mode.name());
        report.put("hardware", hardware);
        report.put("runtimeContext", runtimeContext);
        report.put("currentSnapshot", snapshot);
        report.put("detectedBottleneck", snapshot.primaryBottleneck().name());
        report.put("likelyCause", snapshot.likelyCause());
        report.put("beforeAfterMetrics", Map.of(
                "currentFps", snapshot.fps(),
                "averageFps", snapshot.averageFps(),
                "onePercentLowFps", snapshot.onePercentLowFps(),
                "memoryUsage", snapshot.usedMemoryMb() + "/" + snapshot.maxMemoryMb() + " MB",
                "frameTimeSpikeMs", snapshot.maxFrameTimeMs(),
                "gcPressure", snapshot.gcPressure(),
                "chunkStress", snapshot.chunkStress(),
                "shaderPressure", snapshot.shaderPressure()
        ));
        report.put("recommendations", recommendations == null ? List.of() : recommendations.stream().map(PerformanceRecommendation::toJsonMap).toList());
        report.put("benchmarkHistoryFile", PerformancePaths.BENCHMARK_HISTORY.toString());
        report.put("optimizationHistoryFile", PerformancePaths.OPTIMIZATION_HISTORY.toString());
        report.put("notes", List.of(
                "Koil keeps recommendations reversible by backing up options.txt before applying safe changes.",
                "Major settings are not changed silently unless the user applies safe recommendations.",
                "Shader/resourcepack details are detected conservatively to avoid breaking unsupported mod configs."
        ));
        PerformanceJsonStore.write(PerformancePaths.PERFORMANCE_REPORT, report);
        return report;
    }
}
