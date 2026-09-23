package com.spirit.koil.api.model.provider.llamacpp;

/** Dependency-light proof that adapter-private MAX calibration never crosses context regimes. */
public final class LlamaCppMaxTuningContextProof {
    private LlamaCppMaxTuningContextProof() {}

    public static void main(String[] args) {
        LlamaCppConfiguration configuration = new LlamaCppConfiguration(
                false,
                null,
                java.nio.file.Path.of("model-a.gguf"),
                "model-a",
                2_048,
                "127.0.0.1",
                0,
                "",
                java.time.Duration.ofSeconds(1),
                java.time.Duration.ofSeconds(1)
        );
        String low = LlamaCppMaxTuningStore.identity(configuration, "Vulkan0", "AMD GPU", "ctx<=2k");
        String high = LlamaCppMaxTuningStore.identity(configuration, "Vulkan0", "AMD GPU", "ctx<=16k");
        require(!low.equals(high), "MAX identity must include context regime");

        var profile = new LlamaCppMaxTuningStore.TunedProfile(
                low, java.time.Instant.EPOCH, "model-a", "model-id", "runtime-id",
                "Vulkan0", "AMD GPU", "ctx<=2k", "k=fp16,v=fp16",
                LlamaCppMaxRuntimeProfile.forcedFallback(), null, 1.0D, 1);
        require("ctx<=2k".equals(profile.contextRegime()), "stored profile must retain its measured context regime");
        System.out.println("llama.cpp MAX context isolation proof passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
