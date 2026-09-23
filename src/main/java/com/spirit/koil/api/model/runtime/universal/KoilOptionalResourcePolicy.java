package com.spirit.koil.api.model.runtime.universal;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Converts universal memory pressure into non-reserving ceilings for optional inference resources.
 * These ceilings are admission guidance only. Adapters/features must still perform their own
 * capability, correctness, and live-allocation checks before consuming memory.
 */
public final class KoilOptionalResourcePolicy {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private KoilOptionalResourcePolicy() {}

    public static Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> evaluate(
            KoilMemoryPressureSnapshot snapshot
    ) {
        EnumMap<KoilOptionalResourceKind, KoilOptionalResourceAdmission> result =
                new EnumMap<>(KoilOptionalResourceKind.class);
        if (snapshot == null || !snapshot.known()) {
            for (KoilOptionalResourceKind kind : KoilOptionalResourceKind.values()) {
                result.put(kind, denied(kind, "memory pressure is unknown"));
            }
            return Map.copyOf(result);
        }

        long budget = snapshot.discretionaryBytes();
        KoilMemoryPressureSnapshot.Pressure pressure = snapshot.pressure();
        if (pressure == KoilMemoryPressureSnapshot.Pressure.CRITICAL || budget <= 0L) {
            for (KoilOptionalResourceKind kind : KoilOptionalResourceKind.values()) {
                result.put(kind, denied(kind, pressure == KoilMemoryPressureSnapshot.Pressure.CRITICAL
                        ? "critical host/unified-memory pressure" : "no discretionary memory beyond host reserve"));
            }
            return Map.copyOf(result);
        }

        // Per-feature ceilings are independent upper bounds, not simultaneous reservations.
        put(result, snapshot, KoilOptionalResourceKind.CACHE_GROWTH, 128L * MIB, 0.45D, 1L * GIB);
        put(result, snapshot, KoilOptionalResourceKind.SPECULATION, 512L * MIB, 0.25D, 2L * GIB);
        put(result, snapshot, KoilOptionalResourceKind.EXPERT_CACHE, 768L * MIB, 0.35D, 4L * GIB);
        put(result, snapshot, KoilOptionalResourceKind.TENSOR_RESIDENCY, 512L * MIB, 0.50D, 4L * GIB);
        put(result, snapshot, KoilOptionalResourceKind.CONVERSION_WORKSPACE, 1024L * MIB, 0.50D, 4L * GIB);
        return Map.copyOf(result);
    }

    public static Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> fromPlan(
            KoilExecutionPlan plan,
            String prefix
    ) {
        EnumMap<KoilOptionalResourceKind, KoilOptionalResourceAdmission> result =
                new EnumMap<>(KoilOptionalResourceKind.class);
        String p = prefix == null ? "optional" : prefix;
        Map<String, String> decisions = plan == null ? Map.of() : plan.decisions();
        for (KoilOptionalResourceKind kind : KoilOptionalResourceKind.values()) {
            String key = kind.name().toLowerCase(Locale.ROOT);
            String allowedKey = p + "." + key + ".allowed";
            String ceilingKey = p + "." + key + ".ceilingBytes";
            String reasonKey = p + "." + key + ".reason";
            if (!decisions.containsKey(allowedKey) && !decisions.containsKey(ceilingKey) && !decisions.containsKey(reasonKey)) {
                continue;
            }
            boolean allowed = Boolean.parseBoolean(decisions.getOrDefault(allowedKey, "false"));
            long ceiling = parseLong(decisions.get(ceilingKey));
            String reason = decisions.getOrDefault(reasonKey,
                    allowed ? "admitted by execution plan" : "not admitted by execution plan");
            result.put(kind, new KoilOptionalResourceAdmission(kind, allowed, ceiling, reason));
        }
        return Map.copyOf(result);
    }

    public static void appendDecisions(
            Map<String, String> target,
            String prefix,
            KoilMemoryPressureSnapshot snapshot
    ) {
        if (target == null) return;
        String p = prefix == null ? "optional" : prefix;
        Map<KoilOptionalResourceKind, KoilOptionalResourceAdmission> admissions = evaluate(snapshot);
        for (KoilOptionalResourceKind kind : KoilOptionalResourceKind.values()) {
            KoilOptionalResourceAdmission admission = admissions.get(kind);
            String key = kind.name().toLowerCase(Locale.ROOT);
            target.put(p + "." + key + ".allowed", Boolean.toString(admission.allowed()));
            target.put(p + "." + key + ".ceilingBytes", Long.toString(admission.ceilingBytes()));
            target.put(p + "." + key + ".reason", admission.reason());
        }
    }

    private static void put(
            EnumMap<KoilOptionalResourceKind, KoilOptionalResourceAdmission> target,
            KoilMemoryPressureSnapshot snapshot,
            KoilOptionalResourceKind kind,
            long minimumBudget,
            double fraction,
            long cap
    ) {
        long budget = snapshot.discretionaryBytes();
        boolean constrained = snapshot.pressure() == KoilMemoryPressureSnapshot.Pressure.CONSTRAINED;
        long required = constrained ? Math.max(minimumBudget, minimumBudget * 2L) : minimumBudget;
        if (budget < required) {
            target.put(kind, denied(kind, "discretionary budget below " + (required / MIB) + " MiB admission threshold"));
            return;
        }
        long ceiling = Math.min(cap, Math.max(minimumBudget, (long) Math.floor(budget * fraction)));
        ceiling = Math.min(ceiling, budget);
        target.put(kind, new KoilOptionalResourceAdmission(
                kind, ceiling > 0L, ceiling,
                "admitted under " + snapshot.pressure().name().toLowerCase(Locale.ROOT) + " memory pressure"));
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static KoilOptionalResourceAdmission denied(KoilOptionalResourceKind kind, String reason) {
        return new KoilOptionalResourceAdmission(kind, false, 0L, reason);
    }
}
