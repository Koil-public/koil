package com.spirit.koil.api.model.runtime.universal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Dependency-light executable proof for historical confidence scoring. */
public final class KoilHistoricalBenchmarkEvidenceProof {
    private KoilHistoricalBenchmarkEvidenceProof() {}

    public static void main(String[] args) {
        KoilBenchmarkCandidate stable = candidate("stable", 1024, 256);
        KoilBenchmarkCandidate noisy = candidate("noisy", 2048, 512);
        KoilTuningKey key = new KoilTuningKey("hw", "llama_cpp", "model", "lfm2", "gguf", "q4", "ctx<=2k", KoilRuntimeBackend.VULKAN, "runtime");

        KoilBenchmarkHistoryStore.StoredSession first = stored(key, List.of(
                success(stable, 2600, 170, 310),
                success(noisy, 2700, 174, 300)));
        KoilBenchmarkHistoryStore.StoredSession second = stored(key, List.of(
                success(stable, 2620, 171, 305),
                failed(noisy)));
        KoilBenchmarkHistoryStore.StoredSession third = stored(key, List.of(
                success(stable, 2610, 169, 308),
                failed(noisy)));

        Map<String, KoilHistoricalBenchmarkEvidence.Evidence> summary =
                KoilHistoricalBenchmarkEvidence.summarize(List.of(first, second, third));
        KoilHistoricalBenchmarkEvidence.Evidence stableEvidence = summary.get(stable.geometryKey());
        KoilHistoricalBenchmarkEvidence.Evidence noisyEvidence = summary.get(noisy.geometryKey());
        require(stableEvidence != null && stableEvidence.successfulSamples() == 3, "stable samples missing");
        require(stableEvidence.failedSamples() == 0, "stable failures incorrect");
        require(stableEvidence.confidence() > noisyEvidence.confidence(), "unstable candidate should have lower confidence");
        require(Math.abs(stableEvidence.medianGenerationTokensPerSecond() - 170.0D) < 0.001D, "median decode incorrect");

        List<KoilBenchmarkResult> fresh = List.of(
                success(stable, 2600, 170, 310),
                success(noisy, 2602, 170.1, 309));
        List<KoilCandidateScorer.ScoredResult> baseline = KoilCandidateScorer.score(fresh);
        List<KoilCandidateScorer.ScoredResult> scored = KoilCandidateScorer.score(
                fresh, List.of(first, second, third));
        require(scored.size() == 2, "expected two scored candidates");
        double baselineGap = scoreFor(baseline, noisy) - scoreFor(baseline, stable);
        double historicalGap = scoreFor(scored, noisy) - scoreFor(scored, stable);
        require(historicalGap < baselineGap,
                "repeated stable history should reduce a noisy candidate's advantage");
        System.out.println("Koil historical benchmark evidence proof passed");
    }

    private static KoilBenchmarkHistoryStore.StoredSession stored(KoilTuningKey key, List<KoilBenchmarkResult> results) {
        KoilBenchmarkSession session = new KoilBenchmarkSession("session", Instant.now(), Instant.now(), results,
                KoilCandidateScorer.score(results), 0);
        return new KoilBenchmarkHistoryStore.StoredSession(key, session,
                new KoilBenchmarkWorkload("benchmark", 96, java.time.Duration.ofMinutes(1), "max-autotune"), "llama_cpp");
    }

    private static KoilBenchmarkCandidate candidate(String id, int batch, int ubatch) {
        KoilExecutionSettings settings = new KoilExecutionSettings(
                KoilPlacementPolicy.GPU, "Vulkan0", -1, 8, 8, batch, ubatch, 50, 1,
                KoilStatePlacement.ACCELERATOR, KoilOperatorPlacement.ACCELERATOR,
                KoilFeatureMode.AUTO, true, 0L);
        return new KoilBenchmarkCandidate(id, id, KoilRuntimeBackend.VULKAN, settings,
                KoilBenchmarkCandidate.ValidationMode.ACCELERATOR_FIT, "ctx<=2k", Map.of());
    }

    private static KoilBenchmarkResult success(KoilBenchmarkCandidate candidate, double prompt, double decode, double ttft) {
        return new KoilBenchmarkResult(candidate, KoilBenchmarkResult.Outcome.SUCCESS,
                1000, 96, prompt, decode, 1.0, 1.0, ttft, null, 1000L, null, null,
                "hybrid", 14, KoilStatePlacement.ACCELERATOR, KoilOperatorPlacement.ACCELERATOR,
                true, "", "", Instant.now(), "runtime", Map.of(), Map.of());
    }

    private static KoilBenchmarkResult failed(KoilBenchmarkCandidate candidate) {
        return new KoilBenchmarkResult(candidate, KoilBenchmarkResult.Outcome.FAILED,
                0, 0, null, null, null, null, null, null, null, null, null,
                "", null, KoilStatePlacement.AUTOMATIC, KoilOperatorPlacement.AUTOMATIC,
                false, "out_of_memory", "pressure", Instant.now(), "runtime", Map.of(), Map.of());
    }

    private static double scoreFor(List<KoilCandidateScorer.ScoredResult> scores, KoilBenchmarkCandidate candidate) {
        return scores.stream()
                .filter(value -> value.result().candidate().geometryKey().equals(candidate.geometryKey()))
                .mapToDouble(KoilCandidateScorer.ScoredResult::score)
                .findFirst().orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
