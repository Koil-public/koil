package com.spirit.koil.api.model.install;

public enum ModelInstallationState {
    IDLE,
    CHECKING,
    DOWNLOADING_RUNTIME,
    EXTRACTING_RUNTIME,
    BUILDING_RUNTIME,
    DOWNLOADING_MODEL,
    VERIFYING,
    UNINSTALLING,
    READY,
    CANCELLED,
    FAILED;

    public boolean active() {
        return this == CHECKING
                || this == DOWNLOADING_RUNTIME
                || this == EXTRACTING_RUNTIME
                || this == BUILDING_RUNTIME
                || this == DOWNLOADING_MODEL
                || this == VERIFYING
                || this == UNINSTALLING;
    }

    public boolean terminal() {
        return this == READY || this == CANCELLED || this == FAILED;
    }
}
