package com.spirit.koil.api.automation.runtime;

import java.util.Locale;

/** Stable machine-facing terminal and progress states for Automation and KTL. */
public enum AutomationResultStatus {
    SUCCESS,
    PARTIAL,
    BLOCKED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    NO_TARGET,
    ALREADY_SATISFIED;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean objectiveReachedByDefinition() {
        return this == SUCCESS || this == ALREADY_SATISFIED;
    }

    public static AutomationResultStatus from(String status, String failureCode, boolean progressed) {
        String normalized = status == null ? "" : status.strip().toLowerCase(Locale.ROOT);
        String failure = failureCode == null ? "" : failureCode.strip().toLowerCase(Locale.ROOT);
        if (normalized.equals("success") || normalized.equals("completed")) return SUCCESS;
        if (normalized.equals("partial")) return PARTIAL;
        if (normalized.equals("cancelled") || normalized.equals("canceled")) return CANCELLED;
        if (normalized.equals("interrupted")) return INTERRUPTED;
        if (normalized.equals("already_satisfied") || normalized.equals("already satisfied")) return ALREADY_SATISFIED;
        if (normalized.equals("no_target") || failure.contains("no_matching") || failure.contains("target_not_found")) return NO_TARGET;
        if (normalized.equals("blocked") || normalized.equals("timed_out")) return progressed ? PARTIAL : BLOCKED;
        return FAILED;
    }
}
