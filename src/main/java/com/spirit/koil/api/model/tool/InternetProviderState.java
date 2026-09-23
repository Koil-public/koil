package com.spirit.koil.api.model.tool;

/** Current operational state; retained as deterministic metadata, never only in retrieval memory. */
public enum InternetProviderState {
    READY,
    UNAVAILABLE,
    AUTH_REQUIRED,
    RATE_LIMITED,
    FAILED,
    DEGRADED
}
