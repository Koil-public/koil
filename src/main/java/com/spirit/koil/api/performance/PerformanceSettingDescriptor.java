package com.spirit.koil.api.performance;

public record PerformanceSettingDescriptor(
        String providerId,
        String settingId,
        String label,
        String category,
        String currentValue,
        String recommendedValue,
        String risk,
        boolean liveApplySupported,
        boolean requiresResourceReload,
        String configPath,
        String description
) {
}
