package com.spirit.koil.api.model.runtime.universal;

import java.time.Duration;

/** Controlled workload shared across competing benchmark candidates. */
public record KoilBenchmarkWorkload(
        String prompt,
        int outputTokens,
        Duration timeout,
        String name,
        Kind kind
) {
    public KoilBenchmarkWorkload(String prompt, int outputTokens, Duration timeout, String name) {
        this(prompt, outputTokens, timeout, name, inferKind(name, outputTokens));
    }

    public KoilBenchmarkWorkload {
        prompt = prompt == null ? "" : prompt;
        outputTokens = Math.max(8, Math.min(512, outputTokens));
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofMinutes(2) : timeout;
        name = name == null || name.isBlank() ? "benchmark" : name.strip();
        kind = kind == null ? inferKind(name, outputTokens) : kind;
    }

    public ScoreWeights scoreWeights() {
        return kind.scoreWeights();
    }

    private static Kind inferKind(String name, int outputTokens) {
        String normalized = name == null ? "" : name.strip().toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("tool")) return Kind.TOOL_CALL;
        if (normalized.contains("structured") || normalized.contains("json")) return Kind.STRUCTURED;
        if (normalized.contains("reason")) return Kind.REASONING;
        if (normalized.contains("prefill")) return Kind.PREFILL_HEAVY;
        if (normalized.contains("long-context") || normalized.contains("long_context")) return Kind.LONG_CONTEXT;
        if (normalized.contains("speculative") || normalized.contains("verify")) return Kind.SPECULATIVE_VERIFICATION;
        if (normalized.contains("decode") || outputTokens >= 256) return Kind.DECODE_HEAVY;
        if (normalized.contains("interactive") || normalized.contains("max-autotune") || outputTokens <= 128) return Kind.INTERACTIVE;
        return Kind.BALANCED;
    }

    public enum Kind {
        BALANCED(new ScoreWeights(0.40D, 0.45D, 0.15D)),
        INTERACTIVE(new ScoreWeights(0.30D, 0.45D, 0.25D)),
        TOOL_CALL(new ScoreWeights(0.30D, 0.35D, 0.35D)),
        STRUCTURED(new ScoreWeights(0.25D, 0.55D, 0.20D)),
        PREFILL_HEAVY(new ScoreWeights(0.60D, 0.25D, 0.15D)),
        DECODE_HEAVY(new ScoreWeights(0.20D, 0.70D, 0.10D)),
        LONG_CONTEXT(new ScoreWeights(0.55D, 0.35D, 0.10D)),
        REASONING(new ScoreWeights(0.15D, 0.75D, 0.10D)),
        SPECULATIVE_VERIFICATION(new ScoreWeights(0.20D, 0.70D, 0.10D));

        private final ScoreWeights scoreWeights;

        Kind(ScoreWeights scoreWeights) {
            this.scoreWeights = scoreWeights;
        }

        public ScoreWeights scoreWeights() {
            return scoreWeights;
        }
    }

    public record ScoreWeights(double prompt, double decode, double latency) {
        public ScoreWeights {
            prompt = sanitize(prompt);
            decode = sanitize(decode);
            latency = sanitize(latency);
            double total = prompt + decode + latency;
            if (total <= 0.0D) {
                prompt = 0.40D;
                decode = 0.45D;
                latency = 0.15D;
            } else {
                prompt /= total;
                decode /= total;
                latency /= total;
            }
        }

        private static double sanitize(double value) {
            return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
        }
    }
}
