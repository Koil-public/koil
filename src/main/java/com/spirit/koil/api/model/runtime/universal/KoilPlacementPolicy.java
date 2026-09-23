package com.spirit.koil.api.model.runtime.universal;

/** Runtime-neutral model/tensor placement policy chosen by Koil. */
public enum KoilPlacementPolicy {
    AUTOMATIC,
    CPU,
    GPU,
    HYBRID
}
