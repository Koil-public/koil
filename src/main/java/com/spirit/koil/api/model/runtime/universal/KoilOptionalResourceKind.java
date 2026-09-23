package com.spirit.koil.api.model.runtime.universal;

/** Optional inference resources that may consume discretionary memory beyond the core model runtime. */
public enum KoilOptionalResourceKind {
    CACHE_GROWTH,
    SPECULATION,
    EXPERT_CACHE,
    TENSOR_RESIDENCY,
    CONVERSION_WORKSPACE
}
