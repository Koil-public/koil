package com.spirit.koil.api.model.runtime.universal;

import com.spirit.koil.api.model.catalog.ModelArtifactFormat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Standalone persistence and compatibility proof for the universal tuning database. */
public final class KoilUniversalTuningStoreProof {
    private KoilUniversalTuningStoreProof() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("koil-universal-tuning-proof");
        Path store = root.resolve("universal-tuning.json");
        KoilExecutionAdapterDescriptor adapter = new KoilExecutionAdapterDescriptor(
                "llama_cpp", "openai_tool_calls", true, Map.of());
        KoilArchitectureDescriptor architecture = new KoilArchitectureDescriptor(
                "lfm2", KoilArchitectureCategory.HYBRID_ATTENTION,
                Set.of(KoilOperatorKind.GEMM), Set.of(KoilModelStateKind.ATTENTION_KV), false, "proof");
        KoilModelProfile model = new KoilModelProfile(
                true, ModelArtifactFormat.GGUF_FILE, "proof", "lfm2", architecture, "",
                16384, 1, 0, 0, Set.of(), Map.of(), "proof");
        KoilRuntimeArtifactInventory inventory = new KoilRuntimeArtifactInventory(
                "llama.cpp-b10173", null, Set.of(KoilRuntimeBackend.CPU, KoilRuntimeBackend.VULKAN), true, "proof");
        KoilHardwareProfile hardware = new KoilHardwareProfile(
                "stable-machine", "Linux", "amd64", 8, 4, 16L << 30, 8L << 30,
                Set.of(), List.of(), inventory);
        KoilTuningKey key = KoilTuningKey.create(adapter, model, hardware, "model-a", "Q4_K_M",
                16384, KoilRuntimeBackend.VULKAN, "llama.cpp-b10173");
        KoilMeasuredTuningProfile profile = new KoilMeasuredTuningProfile(
                "legacy-winner", "llama_cpp_max_compat", Instant.now(), "llama_cpp", "model-a",
                "lfm2", "runtime-file-id", hardware.fingerprint(), KoilComputeMode.AUTOMATIC,
                KoilRuntimeBackend.VULKAN, 42.0,
                Map.of("generationTokensPerSecond", 31.5),
                Map.of("placement", "hybrid", "gpuLayers", "14", "batchSize", "2048", "ubatchSize", "512"));
        KoilUniversalTuningStore.save(store, key, profile);

        KoilTuningKey wildcardBackend = KoilTuningKey.create(adapter, model, hardware, "model-a", "Q4_K_M",
                16384, KoilRuntimeBackend.UNKNOWN, "llama.cpp-b10173");
        KoilUniversalTuningStore.StoredProfile loaded = KoilUniversalTuningStore.findBest(store, wildcardBackend)
                .orElseThrow(() -> new AssertionError("saved universal tuning profile was not found"));
        require(loaded.key().backend() == KoilRuntimeBackend.VULKAN, "backend dimension was not persisted");
        require("14".equals(loaded.profile().decisions().get("gpuLayers")), "tuned decisions were not persisted");


        KoilTuningKey cpuKey = KoilTuningKey.create(adapter, model, hardware, "model-a", "Q4_K_M",
                16384, KoilRuntimeBackend.CPU, "llama.cpp-b10173");
        KoilMeasuredTuningProfile staleCpu = new KoilMeasuredTuningProfile(
                "stale-cpu", "llama_cpp_max_compat", Instant.now().plusSeconds(1), "llama_cpp", "model-a",
                "lfm2", "runtime-file-id", hardware.fingerprint(), KoilComputeMode.AUTOMATIC,
                KoilRuntimeBackend.CPU, 1.0, Map.of(),
                Map.of("placement", "cpu", "gpuLayers", "0"));
        KoilUniversalTuningStore.save(store, cpuKey, staleCpu);
        KoilUniversalTuningStore.saveWinner(store, key, profile);
        KoilUniversalTuningStore.StoredProfile winner = KoilUniversalTuningStore.findBest(store, wildcardBackend)
                .orElseThrow(() -> new AssertionError("authoritative winner was not found"));
        require(winner.key().backend() == KoilRuntimeBackend.VULKAN,
                "saveWinner did not replace the competing CPU candidate in the same tuning family");

        KoilMeasuredTuningProfile universal = new KoilMeasuredTuningProfile(
                "universal-winner", KoilBenchmarkTuningProfile.SOURCE, Instant.now().plusSeconds(2), "llama_cpp", "model-a",
                "lfm2", "runtime-file-id", hardware.fingerprint(), KoilComputeMode.AUTOMATIC,
                KoilRuntimeBackend.VULKAN, 43.0, Map.of(),
                Map.of("placement", "hybrid", "gpuLayers", "14", "benchmarkProtocol", KoilBenchmarkTuningProfile.PROTOCOL));
        KoilUniversalTuningStore.saveWinner(store, key, universal);
        KoilUniversalTuningStore.StoredProfile universalLoaded = KoilUniversalTuningStore.findBest(store, wildcardBackend)
                .orElseThrow(() -> new AssertionError("universal winner was not found"));
        require(KoilBenchmarkTuningProfile.SOURCE.equals(universalLoaded.profile().source()),
                "adapter compatibility evidence remained authoritative after universal winner save");

        KoilTuningKey wrongQuant = KoilTuningKey.create(adapter, model, hardware, "model-a", "Q8_0",
                16384, KoilRuntimeBackend.UNKNOWN, "llama.cpp-b10173");
        require(KoilUniversalTuningStore.findBest(store, wrongQuant).isEmpty(),
                "profile leaked across quantization identities");
        KoilTuningKey wrongContext = KoilTuningKey.create(adapter, model, hardware, "model-a", "Q4_K_M",
                32768, KoilRuntimeBackend.UNKNOWN, "llama.cpp-b10173");
        require(KoilUniversalTuningStore.findBest(store, wrongContext).isEmpty(),
                "profile leaked across context regimes");
        KoilTuningKey measured2k = KoilTuningKey.createWithContextRegime(
                adapter, model, hardware, "model-a", "Q4_K_M", "ctx<=2k",
                KoilRuntimeBackend.VULKAN, "llama.cpp-b10173");
        require("ctx<=2k".equals(measured2k.contextRegime()),
                "explicit measured context regime was not preserved");
        require(!measured2k.matches(wildcardBackend),
                "2k benchmark evidence must not match a 16k tuning query");

        KoilTuningKey wrongRuntime = KoilTuningKey.create(adapter, model, hardware, "model-a", "Q4_K_M",
                16384, KoilRuntimeBackend.UNKNOWN, "llama.cpp-b99999");
        require(KoilUniversalTuningStore.findBest(store, wrongRuntime).isEmpty(),
                "profile leaked across runtime revisions");

        System.out.println("Koil universal tuning store proof passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
