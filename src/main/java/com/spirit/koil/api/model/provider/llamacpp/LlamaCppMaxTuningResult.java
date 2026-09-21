package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.ModelPerformanceBenchmarkResult;

import java.util.List;

/** Completed MAX autotune sweep and its winning measured profile. */
public record LlamaCppMaxTuningResult(
        LlamaCppMaxTuningStore.TunedProfile winner,
        List<CandidateResult> candidates,
        int failedCandidates
) {
    public LlamaCppMaxTuningResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        failedCandidates = Math.max(0, failedCandidates);
    }

    public record CandidateResult(
            LlamaCppMaxRuntimeProfile profile,
            ModelPerformanceBenchmarkResult benchmark,
            double score
    ) {
        public CandidateResult {
            score = Double.isFinite(score) ? Math.max(0.0D, score) : 0.0D;
        }
    }
}
