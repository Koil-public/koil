package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.ModelPerformanceBenchmarkRequest;
import com.spirit.koil.api.model.ModelPerformanceBenchmarkResult;
import com.spirit.koil.api.model.runtime.universal.KoilBenchmarkAdapter;
import com.spirit.koil.api.model.runtime.universal.KoilBenchmarkCandidate;
import com.spirit.koil.api.model.runtime.universal.KoilBenchmarkResult;
import com.spirit.koil.api.model.runtime.universal.KoilBenchmarkWorkload;
import com.spirit.koil.api.model.runtime.universal.KoilBenchmarkResourceEvidence;
import com.spirit.koil.api.model.runtime.universal.KoilTuningKey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * llama.cpp implementation of Koil's universal benchmark contract.
 * Lifecycle ownership is supplied by the caller because candidate activation may replace the
 * running native process. The adapter owns translation, native measurement and telemetry capture.
 */
public final class LlamaCppBenchmarkAdapter implements KoilBenchmarkAdapter {
    @FunctionalInterface
    public interface CandidateActivator {
        CompletableFuture<Void> activate(LlamaCppMaxRuntimeProfile profile);
    }

    @FunctionalInterface
    public interface BenchmarkRunner {
        CompletableFuture<ModelPerformanceBenchmarkResult> run(ModelPerformanceBenchmarkRequest request);
    }

    public record RuntimeSnapshot(Map<String, String> diagnostics, int contextTokens) {
        public RuntimeSnapshot {
            diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
            contextTokens = Math.max(0, contextTokens);
        }
    }

    private final CandidateActivator activator;
    private final BenchmarkRunner runner;
    private final Supplier<RuntimeSnapshot> snapshotSupplier;

    public LlamaCppBenchmarkAdapter(
            CandidateActivator activator,
            BenchmarkRunner runner,
            Supplier<RuntimeSnapshot> snapshotSupplier
    ) {
        if (activator == null) throw new IllegalArgumentException("activator is required");
        if (runner == null) throw new IllegalArgumentException("runner is required");
        if (snapshotSupplier == null) throw new IllegalArgumentException("snapshotSupplier is required");
        this.activator = activator;
        this.runner = runner;
        this.snapshotSupplier = snapshotSupplier;
    }

    @Override
    public String adapterId() {
        return "llama_cpp";
    }

    @Override
    public CompletableFuture<KoilBenchmarkResult> benchmark(
            KoilBenchmarkCandidate candidate,
            KoilBenchmarkWorkload workload
    ) {
        if (candidate == null) return CompletableFuture.failedFuture(new IllegalArgumentException("candidate is required"));
        KoilBenchmarkWorkload safe = workload == null
                ? new KoilBenchmarkWorkload("Koil benchmark", 64, java.time.Duration.ofMinutes(2), candidate.label())
                : workload;
        LlamaCppMaxRuntimeProfile profile = LlamaCppBenchmarkCandidateBridge.toRuntimeProfile(candidate);
        return activator.activate(profile).thenCompose(ignored -> runner.run(new ModelPerformanceBenchmarkRequest(
                safe.prompt(), safe.outputTokens(), safe.timeout(), candidate.label())))
                .thenApply(benchmark -> {
                    if (benchmark == null || !benchmark.valid()) {
                        throw new IllegalStateException("llama.cpp returned an invalid benchmark sample");
                    }
                    RuntimeSnapshot snapshot = snapshotSupplier.get();
                    Map<String, String> diagnostics = snapshot == null ? Map.of() : snapshot.diagnostics();
                    String placement = diagnostics.getOrDefault("actualComputePlacement", "unknown");
                    int gpuLayers = parseGpuLayers(diagnostics.getOrDefault("actualGpuLayers", "-1"));
                    String integrityState = diagnostics.getOrDefault("computeIntegrity", "");
                    Boolean integrity = switch (integrityState.toLowerCase(java.util.Locale.ROOT)) {
                        case "passed", "healthy", "not_required" -> Boolean.TRUE;
                        case "failed", "degraded" -> Boolean.FALSE;
                        default -> null;
                    };
                    KoilBenchmarkResult result = LlamaCppBenchmarkCandidateBridge.toUniversalResult(
                            candidate,
                            benchmark,
                            placement,
                            gpuLayers,
                            diagnostics.getOrDefault("runtimeBuildInfo", ""),
                            integrity,
                            diagnostics
                    );
                    LlamaCppComputeSafetyPolicy.RuntimePressure pressure = LlamaCppComputeSafetyPolicy.runtimePressure();
                    result = withResourceEvidence(result, pressure);
                    int actualContextTokens = snapshot == null ? 0 : snapshot.contextTokens();
                    if (actualContextTokens > 0) {
                        String actualRegime = KoilTuningKey.contextRegime(actualContextTokens);
                        if (!actualRegime.equals(candidate.contextRegime())) {
                            return new KoilBenchmarkResult(
                                    candidate, KoilBenchmarkResult.Outcome.REJECTED,
                                    result.promptTokens(), result.generatedTokens(),
                                    result.promptTokensPerSecond(), result.generationTokensPerSecond(),
                                    result.promptMillis(), result.generationMillis(),
                                    result.coldTimeToFirstTokenMillis(), result.warmTimeToFirstTokenMillis(),
                                    result.wallMillis(), result.memoryBytes(), result.acceleratorMemoryBytes(),
                                    result.actualPlacement(), result.actualGpuLayers(),
                                    result.actualStatePlacement(), result.actualOperatorPlacement(),
                                    result.integrityPassed(), "context_regime_mismatch",
                                    "candidate context " + candidate.contextRegime()
                                            + " restarted as " + actualRegime + " (" + actualContextTokens + " tokens)",
                                    result.measuredAt(), result.runtimeRevision(), result.metrics(), result.observations());
                        }
                    }
                    return result;
                });
    }

