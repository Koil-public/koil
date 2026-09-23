package com.spirit.koil.api.model.cache;

import java.lang.management.ManagementFactory;

/**
 * Conservative cache budget derived from host memory. llama.cpp's shared RAM
 * prompt cache is intentionally not budgeted here because Koil currently uses
 * explicit slot ownership and persistent snapshots instead.
 */
public record ModelCacheBudget(
        ModelCacheProfile profile,
        long physicalMemoryBytes,
        long jvmMaximumBytes,
        long minecraftReserveBytes,
        long osReserveBytes,
        long diskSnapshotBudgetBytes,
        int maximumSnapshotsPerSlot
) {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    public ModelCacheBudget {
        profile = profile == null ? ModelCacheProfile.AUTO : profile;
        physicalMemoryBytes = Math.max(0L, physicalMemoryBytes);
        jvmMaximumBytes = Math.max(0L, jvmMaximumBytes);
        minecraftReserveBytes = Math.max(512L * MIB, minecraftReserveBytes);
        osReserveBytes = Math.max(512L * MIB, osReserveBytes);
        diskSnapshotBudgetBytes = Math.max(256L * MIB, diskSnapshotBudgetBytes);
        maximumSnapshotsPerSlot = Math.max(1, Math.min(16, maximumSnapshotsPerSlot));
    }

    /**
     * Remaining host-memory headroom after Koil's explicit safety reserves and
     * the selected model allocation. This is intentionally advisory while the
     * upstream shared RAM prompt cache remains disabled.
     */
    public long availableMemoryCacheBytes(long modelAllocationBytes) {
        if (this.physicalMemoryBytes <= 0L) return 0L;
        long remaining = this.physicalMemoryBytes;
        remaining = subtractFloor(remaining, this.minecraftReserveBytes);
        remaining = subtractFloor(remaining, this.jvmMaximumBytes);
        remaining = subtractFloor(remaining, this.osReserveBytes);
        remaining = subtractFloor(remaining, Math.max(0L, modelAllocationBytes));
        return remaining;
    }

    public static ModelCacheBudget detect(ModelCacheProfile requested) {
        ModelCacheProfile effective = requested == null ? ModelCacheProfile.AUTO : requested;
        long physical = physicalMemory();
        long heap = Runtime.getRuntime().maxMemory();
        if (effective == ModelCacheProfile.AUTO) {
            long basis = physical > 0L ? physical : Math.max(heap * 3L, 8L * GIB);
            effective = basis < 12L * GIB ? ModelCacheProfile.LOW_MEMORY
                    : basis < 24L * GIB ? ModelCacheProfile.BALANCED
                    : basis < 48L * GIB ? ModelCacheProfile.AGGRESSIVE
                    : ModelCacheProfile.MAXIMUM;
        }
        long minecraftReserve = switch (effective) {
            case LOW_MEMORY -> 2L * GIB;
            case BALANCED -> 3L * GIB;
            case AGGRESSIVE -> 4L * GIB;
            case MAXIMUM -> 5L * GIB;
            case AUTO -> 3L * GIB;
        };
        long osReserve = switch (effective) {
            case LOW_MEMORY -> 2L * GIB;
            case BALANCED -> 3L * GIB;
            case AGGRESSIVE, MAXIMUM -> 4L * GIB;
            case AUTO -> 3L * GIB;
        };
        long snapshots = switch (effective) {
            case LOW_MEMORY -> 1L * GIB;
            case BALANCED -> 4L * GIB;
            case AGGRESSIVE -> 12L * GIB;
            case MAXIMUM -> 32L * GIB;
            case AUTO -> 4L * GIB;
        };
        if (physical > 0L) {
            long conservativeHostCap = Math.max(512L * MIB, physical - minecraftReserve - osReserve - heap);
            snapshots = Math.min(snapshots, Math.max(512L * MIB, conservativeHostCap * 2L));
        }
        int perSlot = switch (effective) {
            case LOW_MEMORY -> 1;
            case BALANCED -> 2;
            case AGGRESSIVE -> 4;
            case MAXIMUM -> 8;
            case AUTO -> 2;
        };
        return new ModelCacheBudget(effective, physical, heap, minecraftReserve, osReserve, snapshots, perSlot);
    }

    private static long subtractFloor(long value, long subtraction) {
        if (value <= 0L || subtraction <= 0L) return Math.max(0L, value);
        return subtraction >= value ? 0L : value - subtraction;
    }

    private static long physicalMemory() {
        try {
            Object bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                return Math.max(0L, sun.getTotalMemorySize());
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }
}
