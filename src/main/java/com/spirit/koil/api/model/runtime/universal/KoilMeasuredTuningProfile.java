package com.spirit.koil.api.model.runtime.universal;

import java.time.Instant;
import java.util.Map;

/**
 * Runtime-neutral measured tuning result. Adapter-specific tuners translate their
 * winners into this record so the universal planner can consume measured evidence
 * without depending on adapter internals.
 */
public record KoilMeasuredTuningProfile(
        String identity,
        String source,
        Instant measuredAt,
        String adapterId,
        String modelId,
        String architectureId,
        String runtimeIdentity,
        String hardwareFingerprint,
        KoilComputeMode computeMode,
        KoilRuntimeBackend primaryBackend,
        double score,
        Map<String, Double> metrics,
        Map<String, String> decisions
) {
    public KoilMeasuredTuningProfile {
        identity = safe(identity);
        source = safe(source);
        measuredAt = measuredAt == null ? Instant.EPOCH : measuredAt;
        adapterId = safe(adapterId);
        modelId = safe(modelId);
        architectureId = safe(architectureId);
        runtimeIdentity = safe(runtimeIdentity);
        hardwareFingerprint = safe(hardwareFingerprint);
        computeMode = computeMode == null ? KoilComputeMode.AUTOMATIC : computeMode;
        primaryBackend = primaryBackend == null ? KoilRuntimeBackend.UNKNOWN : primaryBackend;
        score = Double.isFinite(score) ? Math.max(0.0D, score) : 0.0D;
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        decisions = decisions == null ? Map.of() : Map.copyOf(decisions);
    }

    public boolean matchesAdapter(KoilExecutionAdapterDescriptor adapter) {
        return adapter != null && !adapterId.isBlank() && adapterId.equals(adapter.id());
    }

    /**
     * Empty architecture/fingerprint fields are treated as legacy evidence and do
     * not disqualify the profile. Non-empty fields must match exactly.
     */
    public boolean compatibleWith(
            KoilExecutionAdapterDescriptor adapter,
            KoilModelProfile model,
            KoilHardwareProfile hardware
    ) {
        if (!matchesAdapter(adapter)) return false;
        if (!architectureId.isBlank() && model != null
                && !architectureId.equalsIgnoreCase(model.architectureId())) return false;
        return hardwareFingerprint.isBlank() || hardware == null
                || hardwareFingerprint.equals(hardware.fingerprint());
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
