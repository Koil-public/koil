package com.spirit.koil.api.model;

import com.spirit.koil.api.model.retrieval.AutomationToolCalibration;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Session metrics plus conservative historical calibration. History may only tighten speculation. */
final class ToolSpeculationMetrics {
    private final AtomicLong predicted = new AtomicLong();
    private final AtomicLong started = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong joins = new AtomicLong();
    private final AtomicLong ready = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();
    private final AtomicLong hiddenLatencyMillis = new AtomicLong();
    private final AtomicLong prepared = new AtomicLong();
    private final AtomicLong preparedHits = new AtomicLong();
    private final AtomicLong argumentResolved = new AtomicLong();
    private final AtomicLong chainedArguments = new AtomicLong();
    private final Map<String, ToolCounters> byTool = new ConcurrentHashMap<>();

    void seed(List<AutomationToolCalibration> history) {
        if (history == null) return;
        for (AutomationToolCalibration item : history) {
            if (item == null || item.toolId().isBlank()) continue;
            ToolCounters counters = counters(item.toolId());
            // Bound historical influence so ancient sessions cannot dominate current behavior forever.
            counters.priorStarted.addAndGet(Math.min(64L, item.started()));
            counters.priorUsed.addAndGet(Math.min(64L, item.used()));
        }
    }

    void predicted(long count) { if (count > 0) predicted.addAndGet(count); }
    void started(String toolId) { started.incrementAndGet(); counters(toolId).started.incrementAndGet(); }
    void cacheHit(String toolId, long latencyMillis) {
        cacheHits.incrementAndGet(); hiddenLatencyMillis.addAndGet(Math.max(0L, latencyMillis));
        ToolCounters c = counters(toolId); c.used.incrementAndGet(); c.hiddenLatencyMillis.addAndGet(Math.max(0L, latencyMillis));
    }
    void joined(String toolId) { joins.incrementAndGet(); counters(toolId).used.incrementAndGet(); }
    void ready(String toolId) { ready.incrementAndGet(); counters(toolId).ready.incrementAndGet(); }
    void discarded(String toolId) { discarded.incrementAndGet(); counters(toolId).discarded.incrementAndGet(); }
    void prepared() { prepared.incrementAndGet(); }
    void preparedHit() { preparedHits.incrementAndGet(); }
    void argumentResolved(long count) { if (count > 0) argumentResolved.addAndGet(count); }
    void chainedArgument() { chainedArguments.incrementAndGet(); }

    double adaptivePenalty(String toolId) {
        ToolCounters c = this.byTool.get(clean(toolId));
        if (c != null) {
            long totalStarted = c.priorStarted.get() + c.started.get();
            long totalUsed = c.priorUsed.get() + c.used.get();
            if (totalStarted >= 4L) return penalty(totalStarted, totalUsed);
        }
        long s = started.get();
        if (s < 8L) return 0.0D;
        return penalty(s, cacheHits.get() + joins.get());
    }

    List<AutomationToolCalibration> calibrationSamples() {
        long now = System.currentTimeMillis();
        return this.byTool.entrySet().stream()
                .filter(entry -> entry.getValue().started.get() > 0L)
                .map(entry -> new AutomationToolCalibration(
                        entry.getKey(), entry.getValue().started.get(), entry.getValue().used.get(),
                        entry.getValue().discarded.get(), entry.getValue().hiddenLatencyMillis.get(), now, 0.0D))
                .toList();
    }

    private static double penalty(long started, long used) {
        if (started <= 0L) return 0.0D;
        double usefulness = (double) used / (double) started;
        if (usefulness < 0.15D) return 0.18D;
        if (usefulness < 0.30D) return 0.10D;
        if (usefulness < 0.45D) return 0.05D;
        return 0.0D;
    }

    String summary() {
        long s = started.get(); long used = cacheHits.get() + joins.get();
        double usefulness = s == 0 ? 0.0D : (double) used / (double) s;
        String topTools = this.byTool.entrySet().stream()
                .filter(entry -> entry.getValue().started.get() > 0L)
                .sorted(Comparator.<Map.Entry<String, ToolCounters>>comparingLong(e -> e.getValue().started.get()).reversed())
                .limit(4)
                .map(entry -> entry.getKey() + "[s=" + entry.getValue().started.get() + ",u=" + entry.getValue().used.get()
                        + ",d=" + entry.getValue().discarded.get() + ",p=" + entry.getValue().priorStarted.get() + "]")
                .reduce((a,b) -> a + ";" + b).orElse("");
        return "predicted=" + predicted.get() + ",started=" + s + ",used=" + used + ",ready=" + ready.get()
                + ",discarded=" + discarded.get() + ",usefulness=" + String.format(java.util.Locale.ROOT, "%.3f", usefulness)
                + ",hidden_latency_ms=" + hiddenLatencyMillis.get() + ",arguments_resolved=" + argumentResolved.get()
                + ",chained_arguments=" + chainedArguments.get() + ",prepared=" + prepared.get()
                + ",prepared_hits=" + preparedHits.get() + (topTools.isBlank() ? "" : ",tool_calibration=" + topTools);
    }

    private ToolCounters counters(String toolId) { return this.byTool.computeIfAbsent(clean(toolId), ignored -> new ToolCounters()); }
    private static String clean(String toolId) { return toolId == null || toolId.isBlank() ? "unknown" : toolId.strip(); }

    private static final class ToolCounters {
        private final AtomicLong priorStarted = new AtomicLong();
        private final AtomicLong priorUsed = new AtomicLong();
        private final AtomicLong started = new AtomicLong();
        private final AtomicLong used = new AtomicLong();
        private final AtomicLong ready = new AtomicLong();
        private final AtomicLong discarded = new AtomicLong();
        private final AtomicLong hiddenLatencyMillis = new AtomicLong();
    }
}
