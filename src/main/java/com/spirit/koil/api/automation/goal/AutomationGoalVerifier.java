package com.spirit.koil.api.automation.goal;

/** Final-state verifier for Phase 1 high-level goals. */
public final class AutomationGoalVerifier {
    public Result verify(AutomationGoal goal, AutomationFactGraph facts) {
        if (goal == null || facts == null) {
            return Result.failed(AutomationGoalFailureCode.SERVER_DATA_UNAVAILABLE, "Goal facts are unavailable.", 0, 0);
        }
        if (!facts.has("item.exists:" + goal.targetId()) || !Boolean.TRUE.equals(facts.fact("item.exists:" + goal.targetId()).value())) {
            return Result.failed(AutomationGoalFailureCode.UNKNOWN_ITEM, "The requested item is not in the active registry.", 0, goal.count());
        }
        int observed = facts.inventoryCount(goal.targetId());
        if (observed < goal.count()) {
            return Result.failed(AutomationGoalFailureCode.RESOURCE_MISSING,
                    "Inventory contains " + observed + " of " + goal.count() + " requested " + goal.targetId() + ".",
                    observed,
                    goal.count());
        }
        return Result.passed(observed, goal.count());
    }

    public record Result(
            boolean passed,
            AutomationGoalFailureCode failureCode,
            String detail,
            int observedCount,
            int requestedCount
    ) {
        public Result {
            detail = detail == null ? "" : detail;
            observedCount = Math.max(0, observedCount);
            requestedCount = Math.max(0, requestedCount);
        }

        public static Result passed(int observed, int requested) {
            return new Result(true, null, "Final inventory verification passed.", observed, requested);
        }

        public static Result failed(AutomationGoalFailureCode code, String detail, int observed, int requested) {
            return new Result(false, code, detail, observed, requested);
        }

        public boolean failed() {
            return !passed;
        }
    }
}
