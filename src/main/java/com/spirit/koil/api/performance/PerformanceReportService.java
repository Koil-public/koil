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
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("currentFps", snapshot.fps());
        metrics.put("averageFps", snapshot.averageFps());
        metrics.put("onePercentLowFps", snapshot.onePercentLowFps());
        metrics.put("frameTimeMs", snapshot.frameTimeMs());
        metrics.put("frameTimeSpikeMs", snapshot.maxFrameTimeMs());
        metrics.put("jvmMemoryUsage", snapshot.usedMemoryMb() + "/" + snapshot.maxMemoryMb() + " MB");
        metrics.put("processCpuLoad", PerformanceMonitor.processCpuLoad());
        metrics.put("systemCpuLoad", PerformanceMonitor.systemCpuLoad());
        metrics.put("freeSystemMemoryMb", PerformanceMonitor.freeSystemMemoryMb());
        metrics.put("gcPressureEstimate", snapshot.gcPressure());
        metrics.put("chunkStressEstimate", snapshot.chunkStress());
        metrics.put("shaderPressureEstimate", PerformanceRuntimeContextService.shaderPipelineActive() ? snapshot.shaderPressure() : -1.0D);
        metrics.put("modLoadPressureEstimate", snapshot.modLoadPressure());
        metrics.put("resourcePackPressureEstimate", snapshot.resourcePackPressure());
        report.put("metrics", metrics);
        report.put("recommendations", recommendations == null ? List.of() : recommendations.stream().map(PerformanceRecommendation::toJsonMap).toList());
        report.put("benchmarkHistoryFile", PerformancePaths.BENCHMARK_HISTORY.toString());
        report.put("optimizationHistoryFile", PerformancePaths.OPTIMIZATION_HISTORY.toString());
        report.put("notes", List.of(
                "Koil keeps recommendations reversible by backing up options.txt before applying safe changes.",
                "Major settings are not changed silently unless the user applies supported recommendations.",
                "CPU load, FPS, frame time, JVM heap, entity count, display mode, and player latency are direct runtime or host observations when available.",
                "GC, chunk, shader, modpack, resource-pack, and bottleneck pressure values are diagnostic estimates rather than hardware utilization percentages.",
                "Remote server TPS and MSPT are not inferred from client FPS because vanilla clients do not expose authoritative server timing data."
        ));
        PerformanceJsonStore.write(PerformancePaths.PERFORMANCE_REPORT, report);
        return report;
    }
}
