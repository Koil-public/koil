package com.spirit.koil.api.performance;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

public final class PerformanceBenchmarkRunner {
    private PerformanceBenchmarkRunner() {
    }

    public static PerformanceBenchmarkResult runQuickBenchmark(MinecraftClient client, PerformanceProfileMode mode) {
        long start = System.currentTimeMillis();
        PerformanceSnapshot snapshot = PerformanceMonitor.latestSnapshot(client);
        return finishBenchmark(client, mode, start, snapshot);
    }

    public static PerformanceBenchmarkResult finishBenchmark(MinecraftClient client, PerformanceProfileMode mode, long startedAtMillis) {
        PerformanceSnapshot snapshot = PerformanceMonitor.snapshotSince(client, startedAtMillis);
        return finishBenchmark(client, mode, startedAtMillis, snapshot, List.of(), false, false);
    }

    public static PerformanceBenchmarkResult finishBenchmark(MinecraftClient client, PerformanceProfileMode mode, long startedAtMillis, List<String> testNotes, boolean worldUiHidden, boolean automationProbeUsed) {
        PerformanceSnapshot snapshot = PerformanceMonitor.snapshotSince(client, startedAtMillis);
        List<PerformanceBenchmarkPhaseResult> phases = new ArrayList<>();
        phases.add(phase(client, startedAtMillis, System.currentTimeMillis(), worldUiHidden ? "world_hidden" : "client_baseline", worldUiHidden ? "World baseline, UI hidden" : "Client/menu baseline", worldUiHidden, false, worldUiHidden ? "World/render cost sampled without Koil UI." : "Menu/client pressure sampled without world-only metrics."));
        if (automationProbeUsed) {
            phases.add(phase(client, startedAtMillis + Math.max(1L, (System.currentTimeMillis() - startedAtMillis) / 2L), System.currentTimeMillis(), "world_movement", "Movement and chunk streaming probe", worldUiHidden, true, "Automation movement requested so Koil can compare static world cost against movement/chunk streaming cost."));
        }
        if (client != null && client.currentScreen != null) {
            phases.add(phase(client, Math.max(startedAtMillis, System.currentTimeMillis() - 1500L), System.currentTimeMillis(), "ui_overlay_cost", "UI overlay cost", false, false, "A UI is open; this phase is tracked separately so UI frame cost is not labeled as shader pressure."));
        }
        return finishBenchmark(client, mode, startedAtMillis, snapshot, testNotes, phases, worldUiHidden, automationProbeUsed);
    }

    private static PerformanceBenchmarkResult finishBenchmark(MinecraftClient client, PerformanceProfileMode mode, long startedAtMillis, PerformanceSnapshot snapshot) {
        return finishBenchmark(client, mode, startedAtMillis, snapshot, List.of(), false, false);
    }

    private static PerformanceBenchmarkResult finishBenchmark(MinecraftClient client, PerformanceProfileMode mode, long startedAtMillis, PerformanceSnapshot snapshot, List<String> testNotes, boolean worldUiHidden, boolean automationProbeUsed) {
        return finishBenchmark(client, mode, startedAtMillis, snapshot, testNotes, List.of(), worldUiHidden, automationProbeUsed);
    }

    private static PerformanceBenchmarkResult finishBenchmark(MinecraftClient client, PerformanceProfileMode mode, long startedAtMillis, PerformanceSnapshot snapshot, List<String> testNotes, List<PerformanceBenchmarkPhaseResult> phaseResults, boolean worldUiHidden, boolean automationProbeUsed) {
        List<PerformanceRecommendation> recommendations = phaseResults == null || phaseResults.isEmpty()
                ? PerformanceRecommendationEngine.recommend(client, mode, snapshot)
                : PerformanceRecommendationEngine.recommendFromBenchmark(client, mode, snapshot, phaseResults);
        String summary = snapshot.primaryBottleneck() == PerformanceBottleneck.HEALTHY
                ? "Benchmark complete. Stable sample window."
                : "Benchmark complete. Primary pressure: " + snapshot.primaryBottleneck().label();
        PerformanceBenchmarkResult result = new PerformanceBenchmarkResult(
                startedAtMillis,
                System.currentTimeMillis() - startedAtMillis,
                mode,
                snapshot,
                recommendations,
                summary,
                testNotes,
                phaseResults,
                worldUiHidden,
                automationProbeUsed
        );
        PerformanceJsonStore.append(PerformancePaths.BENCHMARK_HISTORY, result);
        PerformanceLearningService.recordBenchmark(result);
        return result;
    }

    private static PerformanceBenchmarkPhaseResult phase(MinecraftClient client, long startMillis, long endMillis, String phaseId, String label, boolean uiHidden, boolean automationProbeUsed, String note) {
        PerformanceSnapshot snapshot = PerformanceMonitor.snapshotSince(client, startMillis);
        return new PerformanceBenchmarkPhaseResult(
                phaseId,
                label,
                startMillis,
                Math.max(0L, endMillis - startMillis),
                snapshot == null ? "unknown" : snapshot.worldType(),
                uiHidden,
                automationProbeUsed,
                snapshot,
                snapshot == null ? PerformanceBottleneck.HEALTHY : snapshot.primaryBottleneck(),
                note
        );
    }
}
