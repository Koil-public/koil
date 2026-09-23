package com.spirit.koil.api.model.provider.llamacpp;

import java.nio.file.Files;
import java.nio.file.Path;

/** Deterministic proof for learned hybrid placement safety/integrity state. */
public final class LlamaCppComputeStabilityStoreProof {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private LlamaCppComputeStabilityStoreProof() {
    }

    public static void main(String[] args) throws Exception {
        requireGeometry(16L * GIB, 2, 1024, 256);
        requireGeometry(16L * GIB, 4, 512, 128);
        requireGeometry(16L * GIB, 8, 256, 64);
        requireGeometry(32L * GIB, 8, 2048, 512);

        Path model = Files.createTempFile("koil-stability-proof-", ".gguf");
        Files.write(model, new byte[128]);
        String device = "VulkanProof-" + System.nanoTime();

        LlamaCppComputeStabilityStore.recordStable(
                model, device, 2, 1500L * MIB, "two layers healthy");
        LlamaCppComputeStabilityStore.Envelope stable =
                LlamaCppComputeStabilityStore.envelope(model, device);
        require(stable.highestStableLayers() == 2, "stable layer evidence was not persisted");

        LlamaCppComputeStabilityStore.recordPressureUnsafe(
                model, device, 4, 3700L * MIB, 650L * MIB, "pressure proof");
        require(LlamaCppComputeStabilityStore.blockedAtOrAbove(
                        model, device, 3700L * MIB) == 4,
                "same-headroom pressure boundary was not enforced");
        require(LlamaCppComputeStabilityStore.recommendedStableMaximum(
                        model, device, 3700L * MIB) == 2,
                "known stable maximum should be preferred below pressure boundary");
        require(LlamaCppComputeStabilityStore.blockedAtOrAbove(
                        model, device, 4700L * MIB) == 0,
                "substantially increased headroom should permit a bounded retest");

        LlamaCppComputeStabilityStore.recordIntegrityUnsafe(
                model, device, 3, "integrity proof");
        require(LlamaCppComputeStabilityStore.exactIntegrityBlocked(model, device, 3),
                "exact integrity failure was not remembered");
        require(!LlamaCppComputeStabilityStore.exactIntegrityBlocked(model, device, 2),
                "integrity failure incorrectly contaminated another layer split");

        LlamaCppComputeStabilityStore.recordStable(
                model, device, 3, 1400L * MIB, "three layers later verified");
        require(!LlamaCppComputeStabilityStore.exactIntegrityBlocked(model, device, 3),
                "successful exact placement did not clear old integrity failure");
        require(LlamaCppComputeStabilityStore.envelope(model, device).highestStableLayers() == 3,
                "higher stable placement was not learned");

        Files.deleteIfExists(model);
        System.out.println("llama.cpp compute stability store proof passed.");
    }

    private static void requireGeometry(long memory, int layers, int batch, int ubatch) {
        int[] actual = LlamaCppComputeStabilityStore.conservativeHybridBatchGeometry(memory, layers);
        require(actual[0] == batch && actual[1] == ubatch,
                "unexpected hybrid batch geometry for " + layers + " layers: "
                        + actual[0] + "/" + actual[1]);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
