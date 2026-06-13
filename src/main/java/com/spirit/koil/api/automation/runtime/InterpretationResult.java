package com.spirit.koil.api.automation.runtime;

import java.util.Map;

public record InterpretationResult(
        ExecutionPlan plan,
        String semanticOperationId,
        String selectedTemplateId,
        Map<String, Object> boundParams,
        Map<String, Object> diagnostics
) {
}
