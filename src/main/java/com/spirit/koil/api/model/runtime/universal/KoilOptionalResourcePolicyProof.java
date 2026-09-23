package com.spirit.koil.api.model.runtime.universal;

import java.util.Map;

/** Dependency-light executable proof for optional resource admission. */
public final class KoilOptionalResourcePolicyProof {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    public static void main(String[] args) {
        var critical = KoilMemoryBudgetPlanner.from(16L * GIB, 774L * MIB, KoilMemoryPressureSnapshot.Phase.RUNTIME);
        var criticalAdmissions = KoilOptionalResourcePolicy.evaluate(critical);
        require(criticalAdmissions.values().stream().noneMatch(KoilOptionalResourceAdmission::allowed),
                "critical pressure must deny optional allocations");

        var live = KoilMemoryBudgetPlanner.from(16L * GIB, 2899L * MIB, KoilMemoryPressureSnapshot.Phase.RUNTIME);
        var liveAdmissions = KoilOptionalResourcePolicy.evaluate(live);
        require(liveAdmissions.get(KoilOptionalResourceKind.CACHE_GROWTH).allowed(),
                "observed 2.9 GiB live headroom should admit bounded cache growth");
        require(!liveAdmissions.get(KoilOptionalResourceKind.CONVERSION_WORKSPACE).allowed(),
                "observed 2.9 GiB live headroom should not admit a large conversion workspace");

        var launch = KoilMemoryBudgetPlanner.from(16L * GIB, 4195L * MIB, KoilMemoryPressureSnapshot.Phase.LAUNCH);
        var launchAdmissions = KoilOptionalResourcePolicy.evaluate(launch);
        require(launchAdmissions.get(KoilOptionalResourceKind.SPECULATION).allowed(),
                "4.2 GiB launch headroom should admit bounded speculation memory");
        require(launchAdmissions.get(KoilOptionalResourceKind.TENSOR_RESIDENCY).allowed(),
                "4.2 GiB launch headroom should admit bounded tensor residency");
        for (KoilOptionalResourceAdmission value : launchAdmissions.values()) {
            require(value.ceilingBytes() <= launch.discretionaryBytes(), "ceiling cannot exceed discretionary budget");
        }
        System.out.println("Koil optional resource policy proof passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
