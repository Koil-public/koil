package com.spirit.koil.api.model.runtime.universal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Scores successful candidates using workload-aware inference performance evidence. */
public final class KoilCandidateScorer {
    private static final double MAX_RESOURCE_HEADROOM_PENALTY = 0.12D;

    private KoilCandidateScorer() {}

    public static List<ScoredResult> score(List<KoilBenchmarkResult> results) {
        return score(results, List.of(), null);
    }

    public static List<ScoredResult> score(List<KoilBenchmarkResult> results, KoilBenchmarkWorkload workload) {
        return score(results, List.of(), workload);
    }

    public static List<ScoredResult> score(
            List<KoilBenchmarkResult> results,
            List<KoilBenchmarkHistoryStore.StoredSession> history
    ) {
        return score(results, history, null);
    }

    /**
     * Scores fresh results for the supplied workload while using exact-key, same-workload historical
     * sessions as a bounded confidence regularizer. Fresh measurements always retain at least 90%
     * of the decision weight.
     */
    public static List<ScoredResult> score(
            List<KoilBenchmarkResult> results,
            List<KoilBenchmarkHistoryStore.StoredSession> history,
            KoilBenchmarkWorkload workload
    ) {
        List<KoilBenchmarkResult> valid = results == null ? List.of() : results.stream()
                .filter(KoilBenchmarkResult::validMeasurement).toList();
        if (valid.isEmpty()) return List.of();

        KoilBenchmarkWorkload effectiveWorkload = workload == null
                ? new KoilBenchmarkWorkload("", 96, java.time.Duration.ofMinutes(2), "interactive", KoilBenchmarkWorkload.Kind.INTERACTIVE)
                : workload;
        KoilBenchmarkWorkload.ScoreWeights weights = effectiveWorkload.scoreWeights();

        double maxPrompt = valid.stream().map(KoilBenchmarkResult::promptTokensPerSecond)
                .filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(1.0D);
        double maxDecode = valid.stream().map(KoilBenchmarkResult::generationTokensPerSecond)
                .filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(1.0D);
        double minTtft = valid.stream().map(KoilBenchmarkResult::coldTimeToFirstTokenMillis)
                .filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).min().orElse(1.0D);

