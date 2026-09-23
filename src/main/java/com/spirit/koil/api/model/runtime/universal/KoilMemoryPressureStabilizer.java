package com.spirit.koil.api.model.runtime.universal;

import java.time.Duration;
import java.time.Instant;

/**
 * Stateful hysteresis for live memory pressure. Worsening pressure is accepted immediately;
 * recovery requires repeated samples above the relevant boundary so optional resources do not
 * flap as Minecraft, the compositor, and native inference transiently allocate/free UMA memory.
 */
public final class KoilMemoryPressureStabilizer {
    private static final long MIB = 1024L * 1024L;
    private static final long RECOVERY_MARGIN_BYTES = 256L * MIB;
    private static final int RECOVERY_SAMPLES = 3;

    public enum Trend { UNKNOWN, FALLING_FAST, FALLING, STABLE, RISING, RISING_FAST }

    public record Result(
            KoilMemoryPressureSnapshot raw,
            KoilMemoryPressureSnapshot effective,
            Trend trend,
            int recoverySamples
    ) {}

    private KoilMemoryPressureSnapshot previousRaw;
    private KoilMemoryPressureSnapshot.Pressure stablePressure = KoilMemoryPressureSnapshot.Pressure.UNKNOWN;
    private int recoverySamples;

    public synchronized Result update(KoilMemoryPressureSnapshot raw) {
        if (raw == null || !raw.known()) {
            previousRaw = raw;
            stablePressure = KoilMemoryPressureSnapshot.Pressure.UNKNOWN;
            recoverySamples = 0;
            return new Result(raw, raw, Trend.UNKNOWN, 0);
        }

        Trend trend = trend(previousRaw, raw);
        if (stablePressure == KoilMemoryPressureSnapshot.Pressure.UNKNOWN) {
            stablePressure = raw.pressure();
            recoverySamples = 0;
        } else {
            int rawSeverity = severity(raw.pressure());
            int stableSeverity = severity(stablePressure);
            if (rawSeverity > stableSeverity) {
                // Safety transitions are immediate.
                stablePressure = raw.pressure();
                recoverySamples = 0;
            } else if (rawSeverity < stableSeverity) {
                if (clearsRecoveryMargin(raw, stablePressure)) {
                    recoverySamples++;
                    if (recoverySamples >= RECOVERY_SAMPLES) {
                        stablePressure = raw.pressure();
                        recoverySamples = 0;
                    }
                } else {
                    recoverySamples = 0;
                }
            } else {
                recoverySamples = 0;
            }
        }

        KoilMemoryPressureSnapshot effective = effectiveSnapshot(raw, stablePressure);
        previousRaw = raw;
        return new Result(raw, effective, trend, recoverySamples);
    }

    public synchronized void reset() {
        previousRaw = null;
        stablePressure = KoilMemoryPressureSnapshot.Pressure.UNKNOWN;
        recoverySamples = 0;
    }

    private static KoilMemoryPressureSnapshot effectiveSnapshot(
            KoilMemoryPressureSnapshot raw,
            KoilMemoryPressureSnapshot.Pressure pressure
    ) {
        long budget = raw.inferenceBudgetBytes();
        // During recovery hysteresis, do not reopen optional allocations before the pressure tier
        // itself has recovered. The resident model remains valid; only discretionary growth stays shut.
        if (severity(pressure) > severity(raw.pressure())) {
            budget = 0L;
        }
        return new KoilMemoryPressureSnapshot(
                raw.installedBytes(), raw.availableBytes(), raw.safetyFloorBytes(), raw.reservedHostBytes(),
                budget, pressure, raw.phase(), raw.availableFraction(), raw.measuredAt());
    }

    private static boolean clearsRecoveryMargin(
            KoilMemoryPressureSnapshot raw,
            KoilMemoryPressureSnapshot.Pressure current
    ) {
        long available = raw.availableBytes();
        return switch (current) {
            case CRITICAL -> available >= raw.safetyFloorBytes() + RECOVERY_MARGIN_BYTES;
            case CONSTRAINED -> available >= raw.reservedHostBytes() + RECOVERY_MARGIN_BYTES;
            case MODERATE -> available >= raw.reservedHostBytes() + (1280L * MIB)
                    && raw.availableFraction() >= 0.35D;
            case HEALTHY, UNKNOWN -> true;
        };
    }

    private static Trend trend(KoilMemoryPressureSnapshot previous, KoilMemoryPressureSnapshot current) {
        if (previous == null || !previous.known() || current == null || !current.known()) return Trend.UNKNOWN;
        long delta = current.availableBytes() - previous.availableBytes();
        long base = Math.max(1L, previous.availableBytes());
        double fraction = delta / (double) base;
        if (delta <= -512L * MIB || fraction <= -0.15D) return Trend.FALLING_FAST;
        if (delta <= -128L * MIB || fraction <= -0.05D) return Trend.FALLING;
        if (delta >= 512L * MIB || fraction >= 0.15D) return Trend.RISING_FAST;
        if (delta >= 128L * MIB || fraction >= 0.05D) return Trend.RISING;
        return Trend.STABLE;
    }

    private static int severity(KoilMemoryPressureSnapshot.Pressure pressure) {
        if (pressure == null) return 0;
        return switch (pressure) {
            case UNKNOWN -> 0;
            case HEALTHY -> 1;
            case MODERATE -> 2;
            case CONSTRAINED -> 3;
            case CRITICAL -> 4;
        };
    }
}
