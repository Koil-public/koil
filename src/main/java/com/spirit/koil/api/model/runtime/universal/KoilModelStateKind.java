package com.spirit.koil.api.model.runtime.universal;

/** Persistent state that a model session may own. KV cache is only one possible state kind. */
public enum KoilModelStateKind {
    ATTENTION_KV,
    SSM,
    CONVOLUTION,
    RECURRENT,
    EXPERT_ROUTING,
    ENCODER,
    CROSS_ATTENTION,
    SPECULATIVE
}
