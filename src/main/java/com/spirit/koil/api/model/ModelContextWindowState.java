package com.spirit.koil.api.model;

import java.util.Optional;

/**
 * Truthful context-window occupancy derived from provider-reported token usage.
 *
 * <p>Prompt tokens already include any reused prefix, so reused-prefix tokens
 * must not be added to the used-token total a second time.</p>
 */
public record ModelContextWindowState(
        int maximumTokens,
        int usedTokens,
        int remainingTokens,
        int remainingPercent
) {
    public ModelContextWindowState {
        maximumTokens = Math.max(1, maximumTokens);
        usedTokens = Math.max(0, Math.min(maximumTokens, usedTokens));
        remainingTokens = Math.max(0, Math.min(maximumTokens, remainingTokens));
        remainingPercent = Math.max(0, Math.min(100, remainingPercent));
    }

    public static Optional<ModelContextWindowState> from(ModelUsage usage, int maximumTokens) {
        if (usage == null || maximumTokens <= 0) {
            return Optional.empty();
        }
        long reportedUsed = (long) usage.promptTokens() + usage.completionTokens();
        if (reportedUsed <= 0L) {
            return Optional.empty();
        }
        int used = (int) Math.min(maximumTokens, reportedUsed);
        int remaining = maximumTokens - used;
        int percent = (int) Math.round((remaining * 100.0D) / maximumTokens);
        return Optional.of(new ModelContextWindowState(maximumTokens, used, remaining, percent));
    }
}
