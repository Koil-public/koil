package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilMemoryPressureSnapshot;
import com.spirit.koil.api.model.runtime.universal.KoilMemoryPressureStabilizer;

/**
 * Latches a conservative response when the model is already at the context floor and
 * critical resident pressure is dominated by the Minecraft JVM or the wider system.
 *
 * This controller never changes user-request compute geometry. It only decides whether
 * optional/background model work should remain blocked and whether further model-side
 * degradation should be frozen because the native runtime is not the dominant resident.
 */
final class LlamaCppResidentPressureController {
    enum Mode {
        NORMAL,
        JVM_DOMINATED_CONSERVATIVE,
        SYSTEM_DOMINATED_CONSERVATIVE,
        NATIVE_RUNTIME_PRESSURE,
        COMBINED_PRESSURE
    }

    record Decision(
            Mode mode,
            boolean backgroundModelWorkAllowed,
            boolean modelDegradationAllowed,
            boolean preserveTunedUserInference,
            int recoverySamples,
            String reason
    ) {
        static Decision normal() {
            return new Decision(Mode.NORMAL, true, true, true, 0, "no resident pressure response is active");
        }

        String modeId() { return mode.name().toLowerCase(java.util.Locale.ROOT); }
    }

    private static final int RECOVERY_SAMPLES_REQUIRED = 3;
    private static final long RECOVERY_MARGIN_BYTES = 512L * 1024L * 1024L;

    private Decision current = Decision.normal();
    private int recoverySamples;

    synchronized Decision observe(
            LlamaCppProcessMemoryAttribution.Snapshot attribution,
            KoilMemoryPressureStabilizer.Result observation
    ) {
        if (observation == null || observation.effective() == null) return current;
        KoilMemoryPressureSnapshot memory = observation.effective();
        String classification = attribution == null ? "unknown" : attribution.classification();

        Decision newlyCritical = criticalDecision(classification, memory);
        if (newlyCritical != null) {
            recoverySamples = 0;
            current = newlyCritical;
            return current;
        }

        if (current.mode() == Mode.NORMAL) return current;

        boolean recoveredPressure = memory.pressure() != KoilMemoryPressureSnapshot.Pressure.CRITICAL;
        boolean recoveredHeadroom = memory.availableBytes() >= memory.safetyFloorBytes() + RECOVERY_MARGIN_BYTES;
        if (recoveredPressure && recoveredHeadroom) {
            recoverySamples++;
            if (recoverySamples >= RECOVERY_SAMPLES_REQUIRED) {
                current = Decision.normal();
                recoverySamples = 0;
            } else {
                current = new Decision(
                        current.mode(), false, current.modelDegradationAllowed(), true, recoverySamples,
                        "resident pressure is recovering; holding conservative background policy until "
                                + RECOVERY_SAMPLES_REQUIRED + " sustained recovery samples"
                );
            }
        } else {
            recoverySamples = 0;
        }
        return current;
    }

    synchronized Decision current() { return current; }

    synchronized void reset() {
        current = Decision.normal();
        recoverySamples = 0;
    }

    private static Decision criticalDecision(String classification, KoilMemoryPressureSnapshot memory) {
        if (memory.pressure() != KoilMemoryPressureSnapshot.Pressure.CRITICAL) return null;
        return switch (classification == null ? "unknown" : classification) {
            case "minecraft_jvm_heavy" -> new Decision(
                    Mode.JVM_DOMINATED_CONSERVATIVE, false, false, true, 0,
                    "critical pressure is JVM-dominated; freeze model-side degradation and block optional background model work"
            );
            case "external_or_shared_system_pressure" -> new Decision(
                    Mode.SYSTEM_DOMINATED_CONSERVATIVE, false, false, true, 0,
                    "critical pressure is external/shared-system dominated; preserve tuned user inference and block optional background model work"
            );
            case "native_runtime_heavy" -> new Decision(
                    Mode.NATIVE_RUNTIME_PRESSURE, false, true, true, 0,
                    "critical pressure is native-runtime dominated; background work is blocked and future model-side adaptation remains eligible"
            );
            case "combined_runtime_pressure", "mixed_resident_pressure" -> new Decision(
                    Mode.COMBINED_PRESSURE, false, true, true, 0,
                    "critical pressure is shared between runtimes; block optional background model work while preserving user-request inference"
            );
            default -> null;
        };
    }
}
