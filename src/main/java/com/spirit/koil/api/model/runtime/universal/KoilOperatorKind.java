package com.spirit.koil.api.model.runtime.universal;

/** Reusable operator vocabulary for architecture descriptions and compatibility diagnostics. */
public enum KoilOperatorKind {
    EMBEDDING,
    RMS_NORM,
    LAYER_NORM,
    GEMM,
    GEMV,
    ROPE,
    ATTENTION,
    FLASH_ATTENTION,
    GROUPED_QUERY_ATTENTION,
    MULTI_QUERY_ATTENTION,
    MULTI_HEAD_ATTENTION,
    MULTI_HEAD_LATENT_ATTENTION,
    SLIDING_WINDOW_ATTENTION,
    LINEAR_ATTENTION,
    SHORT_CONVOLUTION,
    STATE_SPACE_SCAN,
    DELTA_NET,
    MOE_ROUTING,
    EXPERT_EXECUTION,
    GATING,
    ACTIVATION,
    OUTPUT_PROJECTION
}
