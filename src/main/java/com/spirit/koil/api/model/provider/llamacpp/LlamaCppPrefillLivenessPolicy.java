package com.spirit.koil.api.model.provider.llamacpp;

/**
 * Bounds llama.cpp prompt-prefill liveness checks without treating a slow local
 * CPU as a dead request. Prompt ingestion can legitimately spend tens of
 * seconds between progress events on memory-constrained hardware, especially
 * when tool schemas are part of the chat template.
 */
final class LlamaCppPrefillLivenessPolicy {
    private static final long INITIAL_MIN_SECONDS = 90L;
    private static final long INITIAL_MAX_SECONDS = 240L;
    private static final long PROGRESS_MIN_SECONDS = 60L;
    private static final long PROGRESS_MAX_SECONDS = 120L;

    private LlamaCppPrefillLivenessPolicy() {}

    static Budget forPayloadCharacters(int payloadCharacters) {
        int chars = Math.max(0, payloadCharacters);
        // JSON/chat-template bytes are not tokens, but chars/4 is a deliberately
        // cheap conservative proxy and is sufficient for a watchdog budget.
        int estimatedTokens = Math.max(1, (chars + 3) / 4);
        long initial = clamp(INITIAL_MIN_SECONDS + estimatedTokens / 50L,
                INITIAL_MIN_SECONDS, INITIAL_MAX_SECONDS);
        long betweenProgress = clamp(PROGRESS_MIN_SECONDS + estimatedTokens / 200L,
                PROGRESS_MIN_SECONDS, PROGRESS_MAX_SECONDS);
        return new Budget(estimatedTokens, initial, betweenProgress);
    }

    static boolean stalled(long silentNanos, boolean progressSeen, Budget budget) {
        if (budget == null) return false;
        long allowedSeconds = progressSeen ? budget.progressStallSeconds() : budget.initialGraceSeconds();
        return silentNanos >= java.util.concurrent.TimeUnit.SECONDS.toNanos(allowedSeconds);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    record Budget(int estimatedPromptTokens, long initialGraceSeconds, long progressStallSeconds) {}
}
