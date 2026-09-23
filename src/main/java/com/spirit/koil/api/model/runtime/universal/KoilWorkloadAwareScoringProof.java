package com.spirit.koil.api.model.runtime.universal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Dependency-light proof that workload profiles change scoring priorities without bypassing safety. */
public final class KoilWorkloadAwareScoringProof {
    private KoilWorkloadAwareScoringProof() {}

    public static void main(String[] args) {
        KoilBenchmarkCandidate prefill = candidate("prefill-fast");
        KoilBenchmarkCandidate decode = candidate("decode-fast");
        KoilBenchmarkResult prefillResult = result(prefill, 3000.0, 120.0, 260.0);
        KoilBenchmarkResult decodeResult = result(decode, 2100.0, 185.0, 320.0);
        List<KoilBenchmarkResult> results = List.of(prefillResult, decodeResult);

        KoilBenchmarkWorkload prefillWorkload = new KoilBenchmarkWorkload(
                "proof", 64, Duration.ofSeconds(5), "prefill-proof", KoilBenchmarkWorkload.Kind.PREFILL_HEAVY);
        KoilBenchmarkWorkload decodeWorkload = new KoilBenchmarkWorkload(
                "proof", 384, Duration.ofSeconds(5), "decode-proof", KoilBenchmarkWorkload.Kind.DECODE_HEAVY);
        KoilBenchmarkWorkload interactive = new KoilBenchmarkWorkload(
                "proof", 96, Duration.ofSeconds(5), "interactive-proof", KoilBenchmarkWorkload.Kind.INTERACTIVE);

        require(KoilCandidateScorer.score(results, prefillWorkload).get(0).result().candidate().label().equals("prefill-fast"),
                "prefill-heavy workload must prefer stronger prompt processing");
        require(KoilCandidateScorer.score(results, decodeWorkload).get(0).result().candidate().label().equals("decode-fast"),
                "decode-heavy workload must prefer stronger token generation");
        require(interactive.scoreWeights().latency() > prefillWorkload.scoreWeights().latency(),
                "interactive workload must assign more TTFT weight than prefill-heavy workload");

        System.out.println("Koil workload-aware scoring proof passed");
    }

    private static KoilBenchmarkCandidate candidate(String label) {
        return new KoilBenchmarkCandidate(label, label, KoilRuntimeBackend.CPU,
                KoilExecutionSettings.automatic(), KoilBenchmarkCandidate.ValidationMode.EXACT,
                "ctx<=8k", Map.of());
    }

    private static KoilBenchmarkResult result(
            KoilBenchmarkCandidate candidate,
            double prompt,
            double decode,
            double ttft
    ) {
        return new KoilBenchmarkResult(candidate, KoilBenchmarkResult.Outcome.SUCCESS,
                64, 96, prompt, decode, null, null, ttft, null, null, null, null,
                "cpu", 0, KoilStatePlacement.HOST, KoilOperatorPlacement.HOST,
                true, "", "", Instant.now(), "proof", Map.of(), Map.of());
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
