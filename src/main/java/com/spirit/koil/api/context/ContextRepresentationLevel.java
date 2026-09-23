package com.spirit.koil.api.context;

/** Progressive model-facing density; canonical artifact bytes remain outside this policy. */
public enum ContextRepresentationLevel {
    L0_EXACT,
    L1_LOSSLESS,
    L2_STRUCTURAL,
    L3_SEMANTIC,
    L4_AGGRESSIVE,
    L5_EVICTED_RETRIEVABLE
}
