package com.spirit.koil.api.performance;

import java.util.List;

public record PerformanceHardwareProfile(
        long scannedAtMillis,
        String operatingSystem,
        String architecture,
        int cpuThreads,
        long systemMemoryMb,
        String gpuVendor,
        String gpuRenderer,
        String gpuVersion,
        String vramStatus,
        double storageProbeMbPerSecond,
        int monitorWidth,
        int monitorHeight,
        int refreshRate,
        String batteryStatus,
        String minecraftVersion,
        List<String> loadedMods,
        List<String> optimizationMods,
        List<String> enabledResourcePacks,
        boolean shaderModInstalled
) {
}
