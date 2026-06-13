package com.spirit.koil.api.performance;

public record PerformanceApplyEntryResult(
        String recommendationId,
        String settingKey,
        String status,
        boolean changed,
        String message
) {
}
