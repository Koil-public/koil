package com.spirit.koil.api.model.retrieval;

/** Structured historical tool evidence extracted from Koil's shared retrieval store. */
public record AutomationExecutionExperience(
        long knowledgeId,
        String toolId,
        String status,
        String validationStatus,
        String failureCode,
        boolean verified,
        boolean objectiveCompleted,
        boolean retryable,
        String argumentShape,
        String trajectory,
        String recoverySignature,
        String recoverySequence,
        long durationMillis,
        long timestampMillis,
        double retrievalScore
) {
    public AutomationExecutionExperience {
        toolId = clean(toolId);
        status = clean(status);
        validationStatus = clean(validationStatus);
        failureCode = clean(failureCode);
        argumentShape = clean(argumentShape);
        trajectory = clean(trajectory);
        recoverySignature = clean(recoverySignature);
        recoverySequence = clean(recoverySequence);
        durationMillis = Math.max(0L, durationMillis);
        timestampMillis = Math.max(0L, timestampMillis);
        if (!Double.isFinite(retrievalScore)) retrievalScore = 0.0D;
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
