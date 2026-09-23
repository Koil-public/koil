package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.ModelPerformanceBenchmarkResult;

import java.util.List;

/** Dependency-light proof for pressure-triggered MAX winner fallback selection. */
public final class LlamaCppMaxActivationFallbackPolicyProof {
    private LlamaCppMaxActivationFallbackPolicyProof() {}

    public static void main(String[] args) {
        if (!LlamaCppMaxActivationFallbackPolicy.isMemoryPressureFailure(
                new IllegalStateException("Koil stopped llama.cpp accelerator startup to protect system/UMA memory: critical=true | available_mib=809 | hard_floor_mib=1024"))) {
            throw new AssertionError("UMA pressure failure was not classified");
        }
        if (LlamaCppMaxActivationFallbackPolicy.isMemoryPressureFailure(
                new IllegalStateException("tokenizer failed to load"))) {
            throw new AssertionError("unrelated startup failure was misclassified as memory pressure");
        }
        IllegalStateException degraded = new IllegalStateException(
                "Max performance candidate expected gpu-fit | threads=8/8 | poll=100/1 | batch=1024/256, "
                        + "but llama.cpp reported runtime placement=cpu, GPU layers=0/15. "
                        + "Koil rejected the mismatched candidate instead of benchmarking the wrong configuration.");
        if (!LlamaCppMaxActivationFallbackPolicy.isAcceleratorDegradedToCpuFailure(degraded)) {
            throw new AssertionError("CPU-degraded accelerator activation was not classified");
        }
        if (!LlamaCppMaxActivationFallbackPolicy.allowsMeasuredCpuFallback(degraded)) {
            throw new AssertionError("CPU-degraded accelerator activation did not allow measured CPU fallback");
        }
        IllegalStateException retrySigkill = new IllegalStateException("llama.cpp exited with code 137 (SIGKILL)");
        retrySigkill.addSuppressed(degraded);
        if (!LlamaCppMaxActivationFallbackPolicy.allowsMeasuredCpuFallback(retrySigkill)) {
            throw new AssertionError("suppressed original accelerator degradation was lost after retry SIGKILL");
        }
        if (LlamaCppMaxActivationFallbackPolicy.allowsMeasuredCpuFallback(
                new IllegalStateException("MAX hybrid candidate layer mismatch: expected=14, actual=7"))) {
            throw new AssertionError("non-zero placement mismatch must not be hidden by CPU fallback");
        }

        ModelPerformanceBenchmarkResult benchmark = new ModelPerformanceBenchmarkResult(
                128, 64, 800.0, 45.0, 160.0, 1400.0, 210.0, 1800L, "ok");
        LlamaCppMaxTuningResult.CandidateResult slowerCpu = new LlamaCppMaxTuningResult.CandidateResult(
                new LlamaCppMaxRuntimeProfile(LlamaCppMaxRuntimeProfile.Placement.CPU, 0, 4, 8, 50, 1, 1024, 256, "cpu-slow"),
                benchmark, 5.0);
        LlamaCppMaxTuningResult.CandidateResult fasterCpu = new LlamaCppMaxTuningResult.CandidateResult(
                new LlamaCppMaxRuntimeProfile(LlamaCppMaxRuntimeProfile.Placement.CPU, 0, 8, 8, 50, 1, 2048, 512, "cpu-fast"),
                benchmark, 9.0);
        LlamaCppMaxTuningResult.CandidateResult gpu = new LlamaCppMaxTuningResult.CandidateResult(
                new LlamaCppMaxRuntimeProfile(LlamaCppMaxRuntimeProfile.Placement.GPU, 4096, 8, 8, 50, 1, 2048, 512, "gpu-fit"),
                benchmark, 20.0);

        var selected = LlamaCppMaxActivationFallbackPolicy.bestMeasuredCpu(List.of(slowerCpu, gpu, fasterCpu))
                .orElseThrow(() -> new AssertionError("CPU fallback was not selected"));
        if (!"cpu-fast".equals(selected.profile().label())) {
            throw new AssertionError("highest-scoring measured CPU fallback was not selected");
        }
        System.out.println("llama.cpp MAX activation fallback policy proof passed");
    }
}
