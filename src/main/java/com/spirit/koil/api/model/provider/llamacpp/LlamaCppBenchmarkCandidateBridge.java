package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.ModelPerformanceBenchmarkResult;
import com.spirit.koil.api.model.runtime.universal.KoilBenchmarkCandidate;
import com.spirit.koil.api.model.runtime.universal.KoilBenchmarkResult;
import com.spirit.koil.api.model.runtime.universal.KoilExecutionSettings;
import com.spirit.koil.api.model.runtime.universal.KoilOperatorPlacement;
import com.spirit.koil.api.model.runtime.universal.KoilPlacementPolicy;
import com.spirit.koil.api.model.runtime.universal.KoilStatePlacement;

import java.time.Instant;
import java.util.Map;

/** Lossless translation between Koil benchmark policy and llama.cpp MAX runtime geometry. */
public final class LlamaCppBenchmarkCandidateBridge {
    private LlamaCppBenchmarkCandidateBridge() {}

    public static LlamaCppMaxRuntimeProfile toRuntimeProfile(KoilBenchmarkCandidate candidate) {
        if (candidate == null) throw new IllegalArgumentException("candidate is required");
        KoilExecutionSettings settings = candidate.settings();
        LlamaCppMaxRuntimeProfile.Placement placement = switch (settings.placement()) {
            case CPU -> LlamaCppMaxRuntimeProfile.Placement.CPU;
            case HYBRID -> LlamaCppMaxRuntimeProfile.Placement.HYBRID;
            case GPU, AUTOMATIC -> LlamaCppMaxRuntimeProfile.Placement.GPU;
        };
        int gpuLayers = placement == LlamaCppMaxRuntimeProfile.Placement.GPU && settings.gpuLayers() < 0
                ? LlamaCppComputeSettings.MAX_GPU_LAYERS : settings.gpuLayers();
        return new LlamaCppMaxRuntimeProfile(
                placement,
                gpuLayers,
                settings.generationThreads(),
                settings.batchThreads(),
                settings.pollPercent(),
                settings.batchPollMode(),
                settings.batchSize(),
                settings.microBatchSize(),
                candidate.label()
        );
    }

    public static KoilBenchmarkCandidate fromRuntimeProfile(
            LlamaCppMaxRuntimeProfile profile,
            com.spirit.koil.api.model.runtime.universal.KoilRuntimeBackend backend,
            String device,
            String contextRegime
    ) {
        if (profile == null) throw new IllegalArgumentException("profile is required");
        KoilPlacementPolicy placement = switch (profile.placement()) {
            case CPU -> KoilPlacementPolicy.CPU;
            case HYBRID -> KoilPlacementPolicy.HYBRID;
            case GPU -> KoilPlacementPolicy.GPU;
        };
        KoilExecutionSettings settings = new KoilExecutionSettings(
                placement,
                device,
                profile.placement() == LlamaCppMaxRuntimeProfile.Placement.GPU ? -1 : profile.gpuLayers(),
                profile.generationThreads(),
                profile.batchThreads(),
                profile.batchSize(),
                profile.ubatchSize(),
                profile.poll(),
                profile.pollBatch(),
                placement == KoilPlacementPolicy.GPU ? KoilStatePlacement.ACCELERATOR : KoilStatePlacement.HOST,
                placement == KoilPlacementPolicy.GPU ? KoilOperatorPlacement.ACCELERATOR : KoilOperatorPlacement.HOST,
                com.spirit.koil.api.model.runtime.universal.KoilFeatureMode.AUTO,
                true,
                0L
        );
        return new KoilBenchmarkCandidate(
                profile.label(), profile.label(),
                placement == KoilPlacementPolicy.CPU
                        ? com.spirit.koil.api.model.runtime.universal.KoilRuntimeBackend.CPU : backend,
                settings,
                profile.placement() == LlamaCppMaxRuntimeProfile.Placement.GPU
                        ? KoilBenchmarkCandidate.ValidationMode.ACCELERATOR_FIT
                        : KoilBenchmarkCandidate.ValidationMode.EXACT,
                contextRegime,
                Map.of("llamaPlacement", profile.expectedPlacement())
        );
    }

    public static KoilBenchmarkResult toUniversalResult(
            KoilBenchmarkCandidate candidate,
            ModelPerformanceBenchmarkResult benchmark,
            String actualPlacement,
            int actualGpuLayers,
            String runtimeRevision,
            Boolean integrityPassed,
            Map<String, String> observations
    ) {
        if (benchmark == null) throw new IllegalArgumentException("benchmark is required");
        boolean placementAccepted = candidate.acceptsObservedPlacement(actualPlacement, actualGpuLayers);
        KoilBenchmarkResult.Outcome outcome = benchmark.valid() && (integrityPassed == null || integrityPassed) && placementAccepted
                ? KoilBenchmarkResult.Outcome.SUCCESS : KoilBenchmarkResult.Outcome.REJECTED;
        return new KoilBenchmarkResult(
                candidate, outcome,
                benchmark.promptTokens(), benchmark.generatedTokens(),
                benchmark.promptTokensPerSecond(), benchmark.generationTokensPerSecond(),
                benchmark.promptMillis(), benchmark.generationMillis(),
                benchmark.timeToFirstTokenMillis(), null, benchmark.wallMillis(),
                null, null, actualPlacement, actualGpuLayers,
                candidate.settings().statePlacement(), candidate.settings().operatorPlacement(),
                integrityPassed,
                outcome == KoilBenchmarkResult.Outcome.SUCCESS ? "" : "runtime_validation",
                benchmark.detail(), Instant.now(), runtimeRevision, Map.of(), observations
        );
    }
}
