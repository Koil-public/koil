package com.spirit.koil.api.model.hardware;

public enum ModelHardwareTier {
    NOT_CONFIGURED,
    UNSUPPORTED,
    INCOMPLETE_INSTALLATION,
    DISK_STREAMING_MINIMUM,
    DISK_STREAMING_MEASURED,
    HYBRID_RESIDENCY,
    MOSTLY_RESIDENT,
    FULLY_RESIDENT
}
