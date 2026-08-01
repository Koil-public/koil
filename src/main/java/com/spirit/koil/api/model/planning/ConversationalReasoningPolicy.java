package com.spirit.koil.api.model.planning;

import com.spirit.koil.api.model.ModelAgentCapabilityProfile;

import java.util.Locale;

/** Bounded /ask reasoning selection; never exposes private reasoning text. */
public final class ConversationalReasoningPolicy {
    private ConversationalReasoningPolicy() {
    }

    public static Decision evaluate(
            String prompt,
            int conversationCharacters,
            ModelAgentCapabilityProfile profile,
            boolean deepThought
    ) {
        String normalized = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        boolean complex = normalized.length() > 260
                || contains(normalized, "compare", "analyze", "prove", "debug", "design", "architecture", "why", "tradeoff")
                || normalized.contains(" step by step")
                || normalized.contains("multiple approaches");
        boolean contextReview = conversationCharacters > Math.max(4_096, profile.contextWindowTokens() * 2);
        if (deepThought) {
            return new Decision(Depth.DEEP_THOUGHT, true, true, 32, 1536);
        }
        if (complex || contextReview) {
            int rounds = profile.stagedExecution() ? 4 : 3;
            return new Decision(Depth.EXTENDED, true, contextReview, rounds, profile.stagedExecution() ? 1024 : 1536);
        }
        if (normalized.length() < 100 && !normalized.contains("?")) {
            return new Decision(Depth.DIRECT, false, false, 1, 640);
        }
        return new Decision(Depth.NORMAL, false, contextReview, 2, 1024);
    }

    private static boolean contains(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public enum Depth { DIRECT, NORMAL, EXTENDED, DEEP_THOUGHT }

    public record Decision(
            Depth depth,
            boolean answerNowAvailable,
            boolean reviewContext,
            int maximumProviderRounds,
            int maximumOutputTokens
    ) {
    }
}
