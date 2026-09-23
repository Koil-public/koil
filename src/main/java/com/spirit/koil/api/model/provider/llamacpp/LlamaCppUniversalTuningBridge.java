package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilComputeMode;
import com.spirit.koil.api.model.runtime.universal.KoilMeasuredTuningProfile;
import com.spirit.koil.api.model.runtime.universal.KoilExecutionPlan;
import com.spirit.koil.api.model.runtime.universal.KoilExecutionSettings;
import com.spirit.koil.api.model.runtime.universal.KoilHardwareProfiler;
import com.spirit.koil.api.model.runtime.universal.KoilModelProfile;
import com.spirit.koil.api.model.runtime.universal.KoilRuntimeBackend;

import java.util.LinkedHashMap;
import java.util.Map;

/** Translates the mature llama.cpp MAX tuner winner into universal planner evidence. */
public final class LlamaCppUniversalTuningBridge {
    private LlamaCppUniversalTuningBridge() {}

    public static KoilMeasuredTuningProfile translate(
            LlamaCppMaxTuningStore.TunedProfile tuned,
            KoilModelProfile model,
            String hardwareFingerprint,
            KoilRuntimeBackend observedBackend
    ) {
        if (tuned == null) return null;
        LlamaCppMaxRuntimeProfile profile = tuned.profile();
        KoilRuntimeBackend evidencedBackend = observedBackend == null ? KoilRuntimeBackend.UNKNOWN : observedBackend;
        if (evidencedBackend == KoilRuntimeBackend.UNKNOWN) {
            evidencedBackend = KoilHardwareProfiler.backendFromEvidence(tuned.deviceId() + " " + tuned.deviceDetail());
        }
        KoilRuntimeBackend backend = switch (profile.placement()) {
            case CPU -> KoilRuntimeBackend.CPU;
            case HYBRID, GPU -> evidencedBackend;
        };

        Map<String, String> decisions = new LinkedHashMap<>();
        decisions.put("placement", profile.expectedPlacement());
        decisions.put("device", tuned.deviceId());
        decisions.put("gpuLayers", Integer.toString(profile.gpuLayers()));
        decisions.put("generationThreads", Integer.toString(profile.generationThreads()));
        decisions.put("batchThreads", Integer.toString(profile.batchThreads()));
        decisions.put("poll", Integer.toString(profile.poll()));
        decisions.put("pollBatch", Integer.toString(profile.pollBatch()));
        decisions.put("batchSize", Integer.toString(profile.batchSize()));
        decisions.put("ubatchSize", Integer.toString(profile.ubatchSize()));
        decisions.put("profileLabel", profile.label());
        decisions.put("candidatesTested", Integer.toString(tuned.candidatesTested()));
        decisions.put("contextRegime", tuned.contextRegime());
        decisions.put("statePrecisionRegime", tuned.statePrecisionRegime());

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("promptTokensPerSecond", tuned.benchmark().promptTokensPerSecond());
        metrics.put("generationTokensPerSecond", tuned.benchmark().generationTokensPerSecond());
        metrics.put("timeToFirstTokenMillis", tuned.benchmark().timeToFirstTokenMillis());
        metrics.put("wallMillis", (double) tuned.benchmark().wallMillis());

        return new KoilMeasuredTuningProfile(
                tuned.identity(),
                "llama_cpp_max_compat",
                tuned.tunedAt(),
                "llama_cpp",
                tuned.modelId(),
                model == null ? "" : model.architectureId(),
                tuned.runtimeIdentity(),
                hardwareFingerprint,
                KoilComputeMode.AUTOMATIC,
                backend,
                tuned.score(),
                metrics,
                decisions
        );
    }
    /** Converts a universal typed plan back into llama.cpp MAX runtime geometry. */
    public static LlamaCppMaxRuntimeProfile toRuntimeProfile(KoilExecutionPlan plan) {
        if (plan == null || plan.settings() == null) return null;
        KoilExecutionSettings settings = plan.settings();
        if (!settings.hasMeasuredGeometry()) return null;
        LlamaCppMaxRuntimeProfile.Placement placement = switch (settings.placement()) {
            case CPU -> LlamaCppMaxRuntimeProfile.Placement.CPU;
            case HYBRID -> LlamaCppMaxRuntimeProfile.Placement.HYBRID;
            case GPU -> LlamaCppMaxRuntimeProfile.Placement.GPU;
            case AUTOMATIC -> null;
        };
        if (placement == null) return null;
        int gpuLayers = settings.gpuLayers();
        if (placement == LlamaCppMaxRuntimeProfile.Placement.HYBRID && gpuLayers <= 0) return null;
        if (gpuLayers < 0) gpuLayers = LlamaCppComputeSettings.MAX_GPU_LAYERS;
        String source = plan.decisions().getOrDefault("tuningSource", "koil_universal");
        return new LlamaCppMaxRuntimeProfile(
                placement,
                gpuLayers,
                settings.generationThreads(),
                settings.batchThreads(),
                settings.pollPercent(),
                settings.batchPollMode(),
                settings.batchSize(),
                settings.microBatchSize(),
                "koil-universal:" + source
        );
    }

}
