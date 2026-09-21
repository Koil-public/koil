package com.spirit.koil.api.automation.goal;

import java.time.Instant;
import java.util.List;

/** Structured graph-level execution evidence for the Automation workspace. */
public record AutomationGoalTrace(
        String goalId,
        String nodeId,
        List<String> requiredFacts,
        List<String> resourceLocks,
        String expectedObservation,
        String actualObservation,
        AutomationGoalFailureCode failureCode,
        String runtimeFailureCode,
        int retry,
        long durationMillis,
        Instant capturedAt
) {
    public AutomationGoalTrace {
        goalId = goalId == null ? "" : goalId;
        nodeId = nodeId == null ? "" : nodeId;
        requiredFacts = requiredFacts == null ? List.of() : List.copyOf(requiredFacts);
        resourceLocks = resourceLocks == null ? List.of() : List.copyOf(resourceLocks);
        expectedObservation = expectedObservation == null ? "" : expectedObservation;
        actualObservation = actualObservation == null ? "" : actualObservation;
        runtimeFailureCode = runtimeFailureCode == null ? "" : runtimeFailureCode;
        retry = Math.max(0, retry);
        durationMillis = Math.max(0L, durationMillis);
        capturedAt = capturedAt == null ? Instant.now() : capturedAt;
    }
}
