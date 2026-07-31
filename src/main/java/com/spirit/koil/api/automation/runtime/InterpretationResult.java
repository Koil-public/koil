package com.spirit.koil.api.automation.runtime;

import java.util.Map;
import java.util.UUID;

public record InterpretationResult(
        ExecutionPlan plan,
        String semanticOperationId,
        String selectedTemplateId,
        Map<String, Object> boundParams,
        Map<String, Object> diagnostics,
        UUID executionId
) {
    public InterpretationResult {
        executionId = executionId == null ? UUID.randomUUID() : executionId;
    }
}
