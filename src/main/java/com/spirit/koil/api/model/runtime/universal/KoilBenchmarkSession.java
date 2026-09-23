package com.spirit.koil.api.model.runtime.universal;

import java.time.Instant;
import java.util.List;

/** Immutable record of one bounded universal autotuning sweep. */
public record KoilBenchmarkSession(
        String id,
        Instant startedAt,
        Instant completedAt,
        List<KoilBenchmarkResult> results,
        List<KoilCandidateScorer.ScoredResult> scored,
        int failedCandidates
) {
    public KoilBenchmarkSession {
        id = id == null ? "" : id.strip();
        startedAt = startedAt == null ? Instant.EPOCH : startedAt;
        completedAt = completedAt == null ? Instant.EPOCH : completedAt;
        results = results == null ? List.of() : List.copyOf(results);
        scored = scored == null ? List.of() : List.copyOf(scored);
        failedCandidates = Math.max(0, failedCandidates);
    }

    public KoilCandidateScorer.ScoredResult winner() {
        return scored.isEmpty() ? null : scored.get(0);
    }
}
