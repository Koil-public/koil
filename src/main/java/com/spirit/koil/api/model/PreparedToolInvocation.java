package com.spirit.koil.api.model;

import java.util.List;

/**
 * Side-effect-free preparation artifact for one concrete tool invocation.
 *
 * <p>VALIDATE_ONLY preparation proves that the exact call shape passed generic
 * policy/schema/current-state checks against one observation epoch.
 * STAGE_PAYLOAD additionally carries a side-effect-free mutation preview,
 * resource fingerprints and predicted postconditions. Neither mode grants
 * approval or commits the operation.</p>
 */
public record PreparedToolInvocation(
        ModelToolCall call,
        ToolPreflight preflight,
        long observationEpoch,
        long preparedAtMillis,
        String fingerprint,
        StagedToolPayload stagedPayload,
        List<ToolPostcondition> postconditions
) {
    public PreparedToolInvocation {
        if (call == null) throw new IllegalArgumentException("prepared call is required");
        if (preflight == null) throw new IllegalArgumentException("prepared preflight is required");
        fingerprint = fingerprint == null ? "" : fingerprint;
        postconditions = postconditions == null ? List.of() : List.copyOf(postconditions);
    }

    /** Compatibility constructor for validate-only preparation. */
    public PreparedToolInvocation(
            ModelToolCall call,
            ToolPreflight preflight,
            long observationEpoch,
            long preparedAtMillis,
            String fingerprint
    ) {
        this(call, preflight, observationEpoch, preparedAtMillis, fingerprint, null, List.of());
    }

    public boolean matches(ModelToolCall other, long currentEpoch) {
        if (other == null || currentEpoch != observationEpoch) return false;
        return call.toolId().equals(other.toolId()) && call.arguments().equals(other.arguments());
    }

    public boolean staged() {
        return this.stagedPayload != null;
    }
}
