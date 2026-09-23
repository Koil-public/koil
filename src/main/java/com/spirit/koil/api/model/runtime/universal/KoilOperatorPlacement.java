package com.spirit.koil.api.model.runtime.universal;

/** Placement policy for non-weight operator work and temporary compute buffers. */
public enum KoilOperatorPlacement {
    AUTOMATIC,
    HOST,
    ACCELERATOR,
    SPLIT
}
