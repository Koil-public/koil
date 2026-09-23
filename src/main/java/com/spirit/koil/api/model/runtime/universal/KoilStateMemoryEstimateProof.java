package com.spirit.koil.api.model.runtime.universal;

import java.util.Map;

public final class KoilStateMemoryEstimateProof {
    private static final long MIB = 1024L * 1024L;

    private KoilStateMemoryEstimateProof() {}

    public static void main(String[] args) {
        // Hybrid layout: six attention layers and ten non-attention layers.
        Map<String, String> lfmLike = Map.of(
                "lfm2.block_count", "16",
                "lfm2.embedding_length", "1024",
                "lfm2.attention.head_count", "16,0,0,16,0,0,16,0,0,16,0,0,16,0,0,16",
                "lfm2.attention.head_count_kv", "8,0,0,8,0,0,8,0,0,8,0,0,8,0,0,8"
        );
        KoilStateMemoryEstimate estimate = KoilStateMemoryEstimate.fromMetadata(lfmLike, 8192);
        require(estimate.known(), "hybrid per-layer geometry should be estimable");
        require(estimate.confidence() == KoilStateMemoryEstimate.Confidence.HIGH,
                "complete per-layer KV metadata should be high confidence");
        require(estimate.fullPrecisionBytes() == 96L * MIB,
                "six attention layers at 8K should estimate 96 MiB FP16 KV for this geometry");
        require(estimate.q8KeySavingsBytes() > 20L * MIB && estimate.q8KeySavingsBytes() < 24L * MIB,
                "Q8 K savings should reflect q8_0 block overhead rather than idealized 50% storage");

        KoilMemoryPressureSnapshot constrained = KoilMemoryBudgetPlanner.from(
                16L * 1024L * MIB, 1400L * MIB, KoilMemoryPressureSnapshot.Phase.LAUNCH);
        KoilStatePrecisionDecision decision = KoilStatePrecisionPolicy.select(
                constrained, 8192, KoilRuntimeBackend.VULKAN, estimate);
        require(decision.keyPrecision() == KoilStatePrecision.FP16,
                "small projected savings should not quantize K merely because host pressure is constrained");

        Map<String, String> large = Map.of(
                "large.block_count", "48",
                "large.embedding_length", "4096",
                "large.attention.head_count", "32",
                "large.attention.head_count_kv", "8"
        );
        KoilStateMemoryEstimate largeEstimate = KoilStateMemoryEstimate.fromMetadata(large, 32768);
        require(largeEstimate.q8KeySavingsBytes() >= 256L * MIB,
                "large long-context model should expose material K-state savings");
        KoilStatePrecisionDecision largeDecision = KoilStatePrecisionPolicy.select(
                constrained, 32768, KoilRuntimeBackend.VULKAN, largeEstimate);
        require(largeDecision.keyPrecision() == KoilStatePrecision.Q8_BLOCK,
                "material projected savings under constrained launch memory should quantize K");

        System.out.println("Koil state memory estimate proof passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
