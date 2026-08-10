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
        long now = System.currentTimeMillis();
        long movementStart = automationProbeUsed ? startedAtMillis + Math.max(1L, (now - startedAtMillis) / 2L) : 0L;
        return finishBenchmark(client, mode, startedAtMillis, testNotes, worldUiHidden, automationProbeUsed, movementStart);
    }

    public static PerformanceBenchmarkResult finishBenchmark(MinecraftClient client, PerformanceProfileMode mode, long startedAtMillis, List<String> testNotes, boolean worldUiHidden, boolean automationProbeUsed, long movementProbeStartedAtMillis) {
        long endedAtMillis = System.currentTimeMillis();
        PerformanceSnapshot snapshot = PerformanceMonitor.snapshotBetween(client, startedAtMillis, endedAtMillis);
        List<PerformanceBenchmarkPhaseResult> phases = new ArrayList<>();
        long baselineEnd = automationProbeUsed && movementProbeStartedAtMillis > startedAtMillis
                ? Math.min(endedAtMillis, movementProbeStartedAtMillis)
                : endedAtMillis;
        phases.add(phase(
                client,
                startedAtMillis,
                baselineEnd,
                worldUiHidden ? "world_static" : "client_baseline",
                worldUiHidden ? "Static world baseline" : "Client/menu baseline",
                worldUiHidden,
                false,
                worldUiHidden ? "Static world/render cost sampled before movement begins." : "Client/menu frame pacing sampled in the current screen context."
        ));
        if (automationProbeUsed && movementProbeStartedAtMillis > 0L && movementProbeStartedAtMillis < endedAtMillis) {
            phases.add(phase(
                    client,
                    movementProbeStartedAtMillis,
                    endedAtMillis,
                    "world_movement",
                    "Movement and chunk streaming probe",
                    worldUiHidden,
                    true,
                    "A four-block movement probe samples the change from a static world to movement and nearby chunk streaming."
            ));
        }
        if (client != null && client.currentScreen != null) {
            long uiStart = Math.max(startedAtMillis, endedAtMillis - 1500L);
            phases.add(phase(
                    client,
                    uiStart,
                    endedAtMillis,
                    "ui_overlay_context",
                    "Screen-open frame context",
                    false,
                    false,
                    "A screen is open. This is reported as screen-open frame pressure, not isolated UI render cost."
            ));
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
                : snapshot.primaryBottleneck() == PerformanceBottleneck.UNKNOWN
                ? "Benchmark complete. Performance pressure is present, but no single cause is verified."
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
        PerformanceSnapshot snapshot = PerformanceMonitor.snapshotBetween(client, startMillis, endMillis);
        return new PerformanceBenchmarkPhaseResult(
                phaseId,
                label,
                startMillis,
                Math.max(0L, endMillis - startMillis),
                snapshot == null ? "unknown" : snapshot.worldType(),
                uiHidden,
                automationProbeUsed,
                snapshot,
                snapshot == null ? PerformanceBottleneck.UNKNOWN : snapshot.primaryBottleneck(),
                note
        );
    }
}
