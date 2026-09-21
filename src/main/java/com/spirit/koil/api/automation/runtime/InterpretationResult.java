package com.spirit.koil.api.automation.runtime;

import java.util.Map;
import java.util.UUID;

public record InterpretationResult(
        ExecutionPlan plan,
        String semanticOperationId,
        String selectedTemplateId,
        Map<String, Object> boundParams,
        Map<String, Object> diagnostics,
        UUID executionId,
        UUID telemetryRequestId,
        String telemetryParentSpanId
) {
    public InterpretationResult {
        executionId = executionId == null ? UUID.randomUUID() : executionId;
        telemetryParentSpanId = telemetryParentSpanId == null ? "" : telemetryParentSpanId;
    }

    public InterpretationResult(
            ExecutionPlan plan,
            String semanticOperationId,
            String selectedTemplateId,
            Map<String, Object> boundParams,
            Map<String, Object> diagnostics,
            UUID executionId
    ) {
        this(plan, semanticOperationId, selectedTemplateId, boundParams, diagnostics, executionId, null, "");
    }

    public InterpretationResult withTelemetryParent(UUID requestId, String parentSpanId) {
        return new InterpretationResult(plan, semanticOperationId, selectedTemplateId, boundParams, diagnostics,
                executionId, requestId, parentSpanId);
    }
}
