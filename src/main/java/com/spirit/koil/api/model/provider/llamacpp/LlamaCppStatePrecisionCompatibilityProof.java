package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilStateMemoryEstimate;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecision;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecisionDecision;

public final class LlamaCppStatePrecisionCompatibilityProof {
    private LlamaCppStatePrecisionCompatibilityProof() {}

    public static void main(String[] args) {
        KoilStatePrecisionDecision fp16 = new KoilStatePrecisionDecision(
                KoilStatePrecision.FP16, KoilStatePrecision.FP16, false, 1.0,
                0L, 0L, 0L, KoilStateMemoryEstimate.Confidence.UNKNOWN, "proof");
        KoilStatePrecisionDecision q8 = new KoilStatePrecisionDecision(
                KoilStatePrecision.Q8_BLOCK, KoilStatePrecision.FP16, true, 0.75,
                0L, 0L, 0L, KoilStateMemoryEstimate.Confidence.UNKNOWN, "proof");

        require(LlamaCppStatePrecisionCompatibility.matches("k=fp16,v=fp16", fp16), "FP16 should match FP16");
        require(!LlamaCppStatePrecisionCompatibility.matches("k=fp16,v=fp16", q8), "FP16 evidence must reject Q8 launch");
        require(LlamaCppStatePrecisionCompatibility.matches("k=q8_block,v=fp16", q8), "Q8 should match Q8");
        require(LlamaCppStatePrecisionCompatibility.nativeStoreEligible(fp16), "precision-aware native store may serve FP16");
        require(LlamaCppStatePrecisionCompatibility.nativeStoreEligible(q8), "precision-aware native store may serve Q8/FP16");
        System.out.println("llama.cpp state precision compatibility proof passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
