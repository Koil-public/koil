package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactFormat;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency-light executable proof for precision-aware prelaunch tuning lookup. */
public final class KoilStatePrecisionRegimePredictorProof {
    private static final long GIB = 1024L * 1024L * 1024L;

    private KoilStatePrecisionRegimePredictorProof() {}

    public static void main(String[] args) {
        Map<String, String> hybrid = Map.of(
                "lfm2.block_count", "16",
                "lfm2.embedding_length", "2048",
                "lfm2.attention.head_count", "16",
                "lfm2.attention.head_count_kv", "[8,0,0,8,0,0,8,0,0,8,0,0,8,0,0,8]"
        );
        KoilModelProfile smallHybrid = new KoilModelProfile(
                true, ModelArtifactFormat.GGUF_FILE, "lfm2-small", "lfm2", null, "", 131072,
                0L, 0, 0, Set.of(), hybrid, "proof");
        KoilHardwareProfile hardware = new KoilHardwareProfile(
                "proof", "linux", "amd64", 8, 4, 16L * GIB, 0L,
                Set.of(), List.of(), null);

        KoilStatePrecisionRegimePredictor.Prediction small = KoilStatePrecisionRegimePredictor.predict(
                smallHybrid, hardware, 8192, 2L * GIB, KoilRuntimeBackend.VULKAN);
        require(KoilStatePrecisionEvidence.LEGACY_FP16.equals(small.regime()),
                "small hybrid 8K model should remain FP16");

        Map<String, String> large = Map.of(
                "test.block_count", "32",
                "test.embedding_length", "4096",
                "test.attention.head_count", "32",
                "test.attention.head_count_kv", "8"
        );
        KoilModelProfile largeModel = new KoilModelProfile(
                true, ModelArtifactFormat.GGUF_FILE, "large", "test", null, "", 131072,
                0L, 0, 0, Set.of(), large, "proof");
        KoilStatePrecisionRegimePredictor.Prediction constrained = KoilStatePrecisionRegimePredictor.predict(
                largeModel, hardware, 32768, 1024L * 1024L * 1024L, KoilRuntimeBackend.VULKAN);
        require("k=q8_block,v=fp16".equals(constrained.regime()),
                "material long-context K savings should predict q8_block/fp16");

        KoilTuningKey fp16 = new KoilTuningKey("hw", "llama_cpp", "m", "a", "gguf", "q4", "ctx<=8k",
                small.regime(), KoilRuntimeBackend.VULKAN, "r");
        KoilTuningKey q8 = new KoilTuningKey("hw", "llama_cpp", "m", "a", "gguf", "q4", "ctx<=8k",
                constrained.regime(), KoilRuntimeBackend.VULKAN, "r");
        require(!fp16.matches(q8), "predicted precision regimes must remain tuning-isolated");
        require(!fp16.identity().equals(q8.identity()), "precision regimes must produce distinct tuning identities");

        System.out.println("Koil state precision regime predictor proof passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
