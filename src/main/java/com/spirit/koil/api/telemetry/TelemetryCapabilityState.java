package com.spirit.koil.api.telemetry;

/** Canonical availability state shared by model, automation, executor, KTL and tools. */
public enum TelemetryCapabilityState {
    AVAILABLE,
    IDLE,
    ACTIVE,
    DEGRADED,
    BLOCKED,
    NOT_IMPLEMENTED,
    UNAVAILABLE,
    FAILED,
    CANCELLED;

    public boolean terminalProblem() {
        return this == BLOCKED || this == NOT_IMPLEMENTED || this == UNAVAILABLE
                || this == FAILED || this == CANCELLED;
    }
}
