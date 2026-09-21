package com.spirit.koil.api.model;

import java.util.concurrent.CompletableFuture;

/** Optional provider capability for native, non-user-visible performance probes. */
public interface LocalModelPerformanceBenchmarkProvider {
    CompletableFuture<ModelPerformanceBenchmarkResult> benchmark(ModelPerformanceBenchmarkRequest request);
}
