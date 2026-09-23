package com.spirit.koil.api.model.runtime.universal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Dependency-free contract proof for universal candidate generation, validation, scoring and orchestration. */
public final class KoilUniversalBenchmarkProof {
    private KoilUniversalBenchmarkProof() {}

    public static void main(String[] args) {
        List<KoilBenchmarkCandidate> candidates = KoilCandidateGenerator.baseline(
                KoilRuntimeBackend.VULKAN, "Vulkan0", 15, 0, 4, 8, "ctx<=16k",
                layers -> layers <= 2 ? new int[]{1024, 256} : new int[]{256, 64});
        require(candidates.stream().anyMatch(value -> value.settings().placement() == KoilPlacementPolicy.CPU),
                "CPU baseline missing");
        KoilBenchmarkCandidate gpuFit = candidates.stream()
                .filter(value -> value.settings().placement() == KoilPlacementPolicy.GPU).findFirst().orElseThrow();
        require(gpuFit.acceptsObservedPlacement("hybrid", 14), "GPU-fit must accept measured hybrid offload");
        require(!gpuFit.acceptsObservedPlacement("cpu", 0), "GPU-fit must reject CPU fallback");

        KoilBenchmarkAdapter adapter = new KoilBenchmarkAdapter() {
            @Override public String adapterId() { return "proof"; }
            @Override public CompletableFuture<KoilBenchmarkResult> benchmark(KoilBenchmarkCandidate candidate, KoilBenchmarkWorkload workload) {
                double decode = candidate.settings().placement() == KoilPlacementPolicy.GPU ? 170.0D : 90.0D;
                double prompt = candidate.settings().placement() == KoilPlacementPolicy.GPU ? 2900.0D : 1300.0D;
                return CompletableFuture.completedFuture(new KoilBenchmarkResult(
                        candidate, KoilBenchmarkResult.Outcome.SUCCESS, 512, 96,
                        prompt, decode, 175.0D, 565.0D, 240.0D, null, 900L,
                        null, null,
                        candidate.settings().placement() == KoilPlacementPolicy.GPU ? "hybrid" : "cpu",
                        candidate.settings().placement() == KoilPlacementPolicy.GPU ? 14 : 0,
                        candidate.settings().statePlacement(), candidate.settings().operatorPlacement(),
                        true, "", "proof", Instant.now(), "proof-runtime", Map.of(), Map.of()));
            }
        };
        KoilBenchmarkSession session = KoilUniversalAutotuner.run(
                adapter,
                List.of(candidates.get(0), gpuFit),
                new KoilBenchmarkWorkload("proof", 96, Duration.ofSeconds(5), "proof")
        ).join();
        require(session.results().size() == 2, "autotuner did not execute every candidate");
        require(session.winner() != null, "winner missing");
        require(session.winner().result().candidate().settings().placement() == KoilPlacementPolicy.GPU,
                "scorer did not select faster safe candidate");
        KoilMeasuredTuningProfile tuning = KoilBenchmarkTuningProfile.fromWinner(
                "proof-measurement", session.winner(),
                new KoilExecutionAdapterDescriptor("proof", "none", true, Map.of()),
                null, "proof-model", "proof-runtime", "proof-hardware");
        require(KoilBenchmarkTuningProfile.SOURCE.equals(tuning.source()), "universal tuning source was not owned by Koil");
        require(KoilBenchmarkTuningProfile.PROTOCOL.equals(tuning.decisions().get("benchmarkProtocol")),
                "benchmark protocol provenance missing");
        require("proof".equals(tuning.decisions().get("benchmarkAdapter")), "benchmark adapter provenance missing");
        require("hybrid".equals(tuning.decisions().get("actualPlacement")), "actual placement was not preserved");
        require(KoilBenchmarkFailureClassifier.classify(new IllegalStateException("Vulkan allocation failed: out of memory"))
                        == KoilBenchmarkFailureClassifier.Kind.OUT_OF_MEMORY,
                "OOM failure classification regressed");
        require(KoilBenchmarkFailureClassifier.classify(new java.util.concurrent.TimeoutException("benchmark timed out"))
                        == KoilBenchmarkFailureClassifier.Kind.TIMEOUT,
                "timeout failure classification regressed");
        System.out.println("KoilUniversalBenchmarkProof: OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
