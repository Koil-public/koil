package com.spirit.koil.api.model.provider.llamacpp;

/** Dependency-free proof for the exact-hybrid preflight math. */
public final class LlamaCppComputeSafetyPolicyProof {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private LlamaCppComputeSafetyPolicyProof() {
    }

    public static void main(String[] args) {
        LlamaCppComputeSafetyPolicy.Assessment small = LlamaCppComputeSafetyPolicy.assessKnownGeometry(
                4L * GIB, 25, 4, 6L * GIB, 16L * GIB);
        require(small.allowed(), "Small hybrid split should fit within reserved UMA headroom");
        require(small.requestedGpuLayers() == 4, "Assessment lost the requested layer count");

        LlamaCppComputeSafetyPolicy.Assessment large = LlamaCppComputeSafetyPolicy.assessKnownGeometry(
                4L * GIB, 25, 16, 4L * GIB, 16L * GIB);
        require(!large.allowed(), "Large hybrid split should be rejected before process launch");
        require(large.recommendedMaximumLayers() >= 0 && large.recommendedMaximumLayers() < 16,
                "Rejected split did not expose a smaller conservative layer recommendation");

        // Steam Deck regression: r26 used a hard 4 GiB reserve on UMA systems.
        // With ~3.7 GiB MemAvailable this made usable memory zero and rejected
        // even a one-layer split. The exact hybrid path already disables KV/op
        // offload, so a small layer allocation only needs to preserve Koil's
        // actual 2 GiB accelerator headroom.
        long deckAvailable = 3700L * MIB;
        long smallModel = 190L * MIB;
        for (int layers : new int[]{1, 2, 4}) {
            LlamaCppComputeSafetyPolicy.Assessment deck = LlamaCppComputeSafetyPolicy.assessKnownGeometry(
                    smallModel, 15, layers, deckAvailable, 16L * GIB);
            require(deck.allowed(), "Steam Deck should admit safe " + layers + "-layer hybrid split: " + deck.summary());
            require(deck.reservedMemoryBytes() == LlamaCppComputeArguments.acceleratorHeadroomMiB() * MIB,
                    "Preflight reserve must match the configured accelerator headroom");
            require(deck.summary().contains("requested_gpu_layers=" + layers),
                    "Diagnostic did not expose requested GPU layers");
            require(deck.summary().contains("model_layers=15"),
                    "Diagnostic did not expose total model layers separately");
        }

        LlamaCppComputeSafetyPolicy.Assessment lowHeadroom = LlamaCppComputeSafetyPolicy.assessKnownGeometry(
                4L * GIB, 25, 8, 2500L * MIB, 16L * GIB);
        require(!lowHeadroom.allowed(), "A split that violates the real reserved headroom must still be rejected");

        LlamaCppComputeSafetyPolicy.Assessment unknown = LlamaCppComputeSafetyPolicy.assessKnownGeometry(
                0L, -1, 8, 6L * GIB, 16L * GIB);
        require(unknown.allowed(), "Unknown artifact geometry should defer to runtime fit/canary instead of inventing a veto");

        System.out.println("llama.cpp compute safety policy proof passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
