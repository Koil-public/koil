package com.spirit.koil.api.model.runtime.universal;

import java.util.Map;

/** Immutable runtime decision shared by all internal execution adapters. */
public record KoilExecutionPlan(
        KoilExecutionAdapterDescriptor adapter,
        KoilComputeMode computeMode,
        KoilRuntimeBackend primaryBackend,
        String hardwareFingerprint,
        String reason,
        KoilExecutionSettings settings,
        Map<String, String> decisions
) {
    public KoilExecutionPlan {
        if (adapter == null) throw new IllegalArgumentException("adapter is required");
        computeMode = computeMode == null ? KoilComputeMode.AUTOMATIC : computeMode;
        primaryBackend = primaryBackend == null ? KoilRuntimeBackend.UNKNOWN : primaryBackend;
        hardwareFingerprint = hardwareFingerprint == null ? "" : hardwareFingerprint.strip();
        reason = reason == null ? "" : reason.strip();
        settings = settings == null ? KoilExecutionSettings.automatic() : settings;
        decisions = decisions == null ? Map.of() : Map.copyOf(decisions);
    }

    /** Compatibility constructor retained for existing callers. */
    public KoilExecutionPlan(
            KoilExecutionAdapterDescriptor adapter,
            KoilComputeMode computeMode,
            KoilRuntimeBackend primaryBackend,
            String hardwareFingerprint,
            String reason,
            Map<String, String> decisions
    ) {
        this(adapter, computeMode, primaryBackend, hardwareFingerprint, reason,
                KoilExecutionSettings.automatic(), decisions);
    }

    /** Compatibility constructor retained for existing callers and proofs. */
    public KoilExecutionPlan(KoilExecutionAdapterDescriptor adapter, String reason, Map<String, String> decisions) {
        this(adapter, KoilComputeMode.AUTOMATIC, KoilRuntimeBackend.UNKNOWN, "", reason,
                KoilExecutionSettings.automatic(), decisions);
    }
}
