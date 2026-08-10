package com.spirit.koil.api.model.planning;

import com.spirit.koil.api.model.ModelToolResult;

/**
 * Composable Automation finalization policy for the experimental No-Fail
 * feature. It consumes the same validated results and objective evidence as
 * Verification; it never weakens approval, cancellation, or loop guards.
 */
public final class NoFailExecutionPolicy {
    private NoFailExecutionPolicy() {
    }

    public static Decision evaluate(
            boolean enabled,
            boolean automationRequest,
            boolean executionObjective,
            int successfulToolOutputs,
            boolean everyKnownObjectiveCompleted
    ) {
        if (!enabled || !automationRequest || !executionObjective) {
            return Decision.allow();
        }
        if (successfulToolOutputs <= 0) {
            return Decision.continueWith("successful_tool_output_required");
        }
        if (!everyKnownObjectiveCompleted) {
            return Decision.continueWith("objective_evidence_incomplete");
        }
        return Decision.allow();
    }

    /** Verification-enabled sessions accept only a passed validated completion. */
    public static boolean accepts(ModelToolResult result, boolean verificationEnabled) {
        if (result == null) return false;
        if ("already_satisfied".equalsIgnoreCase(result.status())) return true;
        if (!result.completedAndValidated()) return false;
        return !verificationEnabled || "passed".equalsIgnoreCase(result.validationStatus());
    }

    public record Decision(boolean allowFinalization, String reason) {
        private static Decision allow() {
            return new Decision(true, "satisfied");
        }

        private static Decision continueWith(String reason) {
            return new Decision(false, reason);
        }
    }
}
