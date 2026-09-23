package com.spirit.koil.api.model.runtime.universal;

/** Placement of persistent model state such as KV, SSM, convolution, or recurrent state. */
public enum KoilStatePlacement {
    AUTOMATIC,
    HOST,
    ACCELERATOR,
    SPLIT
}
