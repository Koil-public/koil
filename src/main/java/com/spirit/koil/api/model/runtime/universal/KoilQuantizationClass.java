package com.spirit.koil.api.model.runtime.universal;

/** Storage-level quantization classification. It does not imply runtime/operator support. */
public enum KoilQuantizationClass {
    FLOATING_POINT,
    MIXED_PRECISION,
    QUANTIZED,
    INTEGER_OR_PACKED,
    UNKNOWN
}
