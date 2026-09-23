package com.spirit.koil.api.model.runtime.universal;

import java.util.concurrent.CompletableFuture;

/** Native execution boundary used by the universal autotuner. */
public interface KoilBenchmarkAdapter {
    String adapterId();

    CompletableFuture<KoilBenchmarkResult> benchmark(
            KoilBenchmarkCandidate candidate,
            KoilBenchmarkWorkload workload
    );
}
