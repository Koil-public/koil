package com.spirit.koil.api.automation.goal;

/** Stable reasons returned by deterministic goal compilation and verification. */
public enum AutomationGoalFailureCode {
    UNKNOWN_ITEM,
    SERVER_DATA_UNAVAILABLE,
    RECIPE_UNKNOWN,
    RECIPE_UNSUPPORTED,
    RESOURCE_MISSING,
    PLAN_STALE,
    LOCK_CONFLICT,
    VERIFICATION_FAILED,
    TIMEOUT,
    INTERRUPTED;

    public String id() {
        return name();
    }
}
