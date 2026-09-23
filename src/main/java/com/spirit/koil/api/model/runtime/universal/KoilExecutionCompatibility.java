package com.spirit.koil.api.model.runtime.universal;

import java.util.List;

/** End-to-end graph executability analysis for one adapter/backend pair. */
public record KoilExecutionCompatibility(
        String adapterId,
        KoilRuntimeBackend backend,
        boolean graphValid,
        boolean tensorBindingsValid,
        List<KoilOperatorResolution> operators,
        List<KoilStateResolution> states,
        List<String> blockers,
        boolean executable,
        String evidence
) {
    public KoilExecutionCompatibility {
        adapterId = adapterId == null ? "" : adapterId.strip();
        backend = backend == null ? KoilRuntimeBackend.UNKNOWN : backend;
        operators = operators == null ? List.of() : List.copyOf(operators);
        states = states == null ? List.of() : List.copyOf(states);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        evidence = evidence == null ? "" : evidence.strip();
    }
}
