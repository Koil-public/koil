package com.spirit.koil.api.model.runtime.universal;

/** Runtime-neutral compute placement preference exposed by the unified Koil runtime. */
public enum KoilComputeMode {
    AUTOMATIC,
    CPU,
    GPU,
    HYBRID,
    CUSTOM
}
