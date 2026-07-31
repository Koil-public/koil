package com.spirit.koil.api.model;

public record ModelUsage(
        int promptTokens,
        int completionTokens,
        int reusedPrefixTokens,
        long queueMillis,
        long timeToFirstTokenMillis,
        double tokensPerSecond
) {
    public ModelUsage {
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
        reusedPrefixTokens = Math.max(0, reusedPrefixTokens);
        queueMillis = Math.max(0L, queueMillis);
        timeToFirstTokenMillis = Math.max(0L, timeToFirstTokenMillis);
        tokensPerSecond = Math.max(0.0D, tokensPerSecond);
    }

    public static ModelUsage empty() {
        return new ModelUsage(0, 0, 0, 0L, 0L, 0.0D);
    }
}
