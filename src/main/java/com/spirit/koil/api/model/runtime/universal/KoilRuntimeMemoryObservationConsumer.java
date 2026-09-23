package com.spirit.koil.api.model.runtime.universal;

/**
 * Optional execution-adapter hook for consuming stabilized live-memory observations.
 * Implementations may persist bounded evidence for future launch planning, but must never
 * mutate active correctness-critical model state from this callback.
 */
public interface KoilRuntimeMemoryObservationConsumer {
    void observeKoilRuntimeMemory(KoilMemoryPressureStabilizer.Result observation);
}
