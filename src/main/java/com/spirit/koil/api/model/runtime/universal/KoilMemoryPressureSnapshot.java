package com.spirit.koil.api.model.runtime.universal;

import java.time.Instant;

/** Runtime-neutral host/unified-memory pressure snapshot used by planning and observation. */
public record KoilMemoryPressureSnapshot(
        long installedBytes,
        long availableBytes,
        long safetyFloorBytes,
        long reservedHostBytes,
        long inferenceBudgetBytes,
        Pressure pressure,
        Phase phase,
        double availableFraction,
        Instant measuredAt
) {
    public enum Pressure { UNKNOWN, CRITICAL, CONSTRAINED, MODERATE, HEALTHY }
    public enum Phase { LAUNCH, RUNTIME }

    public KoilMemoryPressureSnapshot {
        installedBytes = Math.max(0L, installedBytes);
        availableBytes = Math.max(0L, availableBytes);
        safetyFloorBytes = Math.max(0L, safetyFloorBytes);
        reservedHostBytes = Math.max(safetyFloorBytes, reservedHostBytes);
        inferenceBudgetBytes = Math.max(0L, inferenceBudgetBytes);
        pressure = pressure == null ? Pressure.UNKNOWN : pressure;
        phase = phase == null ? Phase.LAUNCH : phase;
        availableFraction = Double.isFinite(availableFraction)
                ? Math.max(0.0D, Math.min(1.0D, availableFraction)) : 0.0D;
        measuredAt = measuredAt == null ? Instant.now() : measuredAt;
    }

    public boolean known() { return availableBytes > 0L; }

    /** Additional memory that may be allocated without consuming the desired host reserve. */
    public long discretionaryBytes() { return inferenceBudgetBytes; }
}
