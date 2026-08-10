package com.spirit.koil.api.automation.runtime;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AutomationExecutionResult(
        UUID executionId,
        String status,
        String failureCode,
        String detail,
        String templateId,
        Map<String, Object> state,
        AutomationPositionSnapshot initialPosition,
        AutomationPositionSnapshot finalPosition,
        Instant startedAt,
        Instant finishedAt
) {
    public AutomationExecutionResult {
        executionId = executionId == null ? UUID.randomUUID() : executionId;
        status = status == null || status.isBlank() ? "failed" : status.trim();
        failureCode = failureCode == null ? "" : failureCode.trim();
        detail = detail == null ? "" : detail;
        templateId = templateId == null ? "" : templateId.trim();
        state = state == null ? Map.of() : Map.copyOf(state);
        startedAt = startedAt == null ? Instant.now() : startedAt;
        finishedAt = finishedAt == null ? Instant.now() : finishedAt;
    }

    public AutomationStructuredResult structured() {
        return AutomationStructuredResult.from(this);
    }
}
