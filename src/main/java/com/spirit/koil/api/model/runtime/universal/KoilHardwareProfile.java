package com.spirit.koil.api.model.runtime.universal;

import java.util.List;
import java.util.Set;

/** Stable + transient hardware facts consumed by execution planning. */
public record KoilHardwareProfile(
        String fingerprint,
        String operatingSystem,
        String architecture,
        int logicalCpuCount,
        int physicalCpuCount,
        long installedMemoryBytes,
        long availableMemoryBytes,
        Set<String> cpuFeatures,
        List<KoilAcceleratorProfile> accelerators,
        KoilRuntimeArtifactInventory runtimeInventory
) {
    public KoilHardwareProfile {
        fingerprint = safe(fingerprint);
        operatingSystem = safe(operatingSystem);
        architecture = safe(architecture);
        logicalCpuCount = Math.max(1, logicalCpuCount);
        physicalCpuCount = Math.max(0, physicalCpuCount);
        installedMemoryBytes = Math.max(0L, installedMemoryBytes);
        availableMemoryBytes = Math.max(0L, availableMemoryBytes);
        cpuFeatures = cpuFeatures == null ? Set.of() : Set.copyOf(cpuFeatures);
        accelerators = accelerators == null ? List.of() : List.copyOf(accelerators);
    }
    public boolean acceleratorObserved() { return !accelerators.isEmpty(); }
    private static String safe(String value) { return value == null ? "" : value.strip(); }
}
