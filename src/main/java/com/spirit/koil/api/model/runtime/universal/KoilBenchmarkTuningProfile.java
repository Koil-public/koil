package com.spirit.koil.api.model.runtime.universal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts a scored universal benchmark winner into the durable planner evidence owned by Koil.
 * Adapter-specific stores may still be maintained for native launch compatibility, but they are
 * not the conceptual owner of an automatic execution policy once Koil has measured the candidate.
 */
public final class KoilBenchmarkTuningProfile {
    public static final String SOURCE = "koil_universal_autotune";
    public static final String PROTOCOL = "universal-benchmark-v1";

    private KoilBenchmarkTuningProfile() {}

    public static KoilMeasuredTuningProfile fromWinner(
            String measurementIdentity,
            KoilCandidateScorer.ScoredResult winner,
            KoilExecutionAdapterDescriptor adapter,
            KoilModelProfile model,
            String modelId,
            String runtimeIdentity,
            String hardwareFingerprint
    ) {
        if (winner == null || winner.result() == null) {
            throw new IllegalArgumentException("winner is required");
        }
        KoilBenchmarkResult result = winner.result();
        if (!result.validMeasurement()) {
            throw new IllegalArgumentException("winner must be a valid benchmark measurement");
        }
        KoilBenchmarkCandidate candidate = result.candidate();
        KoilExecutionSettings settings = candidate.settings();

        Map<String, Double> metrics = new LinkedHashMap<>();
        put(metrics, "promptTokensPerSecond", result.promptTokensPerSecond());
        put(metrics, "generationTokensPerSecond", result.generationTokensPerSecond());
        put(metrics, "promptMillis", result.promptMillis());
        put(metrics, "generationMillis", result.generationMillis());
        put(metrics, "coldTimeToFirstTokenMillis", result.coldTimeToFirstTokenMillis());
        put(metrics, "warmTimeToFirstTokenMillis", result.warmTimeToFirstTokenMillis());
        if (result.wallMillis() != null) metrics.put("wallMillis", result.wallMillis().doubleValue());
        if (result.memoryBytes() != null) metrics.put("memoryBytes", result.memoryBytes().doubleValue());
        if (result.acceleratorMemoryBytes() != null) {
            metrics.put("acceleratorMemoryBytes", result.acceleratorMemoryBytes().doubleValue());
        }
        metrics.put("promptTokens", (double) result.promptTokens());
        metrics.put("generatedTokens", (double) result.generatedTokens());
        result.metrics().forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null && Double.isFinite(value)) {
                metrics.putIfAbsent(name, value);
            }
        });

        Map<String, String> decisions = new LinkedHashMap<>();
        decisions.put("placement", settings.placement().name().toLowerCase());
        decisions.put("device", settings.device());
        decisions.put("gpuLayers", Integer.toString(settings.gpuLayers()));
        decisions.put("generationThreads", Integer.toString(settings.generationThreads()));
        decisions.put("batchThreads", Integer.toString(settings.batchThreads()));
        decisions.put("poll", Integer.toString(settings.pollPercent()));
        decisions.put("pollBatch", Integer.toString(settings.batchPollMode()));
        decisions.put("batchSize", Integer.toString(settings.batchSize()));
        decisions.put("ubatchSize", Integer.toString(settings.microBatchSize()));
        decisions.put("statePlacement", settings.statePlacement().name().toLowerCase());
        decisions.put("operatorPlacement", settings.operatorPlacement().name().toLowerCase());
        decisions.put("flashAttention", settings.flashAttention().name().toLowerCase());
        decisions.put("repack", Boolean.toString(settings.repack()));
        decisions.put("memoryBudgetBytes", Long.toString(settings.memoryBudgetBytes()));
        decisions.put("candidateId", candidate.id());
        decisions.put("candidateLabel", candidate.label());
        decisions.put("candidateValidation", candidate.validationMode().name().toLowerCase());
        decisions.put("contextRegime", candidate.contextRegime());
        decisions.put("statePrecisionRegime", KoilStatePrecisionEvidence.fromResult(result));
        decisions.put("benchmarkProtocol", PROTOCOL);
        decisions.put("benchmarkAdapter", adapter == null ? "" : adapter.id());
        decisions.put("actualPlacement", result.actualPlacement());
        if (result.actualGpuLayers() != null) {
            decisions.put("actualGpuLayers", Integer.toString(result.actualGpuLayers()));
        }
        decisions.put("actualStatePlacement", result.actualStatePlacement().name().toLowerCase());
        decisions.put("actualOperatorPlacement", result.actualOperatorPlacement().name().toLowerCase());
        if (result.integrityPassed() != null) {
            decisions.put("integrityPassed", Boolean.toString(result.integrityPassed()));
        }
        result.observations().forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null && !value.isBlank()) {
                decisions.putIfAbsent("observed." + name, value.strip());
            }
        });

        String identity = safe(measurementIdentity);
        if (identity.isBlank()) {
            identity = SOURCE + ":" + candidate.geometryKey() + ":" + result.measuredAt().toEpochMilli();
        }
        KoilRuntimeBackend backend = candidate.backend();
        if (settings.placement() == KoilPlacementPolicy.CPU) backend = KoilRuntimeBackend.CPU;

        return new KoilMeasuredTuningProfile(
                identity,
                SOURCE,
                result.measuredAt() == null ? Instant.now() : result.measuredAt(),
                adapter == null ? "" : adapter.id(),
                modelId,
                model == null ? "" : model.architectureId(),
                safe(runtimeIdentity).isBlank() ? result.runtimeRevision() : runtimeIdentity,
                hardwareFingerprint,
                KoilComputeMode.AUTOMATIC,
                backend,
                winner.score(),
                metrics,
                decisions
        );
    }

    private static void put(Map<String, Double> target, String name, Double value) {
        if (value != null && Double.isFinite(value)) target.put(name, value);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
