package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilStateMemoryEstimate;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecision;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecisionDecision;

/** Proves that the benchmark override is exact, process-local and reversible. */
public final class LlamaCppBenchmarkStatePrecisionOverrideProof {
    private LlamaCppBenchmarkStatePrecisionOverrideProof() {}

    public static void main(String[] args) {
        KoilStatePrecisionDecision policy = new KoilStatePrecisionDecision(
                KoilStatePrecision.FP16,
                KoilStatePrecision.FP16,
                false,
                1.0D,
                1_500L * 1024L * 1024L,
                1_500L * 1024L * 1024L,
                351L * 1024L * 1024L,
                KoilStateMemoryEstimate.Confidence.HIGH,
                "policy"
        );

        require(LlamaCppBenchmarkStatePrecisionOverride.apply(policy).keyPrecision() == KoilStatePrecision.FP16,
                "no override must preserve policy precision");

        LlamaCppBenchmarkStatePrecisionOverride.set("k=q8_block,v=fp16");
        KoilStatePrecisionDecision forced = LlamaCppBenchmarkStatePrecisionOverride.apply(policy);
        require(forced.keyPrecision() == KoilStatePrecision.Q8_BLOCK, "Q8 override must force K state");
        require(forced.valuePrecision() == KoilStatePrecision.FP16, "Q8 override must preserve FP16 V state");
        require(forced.estimatedSavingsBytes() == policy.estimatedSavingsBytes(),
                "override must retain projected K-state savings evidence");

        LlamaCppBenchmarkStatePrecisionOverride.clear();
        require(LlamaCppBenchmarkStatePrecisionOverride.current().isEmpty(), "override must clear after launch");
        System.out.println("llama.cpp benchmark state precision override proof passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
