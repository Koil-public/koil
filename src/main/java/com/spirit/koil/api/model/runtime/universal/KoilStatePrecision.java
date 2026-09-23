package com.spirit.koil.api.model.runtime.universal;

/**
 * Backend-neutral numeric format requested for persistent model state.
 * Adapters map these semantic formats to their native runtime representation.
 */
public enum KoilStatePrecision {
    AUTO,
    FP32,
    FP16,
    BF16,
    Q8_BLOCK,
    Q4_BLOCK
}
