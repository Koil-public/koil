package com.spirit.koil.api.model.runtime.universal;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.time.Instant;

/**
 * Computes a conservative runtime-neutral memory budget. Launch snapshots are reusable policy;
 * runtime snapshots describe post-load headroom and must not overwrite the next-launch budget.
 */
public final class KoilMemoryBudgetPlanner {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private KoilMemoryBudgetPlanner() {}

    public static KoilMemoryPressureSnapshot capture(KoilHardwareProfile hardware) {
        return capture(hardware, KoilMemoryPressureSnapshot.Phase.LAUNCH);
    }

    public static KoilMemoryPressureSnapshot captureRuntime(KoilHardwareProfile hardware) {
        return capture(hardware, KoilMemoryPressureSnapshot.Phase.RUNTIME);
    }

    private static KoilMemoryPressureSnapshot capture(
            KoilHardwareProfile hardware,
            KoilMemoryPressureSnapshot.Phase phase
    ) {
        long installed = hardware == null ? 0L : hardware.installedMemoryBytes();
        long available = liveAvailableMemoryBytes(hardware == null ? 0L : hardware.availableMemoryBytes());
        if (installed <= 0L) installed = liveInstalledMemoryBytes();
        return from(installed, available, phase);
    }

    public static KoilMemoryPressureSnapshot from(long installedBytes, long availableBytes) {
        return from(installedBytes, availableBytes, KoilMemoryPressureSnapshot.Phase.LAUNCH);
    }

    public static KoilMemoryPressureSnapshot from(
            long installedBytes,
            long availableBytes,
            KoilMemoryPressureSnapshot.Phase phase
    ) {
        long installed = Math.max(0L, installedBytes);
        long available = Math.max(0L, availableBytes);
        KoilMemoryPressureSnapshot.Phase actualPhase = phase == null
                ? KoilMemoryPressureSnapshot.Phase.LAUNCH : phase;
        if (available <= 0L) {
            return new KoilMemoryPressureSnapshot(installed, 0L, 0L, 0L, 0L,
                    KoilMemoryPressureSnapshot.Pressure.UNKNOWN, actualPhase, 0.0D, Instant.now());
        }

        // The safety floor marks immediate host pressure. The larger reserve is desired headroom
        // for Minecraft, drivers, native allocations, and transient inference work.
        long proportionalFloor = installed > 0L ? Math.round(installed * 0.05D) : 0L;
        long safetyFloor = Math.max(768L * MIB, proportionalFloor);
        safetyFloor = Math.min(1536L * MIB, safetyFloor);

        long proportionalReserve = installed > 0L ? Math.round(installed * 0.12D) : 0L;
        long reserve = Math.max(1280L * MIB, proportionalReserve);
        reserve = Math.min(4L * GIB, reserve);
        reserve = Math.max(reserve, safetyFloor);

        long budget = Math.max(0L, available - reserve);
        double fraction = installed > 0L ? available / (double) installed : 0.0D;

        KoilMemoryPressureSnapshot.Pressure pressure;
        if (available <= safetyFloor) {
            pressure = KoilMemoryPressureSnapshot.Pressure.CRITICAL;
        } else if (available <= reserve) {
            pressure = KoilMemoryPressureSnapshot.Pressure.CONSTRAINED;
        } else if (available <= reserve + 1024L * MIB || (installed > 0L && fraction < 0.32D)) {
            pressure = KoilMemoryPressureSnapshot.Pressure.MODERATE;
        } else {
            pressure = KoilMemoryPressureSnapshot.Pressure.HEALTHY;
        }
        return new KoilMemoryPressureSnapshot(installed, available, safetyFloor, reserve, budget,
                pressure, actualPhase, fraction, Instant.now());
    }

    private static long liveAvailableMemoryBytes(long fallback) {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof OperatingSystemMXBean extended) {
                long value = extended.getFreeMemorySize();
                if (value > 0L) return value;
            }
        } catch (Throwable ignored) {}
        return Math.max(0L, fallback);
    }

    private static long liveInstalledMemoryBytes() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof OperatingSystemMXBean extended) {
                return Math.max(0L, extended.getTotalMemorySize());
            }
        } catch (Throwable ignored) {}
        return 0L;
    }
}
