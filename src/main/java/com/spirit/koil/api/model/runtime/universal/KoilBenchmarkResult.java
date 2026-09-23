package com.spirit.koil.api.model.runtime.universal;

import java.time.Instant;
import java.util.Map;

/** Adapter-neutral evidence produced by executing one benchmark candidate. */
public record KoilBenchmarkResult(
        KoilBenchmarkCandidate candidate,
        Outcome outcome,
        int promptTokens,
        int generatedTokens,
        Double promptTokensPerSecond,
        Double generationTokensPerSecond,
        Double promptMillis,
        Double generationMillis,
        Double coldTimeToFirstTokenMillis,
        Double warmTimeToFirstTokenMillis,
        Long wallMillis,
        Long memoryBytes,
        Long acceleratorMemoryBytes,
        String actualPlacement,
        Integer actualGpuLayers,
        KoilStatePlacement actualStatePlacement,
        KoilOperatorPlacement actualOperatorPlacement,
        Boolean integrityPassed,
        String failureType,
        String detail,
        Instant measuredAt,
        String runtimeRevision,
        Map<String, Double> metrics,
        Map<String, String> observations
) {
    public enum Outcome { SUCCESS, REJECTED, FAILED }

    public KoilBenchmarkResult {
        if (candidate == null) throw new IllegalArgumentException("candidate is required");
        outcome = outcome == null ? Outcome.FAILED : outcome;
        promptTokens = Math.max(0, promptTokens);
        generatedTokens = Math.max(0, generatedTokens);
        promptTokensPerSecond = finitePositiveOrNull(promptTokensPerSecond);
        generationTokensPerSecond = finitePositiveOrNull(generationTokensPerSecond);
        promptMillis = finitePositiveOrNull(promptMillis);
        generationMillis = finitePositiveOrNull(generationMillis);
        coldTimeToFirstTokenMillis = finitePositiveOrNull(coldTimeToFirstTokenMillis);
        warmTimeToFirstTokenMillis = finitePositiveOrNull(warmTimeToFirstTokenMillis);
        wallMillis = nonNegativeOrNull(wallMillis);
        memoryBytes = nonNegativeOrNull(memoryBytes);
        acceleratorMemoryBytes = nonNegativeOrNull(acceleratorMemoryBytes);
        actualPlacement = safe(actualPlacement);
        actualGpuLayers = actualGpuLayers == null ? null : Math.max(-1, actualGpuLayers);
        actualStatePlacement = actualStatePlacement == null ? KoilStatePlacement.AUTOMATIC : actualStatePlacement;
        actualOperatorPlacement = actualOperatorPlacement == null ? KoilOperatorPlacement.AUTOMATIC : actualOperatorPlacement;
        failureType = safe(failureType);
        detail = safe(detail);
        measuredAt = measuredAt == null ? Instant.now() : measuredAt;
        runtimeRevision = safe(runtimeRevision);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        observations = observations == null ? Map.of() : Map.copyOf(observations);
    }

    public boolean validMeasurement() {
        return outcome == Outcome.SUCCESS
                && promptTokens > 0 && generatedTokens > 0
                && promptTokensPerSecond != null && generationTokensPerSecond != null
                && (integrityPassed == null || integrityPassed);
    }

    private static Double finitePositiveOrNull(Double value) {
        return value != null && Double.isFinite(value) && value > 0.0D ? value : null;
    }

    private static Long nonNegativeOrNull(Long value) {
        return value == null ? null : Math.max(0L, value);
    }

    private static String safe(String value) { return value == null ? "" : value.strip(); }
}
