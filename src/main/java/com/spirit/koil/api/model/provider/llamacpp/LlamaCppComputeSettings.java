package com.spirit.koil.api.model.provider.llamacpp;

/** Persistent llama.cpp compute-placement policy. */
public record LlamaCppComputeSettings(
        LlamaCppComputeMode mode,
        int hybridGpuLayers,
        String device
) {
    public static final int DEFAULT_HYBRID_GPU_LAYERS = 16;
    public static final int MAX_GPU_LAYERS = 4096;

    public LlamaCppComputeSettings {
        mode = mode == null ? LlamaCppComputeMode.CPU : mode;
        hybridGpuLayers = Math.max(1, Math.min(MAX_GPU_LAYERS, hybridGpuLayers));
        device = normalizeDevice(device);
    }

    public static LlamaCppComputeSettings defaults() {
        return new LlamaCppComputeSettings(
                LlamaCppComputeMode.CPU,
                DEFAULT_HYBRID_GPU_LAYERS,
                ""
        );
    }

    public LlamaCppComputeSettings withMode(LlamaCppComputeMode nextMode) {
        return new LlamaCppComputeSettings(nextMode, this.hybridGpuLayers, this.device);
    }

    public LlamaCppComputeSettings withHybridGpuLayers(int layers) {
        return new LlamaCppComputeSettings(this.mode, layers, this.device);
    }

    public LlamaCppComputeSettings withDevice(String nextDevice) {
        return new LlamaCppComputeSettings(this.mode, this.hybridGpuLayers, nextDevice);
    }

    public String deviceLabel() {
        return this.device.isBlank() ? "automatic" : this.device;
    }

    private static String normalizeDevice(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.trim();
        if (clean.equalsIgnoreCase("auto") || clean.equalsIgnoreCase("automatic") || clean.equalsIgnoreCase("default")) {
            return "";
        }
        // llama.cpp device identifiers are runtime-owned. Keep the value opaque,
        // but reject control characters so it remains one ProcessBuilder argument.
        return clean.replaceAll("[\\p{Cntrl}]", "");
    }
}
