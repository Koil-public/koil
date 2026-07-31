package com.spirit.koil.api.model;

import java.time.Instant;
import java.util.Map;

public record ModelHealthSnapshot(
        ModelHealthState state,
        String detail,
        int queueDepth,
        Instant updatedAt,
        Map<String, String> diagnostics
) {
    public ModelHealthSnapshot {
        state = state == null ? ModelHealthState.STOPPED : state;
        detail = detail == null ? "" : detail;
        queueDepth = Math.max(0, queueDepth);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }

    public static ModelHealthSnapshot stopped() {
        return new ModelHealthSnapshot(ModelHealthState.STOPPED, "", 0, Instant.now(), Map.of());
    }
}
