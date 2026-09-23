package com.spirit.koil.api.model.runtime.universal;

public final class KoilMemoryPressureStabilizerProof {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    public static void main(String[] args) {
        KoilMemoryPressureStabilizer stabilizer = new KoilMemoryPressureStabilizer();

        var moderate = KoilMemoryBudgetPlanner.from(16L * GIB, 4L * GIB,
                KoilMemoryPressureSnapshot.Phase.RUNTIME);
        require(stabilizer.update(moderate).effective().pressure() == KoilMemoryPressureSnapshot.Pressure.MODERATE,
                "initial moderate pressure should be accepted");

        var critical = KoilMemoryBudgetPlanner.from(16L * GIB, 600L * MIB,
                KoilMemoryPressureSnapshot.Phase.RUNTIME);
        var drop = stabilizer.update(critical);
        require(drop.effective().pressure() == KoilMemoryPressureSnapshot.Pressure.CRITICAL,
                "worsening pressure must be immediate");
        require(drop.trend() == KoilMemoryPressureStabilizer.Trend.FALLING_FAST,
                "large pressure drop should be visible as falling fast");

        var recovered = KoilMemoryBudgetPlanner.from(16L * GIB, 3L * GIB,
                KoilMemoryPressureSnapshot.Phase.RUNTIME);
        for (int i = 0; i < 2; i++) {
            var held = stabilizer.update(recovered);
            require(held.effective().pressure() == KoilMemoryPressureSnapshot.Pressure.CRITICAL,
                    "recovery must be held during hysteresis");
            require(held.effective().inferenceBudgetBytes() == 0L,
                    "optional memory must remain closed during recovery hysteresis");
        }
        var release = stabilizer.update(recovered);
        require(release.effective().pressure() != KoilMemoryPressureSnapshot.Pressure.CRITICAL,
                "third sustained recovery sample should release critical pressure");

        stabilizer.reset();
        var constrained = KoilMemoryBudgetPlanner.from(16L * GIB, 1200L * MIB,
                KoilMemoryPressureSnapshot.Phase.RUNTIME);
        require(stabilizer.update(constrained).effective().pressure() == KoilMemoryPressureSnapshot.Pressure.CONSTRAINED,
                "constrained baseline expected");
        var nearBoundary = KoilMemoryBudgetPlanner.from(16L * GIB, 1900L * MIB,
                KoilMemoryPressureSnapshot.Phase.RUNTIME);
        for (int i = 0; i < 5; i++) {
            require(stabilizer.update(nearBoundary).effective().pressure() == KoilMemoryPressureSnapshot.Pressure.CONSTRAINED,
                    "recovery without margin must not flap pressure state");
        }

        System.out.println("Koil memory pressure stabilizer proof passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