        List<KoilBenchmarkHistoryStore.StoredSession> workloadHistory = history == null ? List.of() : history.stream()
                .filter(java.util.Objects::nonNull)
                .filter(value -> value.workload().kind() == effectiveWorkload.kind())
                .toList();
        Map<String, KoilHistoricalBenchmarkEvidence.Evidence> evidence =
                KoilHistoricalBenchmarkEvidence.summarize(workloadHistory);
        double historicalMaxPrompt = evidence.values().stream()
                .filter(value -> value.successfulSamples() >= 2)
                .map(KoilHistoricalBenchmarkEvidence.Evidence::medianPromptTokensPerSecond)
                .filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(1.0D);
        double historicalMaxDecode = evidence.values().stream()
                .filter(value -> value.successfulSamples() >= 2)
                .map(KoilHistoricalBenchmarkEvidence.Evidence::medianGenerationTokensPerSecond)
                .filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(1.0D);
        double historicalMinTtft = evidence.values().stream()
                .filter(value -> value.successfulSamples() >= 2)
                .map(KoilHistoricalBenchmarkEvidence.Evidence::medianColdTtftMillis)
                .filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).min().orElse(1.0D);

        List<ScoredResult> scored = new ArrayList<>();
        for (KoilBenchmarkResult result : valid) {
            double freshPerformanceScore = componentScore(result, maxPrompt, maxDecode, minTtft, weights);
            double safety = result.integrityPassed() == null || result.integrityPassed() ? 1.0D : 0.0D;
            freshPerformanceScore *= safety;
            double resourceHeadroomScore = KoilBenchmarkResourceEvidence.headroomScore(result);
            double resourcePenalty = MAX_RESOURCE_HEADROOM_PENALTY * (1.0D - resourceHeadroomScore);
            double freshScore = freshPerformanceScore * (1.0D - resourcePenalty);

            KoilHistoricalBenchmarkEvidence.Evidence prior = evidence.get(
                    KoilHistoricalBenchmarkEvidence.geometryKey(result));
            double finalScore = freshScore;
            double historyWeight = 0.0D;
            if (prior != null && prior.successfulSamples() >= 2 && prior.confidence() > 0.0D) {
                double historicalPrompt = prior.medianPromptTokensPerSecond() == null ? 0.0D
                        : prior.medianPromptTokensPerSecond() / historicalMaxPrompt;
                double historicalDecode = prior.medianGenerationTokensPerSecond() == null ? 0.0D
                        : prior.medianGenerationTokensPerSecond() / historicalMaxDecode;
                double historicalLatency = prior.medianColdTtftMillis() == null ? 0.0D
                        : Math.min(1.0D, historicalMinTtft / prior.medianColdTtftMillis());
                double historicalScore = weighted(historicalPrompt, historicalDecode, historicalLatency, weights)
                        * prior.successRate();
                historyWeight = 0.10D * prior.confidence();
                finalScore = freshScore * (1.0D - historyWeight) + historicalScore * historyWeight;
            }
            scored.add(new ScoredResult(
                    result, finalScore, freshScore, historyWeight, effectiveWorkload.kind(),
                    freshPerformanceScore, resourceHeadroomScore, resourcePenalty));
        }
        scored.sort(Comparator.comparingDouble(ScoredResult::score).reversed());
        return List.copyOf(scored);
    }

    private static double componentScore(
            KoilBenchmarkResult result,
            double maxPrompt,
            double maxDecode,
            double minTtft,
            KoilBenchmarkWorkload.ScoreWeights weights
    ) {
        double promptScore = result.promptTokensPerSecond() == null ? 0.0D : result.promptTokensPerSecond() / maxPrompt;
        double decodeScore = result.generationTokensPerSecond() == null ? 0.0D : result.generationTokensPerSecond() / maxDecode;
        double latencyScore = result.coldTimeToFirstTokenMillis() == null
                ? 0.0D : Math.min(1.0D, minTtft / result.coldTimeToFirstTokenMillis());
        return weighted(promptScore, decodeScore, latencyScore, weights);
    }

    private static double weighted(
            double prompt,
            double decode,
            double latency,
            KoilBenchmarkWorkload.ScoreWeights weights
    ) {
        return prompt * weights.prompt() + decode * weights.decode() + latency * weights.latency();
    }

    public record ScoredResult(
            KoilBenchmarkResult result,
            double score,
            double freshScore,
            double historyWeight,
            KoilBenchmarkWorkload.Kind workloadKind,
            double freshPerformanceScore,
            double resourceHeadroomScore,
            double resourcePenalty
    ) {
        public ScoredResult(KoilBenchmarkResult result, double score) {
            this(result, score, score, 0.0D, KoilBenchmarkWorkload.Kind.INTERACTIVE, score, 1.0D, 0.0D);
        }

        public ScoredResult {
            if (result == null) throw new IllegalArgumentException("result is required");
            score = finiteNonNegative(score);
            freshScore = finiteNonNegative(freshScore);
            historyWeight = Double.isFinite(historyWeight) ? Math.max(0.0D, Math.min(1.0D, historyWeight)) : 0.0D;
            workloadKind = workloadKind == null ? KoilBenchmarkWorkload.Kind.INTERACTIVE : workloadKind;
            freshPerformanceScore = finiteNonNegative(freshPerformanceScore);
            resourceHeadroomScore = Double.isFinite(resourceHeadroomScore)
                    ? Math.max(0.0D, Math.min(1.0D, resourceHeadroomScore)) : 1.0D;
            resourcePenalty = Double.isFinite(resourcePenalty)
                    ? Math.max(0.0D, Math.min(MAX_RESOURCE_HEADROOM_PENALTY, resourcePenalty)) : 0.0D;
        }

        private static double finiteNonNegative(double value) {
            return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
        }
    }
}
