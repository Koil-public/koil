package com.spirit.koil.api.model;

/** Native provider timing sample. Values are authoritative when {@link #valid()} is true. */
public record ModelPerformanceBenchmarkResult(
        int promptTokens,
        int generatedTokens,
        double promptTokensPerSecond,
        double generationTokensPerSecond,
        double promptMillis,
        double generationMillis,
        double timeToFirstTokenMillis,
        long wallMillis,
        String detail
) {
    public ModelPerformanceBenchmarkResult {
        promptTokens = Math.max(0, promptTokens);
        generatedTokens = Math.max(0, generatedTokens);
        promptTokensPerSecond = finitePositive(promptTokensPerSecond);
        generationTokensPerSecond = finitePositive(generationTokensPerSecond);
        promptMillis = finitePositive(promptMillis);
        generationMillis = finitePositive(generationMillis);
        timeToFirstTokenMillis = finitePositive(timeToFirstTokenMillis);
        wallMillis = Math.max(0L, wallMillis);
        detail = detail == null ? "" : detail.strip();
    }

    public boolean valid() {
        return promptTokens > 0
                && generatedTokens > 0
                && promptTokensPerSecond > 0.0D
                && generationTokensPerSecond > 0.0D;
    }

    private static double finitePositive(double value) {
        return Double.isFinite(value) && value > 0.0D ? value : 0.0D;
    }
}
