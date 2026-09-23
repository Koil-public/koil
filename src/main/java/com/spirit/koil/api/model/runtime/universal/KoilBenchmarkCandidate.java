package com.spirit.koil.api.model.runtime.universal;

import java.util.Map;

/**
 * One architecture-neutral execution policy to measure. The candidate describes policy only;
 * an execution adapter translates it into native runtime controls.
 */
public record KoilBenchmarkCandidate(
        String id,
        String label,
        KoilRuntimeBackend backend,
        KoilExecutionSettings settings,
        ValidationMode validationMode,
        String contextRegime,
        Map<String, String> attributes
) {
    public enum ValidationMode {
        EXACT,
        ACCELERATOR_FIT
    }

    public KoilBenchmarkCandidate {
        id = safe(id);
        label = safe(label);
        if (id.isBlank()) id = label;
        if (label.isBlank()) label = id.isBlank() ? "benchmark" : id;
        backend = backend == null ? KoilRuntimeBackend.UNKNOWN : backend;
        settings = settings == null ? KoilExecutionSettings.automatic() : settings;
        validationMode = validationMode == null ? ValidationMode.EXACT : validationMode;
        contextRegime = safe(contextRegime);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public boolean acceptsObservedPlacement(String placement, int gpuLayers) {
        String actual = safe(placement).toLowerCase(java.util.Locale.ROOT);
        return switch (settings.placement()) {
            case CPU -> "cpu".equals(actual) && gpuLayers <= 0;
            case HYBRID -> "hybrid".equals(actual) && gpuLayers == settings.gpuLayers();
            case GPU -> validationMode == ValidationMode.ACCELERATOR_FIT
                    ? ("gpu".equals(actual) || "hybrid".equals(actual)) && gpuLayers > 0
                    : "gpu".equals(actual) && gpuLayers > 0;
            case AUTOMATIC -> !actual.isBlank() && !"unknown".equals(actual);
        };
    }

    public String geometryKey() {
        return settings.placement() + ":" + settings.device() + ":" + settings.gpuLayers()
                + ":" + settings.generationThreads() + ":" + settings.batchThreads()
                + ":" + settings.pollPercent() + ":" + settings.batchPollMode()
                + ":" + settings.batchSize() + ":" + settings.microBatchSize()
                + ":" + settings.statePlacement() + ":" + settings.operatorPlacement()
                + ":" + settings.flashAttention() + ":" + settings.repack();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
