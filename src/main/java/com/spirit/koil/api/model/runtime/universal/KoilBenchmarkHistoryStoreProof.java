package com.spirit.koil.api.model.runtime.universal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Dependency-light executable proof for benchmark-history persistence. */
public final class KoilBenchmarkHistoryStoreProof {
    private KoilBenchmarkHistoryStoreProof() {}

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempFile("koil-benchmark-history-proof-", ".properties");
        try {
            KoilExecutionSettings settings = new KoilExecutionSettings(
                    KoilPlacementPolicy.GPU, "Vulkan0", -1, 8, 8, 1024, 256, 50, 1,
                    KoilStatePlacement.ACCELERATOR, KoilOperatorPlacement.ACCELERATOR,
                    KoilFeatureMode.AUTO, true, 0L);
            KoilBenchmarkCandidate candidate = new KoilBenchmarkCandidate(
                    "gpu-fit-1024-256", "gpu-fit", KoilRuntimeBackend.VULKAN, settings,
                    KoilBenchmarkCandidate.ValidationMode.ACCELERATOR_FIT, "ctx<=4k", Map.of("source", "proof"));
            KoilBenchmarkResult measured = new KoilBenchmarkResult(
                    candidate, KoilBenchmarkResult.Outcome.SUCCESS, 1024, 96,
                    2618.66, 157.02, 390.0, 611.0, 395.0, null,
                    1200L, 512L * 1024L * 1024L, 128L * 1024L * 1024L,
                    "hybrid", 14, KoilStatePlacement.ACCELERATOR, KoilOperatorPlacement.ACCELERATOR,
                    true, "", "", Instant.now(), "llama.cpp-b10173",
                    Map.of("minecraftFps", 60.0), Map.of("actualGpuLayersTotal", "15"));
            KoilBenchmarkSession session = new KoilBenchmarkSession(
                    "proof-session", Instant.now().minusSeconds(2), Instant.now(),
                    List.of(measured), KoilCandidateScorer.score(List.of(measured)), 0);
            KoilTuningKey key = new KoilTuningKey(
                    "hardware", "llama_cpp", "model", "lfm2", "gguf", "q4",
                    "ctx<=4k", KoilRuntimeBackend.VULKAN, "llama.cpp-b10173");
            KoilBenchmarkWorkload workload = new KoilBenchmarkWorkload(
                    "proof prompt", 96, Duration.ofMinutes(3), "max-autotune");

            KoilBenchmarkHistoryStore.save(temp, key, session, workload, "llama_cpp");
            KoilBenchmarkHistoryStore.StoredSession loaded = KoilBenchmarkHistoryStore.latest(temp, key).orElseThrow();
            require("proof-session".equals(loaded.session().id()), "session id was not restored");
            require(loaded.session().results().size() == 1, "result count was not restored");
            KoilBenchmarkResult restored = loaded.session().results().get(0);
            require(restored.validMeasurement(), "restored result was not valid");
            require(restored.actualGpuLayers() != null && restored.actualGpuLayers() == 14,
                    "actual GPU layer evidence was lost");
            require(restored.candidate().settings().microBatchSize() == 256,
                    "candidate geometry was not restored");
            require("max-autotune".equals(loaded.workload().name()), "workload identity was not restored");
            require(Math.abs(restored.metrics().get("minecraftFps") - 60.0) < 0.0001,
                    "auxiliary metric was not restored");
            System.out.println("Koil benchmark history store proof passed");
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
