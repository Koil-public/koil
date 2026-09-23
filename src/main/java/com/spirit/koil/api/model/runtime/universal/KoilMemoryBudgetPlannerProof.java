package com.spirit.koil.api.model.runtime.universal;

/** Dependency-light invariants for adaptive host-memory budgeting. */
public final class KoilMemoryBudgetPlannerProof {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private KoilMemoryBudgetPlannerProof() {}

    public static void main(String[] args) {
        KoilMemoryPressureSnapshot critical = KoilMemoryBudgetPlanner.from(16L * GIB, 774L * MIB);
        require(critical.pressure() == KoilMemoryPressureSnapshot.Pressure.CRITICAL, "774 MiB must be critical");
        require(critical.inferenceBudgetBytes() == 0L, "critical pressure must expose no discretionary budget");

        KoilMemoryPressureSnapshot resident = KoilMemoryBudgetPlanner.from(
                16L * GIB, 1430L * MIB, KoilMemoryPressureSnapshot.Phase.RUNTIME);
        require(resident.pressure() == KoilMemoryPressureSnapshot.Pressure.CONSTRAINED,
                "1.43 GiB live post-load headroom should be constrained, not critical");
        require(resident.inferenceBudgetBytes() == 0L,
                "resident runtime may have no additional discretionary allocation budget");
        require(resident.phase() == KoilMemoryPressureSnapshot.Phase.RUNTIME,
                "runtime evidence must retain its phase");

        KoilMemoryPressureSnapshot constrained = KoilMemoryBudgetPlanner.from(16L * GIB, 2400L * MIB);
        require(constrained.pressure() == KoilMemoryPressureSnapshot.Pressure.MODERATE,
                "2.4 GiB should preserve reserve but remain under moderate pressure");
        require(constrained.inferenceBudgetBytes() > 0L, "moderate memory may retain a bounded budget");

        KoilMemoryPressureSnapshot moderate = KoilMemoryBudgetPlanner.from(16L * GIB, 4606L * MIB);
        require(moderate.pressure() == KoilMemoryPressureSnapshot.Pressure.MODERATE, "4.6 GiB should be moderate on 16 GiB host");
        require(moderate.inferenceBudgetBytes() < moderate.availableBytes(), "budget must preserve host reserve");

        KoilMemoryPressureSnapshot healthy = KoilMemoryBudgetPlanner.from(16L * GIB, 8L * GIB);
        require(healthy.pressure() == KoilMemoryPressureSnapshot.Pressure.HEALTHY, "8 GiB should be healthy on 16 GiB host");
        require(healthy.inferenceBudgetBytes() > moderate.inferenceBudgetBytes(), "budget should grow with headroom");
        System.out.println("Koil memory budget planner proof passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
