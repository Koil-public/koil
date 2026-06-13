package com.spirit.koil.api.performance;

import java.util.List;

public record PerformanceRuntimeContext(
        long capturedAtMillis,
        String worldType,
        String worldName,
        String serverAddress,
        String dimension,
        String profileKey,
        String suggestedProfile,
        int resourcePackCount,
        List<String> resourcePacks,
        String shaderState,
        List<String> optimizationModConfigs,
        List<String> modpackImpactNotes
) {
}
