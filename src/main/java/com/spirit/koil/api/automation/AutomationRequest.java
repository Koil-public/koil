package com.spirit.koil.api.automation;

import java.util.UUID;

/**
 * Typed automation request. telemetryRequestId/telemetryParentSpanId preserve
 * causal ownership when a model tool becomes a planner/executor/KTL task.
 */
public record AutomationRequest(
        String rawInput,
        boolean runCommand,
        boolean directTemplate,
        UUID executionId,
        UUID telemetryRequestId,
        String telemetryParentSpanId
) {
    public AutomationRequest {
        rawInput = rawInput == null ? "" : rawInput;
        executionId = executionId == null ? UUID.randomUUID() : executionId;
        telemetryParentSpanId = telemetryParentSpanId == null ? "" : telemetryParentSpanId;
    }

    public AutomationRequest(String rawInput, boolean runCommand, boolean directTemplate, UUID executionId) {
        this(rawInput, runCommand, directTemplate, executionId, null, "");
    }

    public AutomationRequest(String rawInput, boolean runCommand, boolean directTemplate) {
        this(rawInput, runCommand, directTemplate, UUID.randomUUID(), null, "");
    }

    public AutomationRequest withTelemetry(UUID requestId, String parentSpanId) {
        return new AutomationRequest(rawInput, runCommand, directTemplate, executionId, requestId, parentSpanId);
    }
}
