package com.spirit.koil.api.model;

import java.util.List;

/** Reusable historical failure -> recovery evidence. Never grants execution authority. */
record ToolRecoveryTrajectory(
        String failureSignature,
        List<String> recoveryTools,
        int samples,
        double confidence,
        String sourceTrajectory
) {
    ToolRecoveryTrajectory {
        failureSignature = failureSignature == null ? "" : failureSignature.strip();
        recoveryTools = recoveryTools == null ? List.of() : recoveryTools.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::strip).toList();
        samples = Math.max(0, samples);
        confidence = Math.max(0.0D, Math.min(1.0D, confidence));
        sourceTrajectory = sourceTrajectory == null ? "" : sourceTrajectory.strip();
    }

    boolean available() { return !recoveryTools.isEmpty(); }
}
