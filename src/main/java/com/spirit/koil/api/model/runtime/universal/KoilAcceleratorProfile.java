package com.spirit.koil.api.model.runtime.universal;

/** Observed accelerator evidence. A compiled backend alone must not create one of these. */
public record KoilAcceleratorProfile(
        String id,
        KoilRuntimeBackend backend,
        String name,
        long memoryBytes,
        boolean sharedMemory,
        boolean unifiedMemory,
        String evidence
) {
    public KoilAcceleratorProfile {
        id = safe(id);
        backend = backend == null ? KoilRuntimeBackend.UNKNOWN : backend;
        name = safe(name);
        memoryBytes = Math.max(0L, memoryBytes);
        evidence = safe(evidence);
    }
    private static String safe(String value) { return value == null ? "" : value.strip(); }
}
