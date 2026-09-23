package com.spirit.koil.api.model.runtime.universal;

/**
 * Typed execution geometry owned by the universal Koil runtime.
 * Zero means "runtime/backend automatic" for thread/batch/poll fields.
 * gpuLayers uses -1 for automatic/fit-managed placement.
 */
public record KoilExecutionSettings(
        KoilPlacementPolicy placement,
        String device,
        int gpuLayers,
        int generationThreads,
        int batchThreads,
        int batchSize,
        int microBatchSize,
        int pollPercent,
        int batchPollMode,
        KoilStatePlacement statePlacement,
        KoilOperatorPlacement operatorPlacement,
        KoilFeatureMode flashAttention,
        boolean repack,
        long memoryBudgetBytes
) {
    public KoilExecutionSettings {
        placement = placement == null ? KoilPlacementPolicy.AUTOMATIC : placement;
        device = device == null ? "" : device.strip().replaceAll("[\\p{Cntrl}]", "");
        gpuLayers = Math.max(-1, gpuLayers);
        generationThreads = Math.max(0, generationThreads);
        batchThreads = Math.max(0, batchThreads);
        batchSize = Math.max(0, batchSize);
        microBatchSize = Math.max(0, microBatchSize);
        if (batchSize > 0 && microBatchSize > batchSize) microBatchSize = batchSize;
        pollPercent = Math.max(0, Math.min(100, pollPercent));
        batchPollMode = batchPollMode <= 0 ? 0 : 1;
        statePlacement = statePlacement == null ? KoilStatePlacement.AUTOMATIC : statePlacement;
        operatorPlacement = operatorPlacement == null ? KoilOperatorPlacement.AUTOMATIC : operatorPlacement;
        flashAttention = flashAttention == null ? KoilFeatureMode.AUTO : flashAttention;
        memoryBudgetBytes = Math.max(0L, memoryBudgetBytes);

        if (placement == KoilPlacementPolicy.CPU) {
            gpuLayers = 0;
            statePlacement = KoilStatePlacement.HOST;
            operatorPlacement = KoilOperatorPlacement.HOST;
        }
    }

    public static KoilExecutionSettings automatic() {
        return new KoilExecutionSettings(
                KoilPlacementPolicy.AUTOMATIC, "", -1,
                0, 0, 0, 0, 0, 0,
                KoilStatePlacement.AUTOMATIC,
                KoilOperatorPlacement.AUTOMATIC,
                KoilFeatureMode.AUTO,
                true,
                0L
        );
    }

    public boolean hasMeasuredGeometry() {
        return generationThreads > 0 || batchThreads > 0 || batchSize > 0 || microBatchSize > 0
                || pollPercent > 0 || gpuLayers >= 0;
    }
}
