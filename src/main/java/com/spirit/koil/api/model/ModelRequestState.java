package com.spirit.koil.api.model;

public enum ModelRequestState {
    WAITING_FOR_RUNTIME,
    QUEUED,
    PREPARING_CONTEXT,
    PREFILLING,
    GENERATING,
    EXECUTING_TOOL,
    WAITING_FOR_TOOL_RESULT,
    FINALIZING,
    COMPLETED,
    CANCELLED,
    FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }
}
