package com.spirit.koil.api.performance;

public record PerformanceSnapshot(
        long capturedAtMillis,
        int fps,
        double averageFps,
        double onePercentLowFps,
        double frameTimeMs,
        double maxFrameTimeMs,
        long usedMemoryMb,
        long maxMemoryMb,
        double memoryPressure,
        int entityCount,
        int loadedModCount,
        int resourcePackCount,
        int optimizationModCount,
        int renderDistance,
        int simulationDistance,
        int maxFps,
        double entityDistanceScale,
        int mipmapLevels,
        String particlesMode,
        String graphicsMode,
        String cloudsMode,
        boolean smoothLighting,
        int biomeBlend,
        boolean entityShadows,
        boolean vsync,
        boolean shaderModInstalled,
        double gcPressure,
        double chunkStress,
        double shaderPressure,
        double uiFramePressure,
        double modLoadPressure,
        double resourcePackPressure,
        String worldType,
        String likelyCause,
        PerformanceBottleneck primaryBottleneck
) {
    public boolean hasVerifiedVideoOptions() {
        return renderDistance > 0
                && simulationDistance > 0
                && maxFps >= 0
                && entityDistanceScale > 0.0D
                && mipmapLevels >= 0
                && particlesMode != null
                && graphicsMode != null
                && cloudsMode != null
                && !"unavailable".equalsIgnoreCase(particlesMode)
                && !"unavailable".equalsIgnoreCase(graphicsMode)
                && !"unavailable".equalsIgnoreCase(cloudsMode);
    }
}
