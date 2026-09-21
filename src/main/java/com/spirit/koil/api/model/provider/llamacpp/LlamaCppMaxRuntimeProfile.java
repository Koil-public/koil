package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilExecutionSettings;
import com.spirit.koil.api.model.runtime.universal.KoilFeatureMode;
import com.spirit.koil.api.model.runtime.universal.KoilOperatorPlacement;
import com.spirit.koil.api.model.runtime.universal.KoilStatePlacement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Concrete llama.cpp runtime geometry selected for Koil MAX mode.
 *
 * <p>MAX is a user-facing policy. This record is the measured machine/model-specific
 * implementation of that policy, so MAX may legitimately resolve to CPU, hybrid,
 * or GPU-preferred fitted placement when that placement wins the benchmark.</p>
 */
public record LlamaCppMaxRuntimeProfile(
        Placement placement,
        int gpuLayers,
        int generationThreads,
        int batchThreads,
        int poll,
        int pollBatch,
        int batchSize,
        int ubatchSize,
        String label
) {
    public enum Placement {
        CPU,
        HYBRID,
        GPU
    }

    public LlamaCppMaxRuntimeProfile {
        placement = placement == null ? Placement.GPU : placement;
        gpuLayers = Math.max(0, Math.min(LlamaCppComputeSettings.MAX_GPU_LAYERS, gpuLayers));
        generationThreads = Math.max(0, generationThreads);
        batchThreads = Math.max(0, batchThreads);
        poll = Math.max(0, Math.min(100, poll));
        pollBatch = pollBatch <= 0 ? 0 : 1;
        batchSize = clampBatch(batchSize <= 0 ? 2048 : batchSize);
        ubatchSize = clampBatch(ubatchSize <= 0 ? 512 : ubatchSize);
        ubatchSize = Math.min(batchSize, ubatchSize);
        label = label == null ? "" : label.strip();
        if (label.isBlank()) label = defaultLabel(placement, gpuLayers, generationThreads, batchThreads, poll, batchSize, ubatchSize);
        if (placement == Placement.CPU) gpuLayers = 0;
        if (placement == Placement.HYBRID && gpuLayers <= 0) gpuLayers = 1;
    }

    public static LlamaCppMaxRuntimeProfile forcedFallback() {
        return new LlamaCppMaxRuntimeProfile(
                Placement.GPU,
                LlamaCppComputeSettings.MAX_GPU_LAYERS,
                0,
                0,
                100,
                1,
                2048,
                512,
                "safe GPU-fit fallback"
        );
    }

    public String expectedPlacement() {
        return placement.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns whether llama.cpp runtime telemetry is compatible with this MAX profile.
     *
     * <p>GPU is a GPU-fit policy, not an exact all-layers-on-accelerator contract.
     * The command intentionally uses {@code --fit on} without forcing
     * {@code --n-gpu-layers}, so llama.cpp may legitimately retain one or more
     * model layers on the host and report the resulting placement as hybrid.
     * HYBRID remains strict because that profile requests an exact layer split.</p>
     */
    public boolean acceptsRuntimePlacement(String actualPlacement, Integer actualGpuLayers) {
        String actual = actualPlacement == null ? "unknown" : actualPlacement.strip().toLowerCase(Locale.ROOT);
        int layers = actualGpuLayers == null ? -1 : actualGpuLayers;
        return switch (placement) {
            case CPU -> "cpu".equals(actual) && layers <= 0;
            case HYBRID -> "hybrid".equals(actual) && layers == gpuLayers;
            case GPU -> ("gpu".equals(actual) || "hybrid".equals(actual)) && layers > 0;
        };
    }

    public List<String> arguments(String device) {
        return arguments(device, null);
    }

    public List<String> arguments(String device, KoilExecutionSettings executionSettings) {
        List<String> arguments = new ArrayList<>();
        String resolvedDevice = executionSettings != null && !executionSettings.device().isBlank()
                ? executionSettings.device() : device;
        if (placement == Placement.CPU) {
            arguments.add("--device");
            arguments.add("none");
            arguments.add("--n-gpu-layers");
            arguments.add("0");
        } else {
            if (resolvedDevice != null && !resolvedDevice.isBlank()) {
                arguments.add("--device");
                arguments.add(resolvedDevice.strip());
            }
            if (placement == Placement.HYBRID) {
                arguments.add("--n-gpu-layers");
                arguments.add(Integer.toString(gpuLayers));
            }
            arguments.add("--fit");
            arguments.add("on");
            arguments.add("--fit-target");
            arguments.add(Integer.toString(LlamaCppComputeArguments.acceleratorHeadroomMiB()));
        }

        applyStatePlacement(arguments, executionSettings);
        applyOperatorPlacement(arguments, executionSettings);

        arguments.add("--threads");
        arguments.add(Integer.toString(generationThreads));
        arguments.add("--threads-batch");
        arguments.add(Integer.toString(batchThreads));
        arguments.add("--poll");
        arguments.add(Integer.toString(poll));
        arguments.add("--poll-batch");
        arguments.add(Integer.toString(pollBatch));
        arguments.add("--batch-size");
        arguments.add(Integer.toString(batchSize));
        arguments.add("--ubatch-size");
        arguments.add(Integer.toString(ubatchSize));

        arguments.add("--flash-attn");
        KoilFeatureMode flashMode = executionSettings == null ? KoilFeatureMode.AUTO : executionSettings.flashAttention();
        arguments.add(switch (flashMode) {
            case ENABLED -> "on";
            case DISABLED -> "off";
            case AUTO -> "auto";
        });
        if (executionSettings == null || executionSettings.repack()) arguments.add("--repack");
        return List.copyOf(arguments);
    }

    private void applyStatePlacement(List<String> arguments, KoilExecutionSettings executionSettings) {
        KoilStatePlacement state = executionSettings == null
                ? (placement == Placement.HYBRID ? KoilStatePlacement.HOST
                : placement == Placement.GPU ? KoilStatePlacement.ACCELERATOR : KoilStatePlacement.HOST)
                : executionSettings.statePlacement();
        switch (state) {
            case HOST -> arguments.add("--no-kv-offload");
            case ACCELERATOR -> arguments.add("--kv-offload");
            case AUTOMATIC, SPLIT -> { }
        }
    }

    private void applyOperatorPlacement(List<String> arguments, KoilExecutionSettings executionSettings) {
        KoilOperatorPlacement operators = executionSettings == null
                ? (placement == Placement.HYBRID ? KoilOperatorPlacement.HOST
                : placement == Placement.GPU ? KoilOperatorPlacement.ACCELERATOR : KoilOperatorPlacement.HOST)
                : executionSettings.operatorPlacement();
        switch (operators) {
            case HOST -> arguments.add("--no-op-offload");
            case ACCELERATOR -> arguments.add("--op-offload");
            case AUTOMATIC, SPLIT -> { }
        }
    }

    public String summary() {
        String placementDetail = switch (placement) {
            case CPU -> "cpu";
            case GPU -> "gpu-fit";
            case HYBRID -> "hybrid-" + gpuLayers;
        };
        return placementDetail
                + " | threads=" + threadLabel(generationThreads)
                + "/" + threadLabel(batchThreads)
                + " | poll=" + poll + "/" + pollBatch
                + " | batch=" + batchSize + "/" + ubatchSize;
    }

    private static String threadLabel(int threads) {
        return threads <= 0 ? "all" : Integer.toString(threads);
    }

    private static int clampBatch(int value) {
        return Math.max(32, Math.min(8192, value));
    }

    private static String defaultLabel(
            Placement placement,
            int gpuLayers,
            int generationThreads,
            int batchThreads,
            int poll,
            int batchSize,
            int ubatchSize
    ) {
        return placement.name().toLowerCase(Locale.ROOT)
                + (placement == Placement.HYBRID ? "-" + gpuLayers : "")
                + "-t" + generationThreads
                + "-tb" + batchThreads
                + "-p" + poll
                + "-b" + batchSize
                + "-ub" + ubatchSize;
    }
}
