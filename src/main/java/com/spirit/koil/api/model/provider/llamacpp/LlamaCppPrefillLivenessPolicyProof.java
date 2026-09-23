package com.spirit.koil.api.model.provider.llamacpp;

import java.util.concurrent.TimeUnit;

/** Deterministic proof for adaptive prompt-prefill liveness budgets. */
public final class LlamaCppPrefillLivenessPolicyProof {
    private LlamaCppPrefillLivenessPolicyProof() {}

    public static void main(String[] args) {
        var small = LlamaCppPrefillLivenessPolicy.forPayloadCharacters(8_000);
        require(small.initialGraceSeconds() >= 90L, "small prompt initial grace regressed below 90s");
        require(!LlamaCppPrefillLivenessPolicy.stalled(TimeUnit.SECONDS.toNanos(31), false, small),
                "legacy 30s false-stall behavior returned");
        require(!LlamaCppPrefillLivenessPolicy.stalled(TimeUnit.SECONDS.toNanos(31), true, small),
                "progressing prefill was killed after 30s");

        var large = LlamaCppPrefillLivenessPolicy.forPayloadCharacters(80_000);
        require(large.initialGraceSeconds() > small.initialGraceSeconds(),
                "larger prompt did not receive a larger initial prefill budget");
        require(large.initialGraceSeconds() <= 240L && large.progressStallSeconds() <= 120L,
                "prefill watchdog became unbounded");
        require(LlamaCppPrefillLivenessPolicy.stalled(
                        TimeUnit.SECONDS.toNanos(large.initialGraceSeconds() + 1), false, large),
                "dead no-progress prefill was not eventually detected");
        System.out.println("llama.cpp prefill liveness policy proof passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
