package com.spirit.koil.api.model.runtime.universal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Universal sequential benchmark orchestrator. Candidate execution stays inside the adapter. */
public final class KoilUniversalAutotuner {
    private KoilUniversalAutotuner() {}

    public static CompletableFuture<KoilBenchmarkSession> run(
            KoilBenchmarkAdapter adapter,
            List<KoilBenchmarkCandidate> candidates,
            KoilBenchmarkWorkload workload
    ) {
        if (adapter == null) return CompletableFuture.failedFuture(new IllegalArgumentException("adapter is required"));
        List<KoilBenchmarkCandidate> safeCandidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (safeCandidates.isEmpty()) return CompletableFuture.failedFuture(new IllegalArgumentException("at least one candidate is required"));
        KoilBenchmarkWorkload safeWorkload = workload == null
                ? new KoilBenchmarkWorkload("Koil benchmark", 64, java.time.Duration.ofMinutes(2), "benchmark")
                : workload;
        Instant started = Instant.now();
        List<KoilBenchmarkResult> results = new ArrayList<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (KoilBenchmarkCandidate candidate : safeCandidates) {
            chain = chain.thenCompose(ignored -> adapter.benchmark(candidate, safeWorkload)
                    .handle((result, failure) -> {
                        if (result != null) results.add(result);
                        else results.add(new KoilBenchmarkResult(candidate, KoilBenchmarkResult.Outcome.FAILED,
                                0, 0, null, null, null, null, null, null, null, null, null,
                                "", null, KoilStatePlacement.AUTOMATIC, KoilOperatorPlacement.AUTOMATIC,
                                false, KoilBenchmarkFailureClassifier.id(failure),
                                failure == null ? "benchmark failed" : safeMessage(failure), Instant.now(), "", null, null));
                        return null;
                    }));
        }
        return chain.thenApply(ignored -> {
            List<KoilCandidateScorer.ScoredResult> scored = KoilCandidateScorer.score(results, safeWorkload);
            int failed = (int) results.stream().filter(value -> value.outcome() != KoilBenchmarkResult.Outcome.SUCCESS).count();
            return new KoilBenchmarkSession(UUID.randomUUID().toString(), started, Instant.now(), results, scored, failed);
        });
    }

    private static String safeMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message.strip();
    }
}
