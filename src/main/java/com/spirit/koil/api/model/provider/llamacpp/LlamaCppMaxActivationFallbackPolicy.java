package com.spirit.koil.api.model.provider.llamacpp;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Chooses a previously measured safe fallback when the selected MAX winner cannot be
 * reactivated because transient host/UMA pressure makes accelerator startup unsafe.
 *
 * <p>This policy never invents an unmeasured configuration. It may only fall back to a
 * successful CPU candidate already present in the completed sweep.</p>
 */
public final class LlamaCppMaxActivationFallbackPolicy {
    private LlamaCppMaxActivationFallbackPolicy() {}

    public static boolean isMemoryPressureFailure(Throwable failure) {
        return anyFailure(failure, normalized ->
                normalized.contains("protect system/uma memory")
                        || normalized.contains("startup memory pressure abort")
                        || normalized.contains("hard_floor_mib")
                        || normalized.contains("critical_low_memory"));
    }

    /**
     * True when an accelerator candidate reached runtime startup but degraded all the way to CPU.
     * This is distinct from an integrity mismatch such as the wrong non-zero hybrid split.
     */
    public static boolean isAcceleratorDegradedToCpuFailure(Throwable failure) {
        return anyFailure(failure, normalized ->
                (normalized.contains("max performance candidate expected")
                        && normalized.contains("runtime placement=cpu")
                        && normalized.contains("gpu layers=0"))
                        || normalized.contains("did not report any gpu-offloaded model layers"));
    }

    /**
     * A degraded accelerator activation gets one bounded retry only while the machine still has
     * enough headroom to attempt another accelerator process safely. This uses the same live
     * pressure policy that guards llama.cpp startup, so orchestration cannot race the provider's
     * UMA protection with a stale pre-tune memory observation.
     */
    public static boolean shouldRetryAccelerator(Throwable failure) {
        if (!isAcceleratorDegradedToCpuFailure(failure)) return false;
        return !LlamaCppComputeSafetyPolicy.runtimePressure().critical();
    }

    /**
     * Final activation may safely fall back only for pressure or complete accelerator loss.
     * Exact non-zero placement/integrity mismatches remain hard failures.
     */
    public static boolean allowsMeasuredCpuFallback(Throwable failure) {
        return isMemoryPressureFailure(failure) || isAcceleratorDegradedToCpuFailure(failure);
    }

    private static boolean anyFailure(Throwable failure, java.util.function.Predicate<String> predicate) {
        if (failure == null || predicate == null) return false;
        java.util.ArrayDeque<Throwable> pending = new java.util.ArrayDeque<>();
        java.util.IdentityHashMap<Throwable, Boolean> seen = new java.util.IdentityHashMap<>();
        pending.add(failure);
        int visited = 0;
        while (!pending.isEmpty() && visited++ < 64) {
            Throwable current = pending.removeFirst();
            if (current == null || seen.put(current, Boolean.TRUE) != null) continue;
            String message = current.getMessage();
            if (message != null && predicate.test(message.toLowerCase(Locale.ROOT))) return true;
            Throwable cause = current.getCause();
            if (cause != null) pending.addLast(cause);
            for (Throwable suppressed : current.getSuppressed()) {
                if (suppressed != null) pending.addLast(suppressed);
            }
        }
        return false;
    }

    public static Optional<LlamaCppMaxTuningResult.CandidateResult> bestMeasuredCpu(
            List<LlamaCppMaxTuningResult.CandidateResult> candidates
    ) {
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        return candidates.stream()
                .filter(candidate -> candidate != null && candidate.profile() != null && candidate.benchmark() != null)
                .filter(candidate -> candidate.profile().placement() == LlamaCppMaxRuntimeProfile.Placement.CPU)
                .filter(candidate -> candidate.benchmark().valid())
                .max(Comparator.comparingDouble(LlamaCppMaxTuningResult.CandidateResult::score));
    }
}
