package com.spirit.koil.api.model.runtime.universal;

/** Broad computational families used for planning without privileging model brands. */
public enum KoilArchitectureCategory {
    DECODER_TRANSFORMER,
    ENCODER_DECODER_TRANSFORMER,
    MIXTURE_OF_EXPERTS,
    HYBRID_ATTENTION,
    LINEAR_ATTENTION,
    STATE_SPACE,
    RECURRENT,
    CONVOLUTION_HYBRID,
    UNKNOWN
}
