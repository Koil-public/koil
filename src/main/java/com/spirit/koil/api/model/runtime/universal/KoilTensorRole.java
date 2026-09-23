package com.spirit.koil.api.model.runtime.universal;

/** Semantic tensor roles resolved from artifact tensor names and graph context. */
public enum KoilTensorRole {
    TOKEN_EMBEDDING,
    OUTPUT_NORM,
    OUTPUT_PROJECTION,
    ATTENTION_NORM,
    ATTENTION_QUERY,
    ATTENTION_KEY,
    ATTENTION_VALUE,
    ATTENTION_OUTPUT,
    FFN_NORM,
    FFN_GATE,
    FFN_UP,
    FFN_DOWN,
    MOE_ROUTER,
    EXPERT_GATE,
    EXPERT_UP,
    EXPERT_DOWN,
    CONVOLUTION,
    SSM_INPUT,
    SSM_OUTPUT,
    SSM_STATE,
    CROSS_ATTENTION_QUERY,
    CROSS_ATTENTION_KEY,
    CROSS_ATTENTION_VALUE,
    CROSS_ATTENTION_OUTPUT,
    UNKNOWN
}
