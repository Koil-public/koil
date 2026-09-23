package com.spirit.koil.api.model.runtime.universal;

/** Dependency-light proof that measured tuning evidence cannot cross state-precision regimes. */
public final class KoilStatePrecisionTuningIsolationProof {
    private KoilStatePrecisionTuningIsolationProof() {}

    public static void main(String[] args) {
        KoilTuningKey fp16 = new KoilTuningKey(
                "hw", "llama_cpp", "model", "lfm2", "gguf", "q4_k_m",
                "ctx<=8k", "k=fp16,v=fp16", KoilRuntimeBackend.VULKAN, "runtime");
        KoilTuningKey q8k = new KoilTuningKey(
                "hw", "llama_cpp", "model", "lfm2", "gguf", "q4_k_m",
                "ctx<=8k", "k=q8_block,v=fp16", KoilRuntimeBackend.VULKAN, "runtime");

        require(!fp16.identity().equals(q8k.identity()), "precision must change tuning identity");
        require(!fp16.matches(q8k), "FP16 evidence must not match Q8-K query");
        require(!q8k.matches(fp16), "Q8-K evidence must not match FP16 query");

        KoilTuningKey legacy = new KoilTuningKey(
                "hw", "llama_cpp", "model", "lfm2", "gguf", "q4_k_m",
                "ctx<=8k", KoilRuntimeBackend.VULKAN, "runtime");
        require(KoilStatePrecisionEvidence.LEGACY_FP16.equals(legacy.statePrecisionRegime()),
                "pre-precision constructor must migrate to FP16/FP16");
        require(legacy.matches(fp16), "legacy evidence should remain usable as FP16 evidence");

        require("k=q8_block,v=fp16".equals(KoilStatePrecisionEvidence.regime("q8_0", "f16")),
                "native q8_0/f16 must normalize to universal precision regime");

        System.out.println("Koil state precision tuning isolation proof passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
