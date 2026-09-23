package com.spirit.koil.api.model.runtime.universal;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Dependency-light proof for bounded resource-headroom scoring. */
public final class KoilResourceAwareScoringProof {
    private KoilResourceAwareScoringProof() {}

    public static void main(String[] args) {
        KoilBenchmarkWorkload interactive = new KoilBenchmarkWorkload(
                "resource scoring proof", 96, java.time.Duration.ofMinutes(1),
                "interactive", KoilBenchmarkWorkload.Kind.INTERACTIVE);

        KoilBenchmarkResult fastTight = result("fast-tight", 2600.0D, 170.0D, 280.0D, 0.05D);
        KoilBenchmarkResult closeSafe = result("close-safe", 2530.0D, 168.0D, 288.0D, 1.0D);
        var closeScores = KoilCandidateScorer.score(List.of(fastTight, closeSafe), interactive);
        require(closeScores.get(0).result().candidate().id().equals("close-safe"),
                "near-equal performance should prefer materially safer headroom");
        require(closeScores.stream().filter(v -> v.result().candidate().id().equals("fast-tight"))
                        .findFirst().orElseThrow().resourcePenalty() > 0.10D,
                "tight candidate should receive a bounded resource penalty");

        KoilBenchmarkResult muchFasterTight = result("much-faster-tight", 3400.0D, 220.0D, 220.0D, 0.05D);
        var clearScores = KoilCandidateScorer.score(List.of(muchFasterTight, closeSafe), interactive);
        require(clearScores.get(0).result().candidate().id().equals("much-faster-tight"),
                "bounded resource pressure must not erase a decisive performance lead");

        KoilBenchmarkResult unknown = result("unknown", 2530.0D, 168.0D, 288.0D, null);
        var unknownScore = KoilCandidateScorer.score(List.of(unknown), interactive).get(0);
        require(Math.abs(unknownScore.resourceHeadroomScore() - 1.0D) < 0.000001D,
                "unknown resource telemetry must remain neutral");
        require(Math.abs(unknownScore.resourcePenalty()) < 0.000001D,
                "unknown resource telemetry must not invent a penalty");

        System.out.println("Koil resource-aware scoring proof passed");
    }

    private static KoilBenchmarkResult result(String id, double prompt, double decode, double ttft, Double headroom) {
        KoilExecutionSettings settings = new KoilExecutionSettings(
                KoilPlacementPolicy.GPU, "Vulkan0", -1,
                8, 8, 1024, 256, 100, 1,
                KoilStatePlacement.ACCELERATOR, KoilOperatorPlacement.ACCELERATOR,
                KoilFeatureMode.AUTO, true, 0L);
        KoilBenchmarkCandidate candidate = new KoilBenchmarkCandidate(
                id, id, KoilRuntimeBackend.VULKAN, settings,
                KoilBenchmarkCandidate.ValidationMode.ACCELERATOR_FIT, "ctx<=2k", Map.of());
        Map<String, Double> metrics = headroom == null ? Map.of()
                : Map.of(KoilBenchmarkResourceEvidence.HEADROOM_SCORE, headroom);
        return new KoilBenchmarkResult(
                candidate, KoilBenchmarkResult.Outcome.SUCCESS,
                512, 96, prompt, decode,
                100.0D, 500.0D, ttft, null, 1000L,
                null, null, "hybrid", 14,
                KoilStatePlacement.ACCELERATOR, KoilOperatorPlacement.ACCELERATOR,
                true, "", "", Instant.now(), "proof", metrics, Map.of());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
