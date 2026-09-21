package com.spirit.koil.api.model;

import java.time.Duration;

/** Controlled local-model benchmark request used by runtime autotuners. */
public record ModelPerformanceBenchmarkRequest(
        String prompt,
        int outputTokens,
        Duration timeout,
        String label
) {
    public ModelPerformanceBenchmarkRequest {
        prompt = prompt == null ? "" : prompt;
        outputTokens = Math.max(8, Math.min(512, outputTokens));
        timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofMinutes(2)
                : timeout;
        label = label == null ? "benchmark" : label.strip();
        if (label.isBlank()) label = "benchmark";
    }
}
