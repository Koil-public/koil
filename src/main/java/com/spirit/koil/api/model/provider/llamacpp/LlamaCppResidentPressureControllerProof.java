package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.runtime.universal.KoilMemoryPressureSnapshot;
import com.spirit.koil.api.model.runtime.universal.KoilMemoryPressureStabilizer;

/** Dependency-light proof for the resident-pressure response latch. */
public final class LlamaCppResidentPressureControllerProof {
    private static final long MIB = 1024L * 1024L;

    public static void main(String[] args) {
        LlamaCppResidentPressureController controller = new LlamaCppResidentPressureController();
        var nativeMem = new LlamaCppProcessMemoryAttribution.ProcessMemory(120L * MIB, 113L * MIB, "test");
        var jvmMem = new LlamaCppProcessMemoryAttribution.ProcessMemory(3300L * MIB, 3160L * MIB, "test");
        var attribution = new LlamaCppProcessMemoryAttribution.Snapshot(
                nativeMem, jvmMem, "minecraft_jvm_heavy", "proof");

        var critical = result(600, 768, KoilMemoryPressureSnapshot.Pressure.CRITICAL);
        var decision = controller.observe(attribution, critical);
        require(decision.mode() == LlamaCppResidentPressureController.Mode.JVM_DOMINATED_CONSERVATIVE, "JVM mode");
        require(!decision.backgroundModelWorkAllowed(), "background blocked");
        require(!decision.modelDegradationAllowed(), "model degradation frozen");
        require(decision.preserveTunedUserInference(), "user inference preserved");

        var recovering = result(1400, 768, KoilMemoryPressureSnapshot.Pressure.MODERATE);
        decision = controller.observe(LlamaCppProcessMemoryAttribution.Snapshot.unknown("recovery"), recovering);
        require(decision.mode() != LlamaCppResidentPressureController.Mode.NORMAL, "recovery sample 1 latched");
        decision = controller.observe(LlamaCppProcessMemoryAttribution.Snapshot.unknown("recovery"), recovering);
        require(decision.mode() != LlamaCppResidentPressureController.Mode.NORMAL, "recovery sample 2 latched");
        decision = controller.observe(LlamaCppProcessMemoryAttribution.Snapshot.unknown("recovery"), recovering);
        require(decision.mode() == LlamaCppResidentPressureController.Mode.NORMAL, "recovery sample 3 releases");

        System.out.println("llama.cpp resident pressure controller proof passed");
    }

    private static KoilMemoryPressureStabilizer.Result result(
            long availableMiB, long floorMiB, KoilMemoryPressureSnapshot.Pressure pressure) {
        KoilMemoryPressureSnapshot snapshot = new KoilMemoryPressureSnapshot(
                16L * 1024L * MIB, availableMiB * MIB, floorMiB * MIB,
                1776L * MIB, 0L, pressure, KoilMemoryPressureSnapshot.Phase.RUNTIME,
                (double) availableMiB / (16.0D * 1024.0D), java.time.Instant.now());
        return new KoilMemoryPressureStabilizer.Result(
                snapshot, snapshot, KoilMemoryPressureStabilizer.Trend.STABLE, 0);
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
