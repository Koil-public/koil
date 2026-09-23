package com.spirit.koil.api.model.provider.llamacpp;

/** Dependency-light proof that native MAX calibration never crosses state-precision regimes. */
public final class LlamaCppMaxTuningPrecisionProof {
    private LlamaCppMaxTuningPrecisionProof() {}

    public static void main(String[] args) {
        LlamaCppConfiguration configuration = new LlamaCppConfiguration(
                false,
                null,
                java.nio.file.Path.of("model-a.gguf"),
                "model-a",
                131_072,
                "127.0.0.1",
                0,
                "",
                java.time.Duration.ofSeconds(1),
                java.time.Duration.ofSeconds(1)
        );
        String fp16 = LlamaCppMaxTuningStore.identity(
                configuration, "Vulkan0", "AMD GPU", "ctx<=128k", "k=fp16,v=fp16");
        String q8 = LlamaCppMaxTuningStore.identity(
                configuration, "Vulkan0", "AMD GPU", "ctx<=128k", "k=q8_block,v=fp16");
        require(!fp16.equals(q8), "MAX identity must include state precision regime");

        var profile = new LlamaCppMaxTuningStore.TunedProfile(
                q8, java.time.Instant.EPOCH, "model-a", "model-id", "runtime-id",
                "Vulkan0", "AMD GPU", "ctx<=128k", "k=q8_block,v=fp16",
                LlamaCppMaxRuntimeProfile.forcedFallback(), null, 1.0D, 1);
        require("k=q8_block,v=fp16".equals(profile.statePrecisionRegime()),
                "stored profile must retain measured state precision");
        System.out.println("llama.cpp MAX state precision isolation proof passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