    private static KoilBenchmarkResult withResourceEvidence(
            KoilBenchmarkResult result,
            LlamaCppComputeSafetyPolicy.RuntimePressure pressure
    ) {
        if (result == null || pressure == null) return result;
        long available = Math.max(0L, pressure.availableBytes());
        long floor = Math.max(0L, pressure.hardFloorBytes());
        Map<String, Double> metrics = new LinkedHashMap<>(result.metrics());
        if (available > 0L) metrics.put(KoilBenchmarkResourceEvidence.AVAILABLE_MEMORY_BYTES, (double) available);
        if (floor > 0L) metrics.put(KoilBenchmarkResourceEvidence.HARD_FLOOR_BYTES, (double) floor);
        if (available > 0L && floor > 0L) {
            long comfortWindow = 2L * 1024L * 1024L * 1024L;
            double headroom = (available - floor) / (double) comfortWindow;
            metrics.put(KoilBenchmarkResourceEvidence.HEADROOM_SCORE,
                    KoilBenchmarkResourceEvidence.clamp01(headroom));
        }
        return new KoilBenchmarkResult(
                result.candidate(), result.outcome(),
                result.promptTokens(), result.generatedTokens(),
                result.promptTokensPerSecond(), result.generationTokensPerSecond(),
                result.promptMillis(), result.generationMillis(),
                result.coldTimeToFirstTokenMillis(), result.warmTimeToFirstTokenMillis(),
                result.wallMillis(), result.memoryBytes(), result.acceleratorMemoryBytes(),
                result.actualPlacement(), result.actualGpuLayers(),
                result.actualStatePlacement(), result.actualOperatorPlacement(),
                result.integrityPassed(), result.failureType(), result.detail(),
                result.measuredAt(), result.runtimeRevision(), metrics, result.observations());
    }

    public static ModelPerformanceBenchmarkResult nativeTimings(KoilBenchmarkResult result) {
        if (result == null) throw new IllegalArgumentException("result is required");
        return new ModelPerformanceBenchmarkResult(
                result.promptTokens(),
                result.generatedTokens(),
                number(result.promptTokensPerSecond()),
                number(result.generationTokensPerSecond()),
                number(result.promptMillis()),
                number(result.generationMillis()),
                number(result.coldTimeToFirstTokenMillis()),
                result.wallMillis() == null ? 0L : result.wallMillis(),
                result.detail()
        );
    }

    private static int parseGpuLayers(String value) {
        if (value == null) return -1;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^\\s*(\\d+)").matcher(value);
        if (!matcher.find()) return -1;
        try { return Integer.parseInt(matcher.group(1)); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static double number(Double value) {
        return value == null ? 0.0D : value;
    }
}
