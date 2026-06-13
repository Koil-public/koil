package com.spirit.koil.api.performance;

public record PerformanceProviderApplyResult(
        String providerId,
        String settingId,
        boolean changed,
        boolean liveApplied,
        boolean resourceReloadRequested,
        String message
) {
}
