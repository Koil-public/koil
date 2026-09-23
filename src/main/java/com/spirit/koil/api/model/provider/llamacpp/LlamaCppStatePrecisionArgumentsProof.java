package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilStatePrecision;
import com.spirit.koil.api.model.runtime.universal.KoilStatePrecisionDecision;

import java.util.List;

public final class LlamaCppStatePrecisionArgumentsProof {
    private LlamaCppStatePrecisionArgumentsProof() {}

    public static void main(String[] args) {
        KoilStatePrecisionDecision pressured = new KoilStatePrecisionDecision(
                KoilStatePrecision.Q8_BLOCK, KoilStatePrecision.FP16, true, 0.75, "proof");
        List<String> mapped = LlamaCppStatePrecisionArguments.forDecision(pressured);
        require(mapped.equals(List.of("--cache-type-k", "q8_0", "--cache-type-v", "f16")),
                "Q8 K / FP16 V mapping must be exact");

        KoilStatePrecisionDecision external = new KoilStatePrecisionDecision(
                KoilStatePrecision.AUTO, KoilStatePrecision.AUTO, false, 1.0, "external");
        require(LlamaCppStatePrecisionArguments.forDecision(external).isEmpty(),
                "AUTO precision must not override an externally-owned runtime");

        System.out.println("llama.cpp state precision arguments proof passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
