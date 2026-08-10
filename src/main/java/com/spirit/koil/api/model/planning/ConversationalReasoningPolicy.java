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
        boolean minecraftTopic = contains(normalized, "minecraft", "command", "registry", "entity", "mob", "item", "block",
                "recipe", "craft", "advancement", "nbt", "snbt", "biome", "dimension", "datapack", "modded");
        boolean evidenceQuestion = contains(normalized, "what", "which", "how", "where", "does", "is there", "syntax",
                "exact", "valid", "exists", "id", "identifier", "tag", "property", "ingredient");
        boolean grounded = minecraftTopic && (complex || evidenceQuestion || normalized.contains("?"));
        if (deepThought) {
            return new Decision(Depth.DEEP_THOUGHT, true, true, true, 32, 1536);
        }
        if (complex || contextReview) {
            int rounds = profile.stagedExecution() ? 4 : 3;
            return new Decision(Depth.EXTENDED, true, contextReview, grounded, rounds, profile.stagedExecution() ? 1024 : 1536);
        }
        if (normalized.length() < 100 && !normalized.contains("?") && !grounded) {
            return new Decision(Depth.DIRECT, false, false, false, 1, 192);
        }
        return new Decision(Depth.NORMAL, false, contextReview, grounded, grounded ? 4 : 2, 1024);
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
            boolean groundedMinecraft,
            int maximumProviderRounds,
            int maximumOutputTokens
    ) {
    }
}
