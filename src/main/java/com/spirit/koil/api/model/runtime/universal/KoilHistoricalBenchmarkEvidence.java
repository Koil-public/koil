package com.spirit.koil.api.model.runtime.universal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Robust per-geometry summary of prior benchmark sessions for one exact tuning key.
 *
 * Historical evidence is deliberately descriptive rather than authoritative. Fresh measurements
 * remain the primary tuning signal; this class only provides confidence and stability evidence that
 * can be used to reduce winner churn from normal benchmark noise.
 */
public final class KoilHistoricalBenchmarkEvidence {
    private KoilHistoricalBenchmarkEvidence() {}

    public static Map<String, Evidence> summarize(List<KoilBenchmarkHistoryStore.StoredSession> sessions) {
        if (sessions == null || sessions.isEmpty()) return Map.of();
        Map<String, Accumulator> byGeometry = new LinkedHashMap<>();
        for (KoilBenchmarkHistoryStore.StoredSession stored : sessions) {
            if (stored == null || stored.session() == null) continue;
            for (KoilBenchmarkResult result : stored.session().results()) {
                if (result == null || result.candidate() == null) continue;
                byGeometry.computeIfAbsent(geometryKey(result), ignored -> new Accumulator())
                        .add(result);
            }
        }
        Map<String, Evidence> summarized = new LinkedHashMap<>();
        byGeometry.forEach((geometry, values) -> summarized.put(geometry, values.finish()));
        return Map.copyOf(summarized);
    }


    public static String geometryKey(KoilBenchmarkResult result) {
        if (result == null || result.candidate() == null) return "";
        return result.candidate().geometryKey() + "|statePrecision=" + KoilStatePrecisionEvidence.fromResult(result);
    }

    public record Evidence(
            int successfulSamples,
            int failedSamples,
            double successRate,
            Double medianPromptTokensPerSecond,
            Double medianGenerationTokensPerSecond,
            Double medianColdTtftMillis,
            double confidence
    ) {
        public Evidence {
            successfulSamples = Math.max(0, successfulSamples);
            failedSamples = Math.max(0, failedSamples);
            successRate = clamp(successRate);
            confidence = clamp(confidence);
        }

        public int totalSamples() {
            return successfulSamples + failedSamples;
        }
    }

    private static final class Accumulator {
        private int success;
        private int failed;
        private final List<Double> prompt = new ArrayList<>();
        private final List<Double> decode = new ArrayList<>();
        private final List<Double> ttft = new ArrayList<>();

        private void add(KoilBenchmarkResult result) {
            if (!result.validMeasurement()) {
                failed++;
                return;
            }
            success++;
            addFinite(prompt, result.promptTokensPerSecond());
            addFinite(decode, result.generationTokensPerSecond());
            addFinite(ttft, result.coldTimeToFirstTokenMillis());
        }

        private Evidence finish() {
            int total = success + failed;
            double successRate = total <= 0 ? 0.0D : (double) success / (double) total;
            // Require repeated success before historical measurements become influential. Four
            // successful samples reaches full sample confidence; failures reduce it proportionally.
            double sampleConfidence = Math.min(1.0D, success / 4.0D);
            double confidence = sampleConfidence * successRate;
            return new Evidence(success, failed, successRate,
                    median(prompt), median(decode), median(ttft), confidence);
        }
    }

    private static void addFinite(List<Double> values, Double value) {
        if (value != null && Double.isFinite(value) && value > 0.0D) values.add(value);
    }

    private static Double median(List<Double> values) {
        if (values == null || values.isEmpty()) return null;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 1) return sorted.get(middle);
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0D;
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) return 0.0D;
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
