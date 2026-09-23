package com.spirit.koil.api.model.runtime.universal;

/** Backend-neutral execution stage used by the normalized model graph. */
public enum KoilGraphStage {
    INPUT,
    ENCODER,
    DECODER,
    BLOCK,
    OUTPUT,
    SPECULATIVE,
    UNKNOWN
}
