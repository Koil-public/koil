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
    public PerformanceSettingDescriptor {
        providerId = normalize(providerId, "unavailable-provider");
        settingId = normalize(settingId, "unavailable-setting");
        label = normalize(label, settingId);
        category = normalize(category, "uncategorized");
        currentValue = normalize(currentValue, "unavailable");
        recommendedValue = normalize(recommendedValue, currentValue);
        risk = normalize(risk, "manual-review");
        configPath = normalize(configPath, "runtime");
        description = normalize(description, "No provider description is available.");
    }

    public boolean hasActionableTarget() {
        return liveApplySupported
                && !currentValue.equalsIgnoreCase("unavailable")
                && !recommendedValue.equalsIgnoreCase("unavailable")
                && !currentValue.equalsIgnoreCase(recommendedValue);
    }

    public boolean observationOnly() {
        return !liveApplySupported && currentValue.equalsIgnoreCase(recommendedValue);
    }

    public boolean hasVerifiedCurrentValue() {
        return !currentValue.equalsIgnoreCase("unavailable");
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
