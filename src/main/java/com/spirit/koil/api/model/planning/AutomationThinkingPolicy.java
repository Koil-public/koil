package com.spirit.koil.api.model.planning;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Selects a bounded reasoning budget from the objective itself. Enabling Deep
 * Thinking permits a larger inspect/plan/act/verify budget for genuinely
 * complex objectives; it never forces greetings or single direct actions
 * through an expensive planning loop.
 */
public final class AutomationThinkingPolicy {
    private static final Pattern ACTION_WORD = Pattern.compile(
            "\\b(?:walk|move|jump|interact|open|take|store|use|eat|attack|kill|mine|farm|craft|give|grant|run|read|search|create|edit|write|replace|delete|build|plan|verify)\\b"
    );

    private AutomationThinkingPolicy() {
    }

    public static Decision evaluate(String prompt, boolean deepThinkingEnabled) {
        return evaluate(prompt, deepThinkingEnabled, false);
    }

    public static Decision evaluate(
            String prompt,
            boolean deepThinkingEnabled,
            boolean planningModeEnabled
    ) {
        String normalized = normalize(prompt);
        if (normalized.isBlank() || isConversation(normalized)) {
            return new Decision(Depth.CONVERSATIONAL, false, false, 0, 2, 0);
        }

        int actions = actionCount(normalized);
        boolean explicitSequence = normalized.contains(" then ")
                || normalized.contains(" after ")
                || normalized.contains(" before ")
                || normalized.contains(" and then ");
        boolean planningWork = containsAny(
                normalized,
                "plan", "strategy", "complex", "workflow", "task graph", "ktl",
                "file", "files", "code", "coding", "debug", "fix", "improve",
                "farm", "farming", "mine resources", "ender dragon", "starting from nothing"
        );
        boolean needsPlanTool = containsAny(
                normalized,
                "plan", "strategy", "workflow", "task graph",
                "file", "files", "code", "coding", "debug", "fix", "improve"
        );
        boolean complex = explicitSequence || planningWork || actions >= 3 || normalized.length() > 260;
        if (complex && deepThinkingEnabled) {
            return new Decision(Depth.DEEP, true, true, 16, 24, 4);
        }
        if (planningModeEnabled) {
            return new Decision(Depth.PLANNED, false, true, 12, 20, 3);
        }
        if (complex) {
            return new Decision(Depth.PLANNED, false, true, 10, 16, 3);
        }
        return new Decision(Depth.DIRECT, false, false, 8, 12, 2);
    }

    private static int actionCount(String normalized) {
        int count = 0;
        Matcher matcher = ACTION_WORD.matcher(normalized);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static boolean isConversation(String normalized) {
        return normalized.matches("(hi|hello|hey|thanks|thank you|how are you|good morning|good evening)[.!? ]*")
                || normalized.matches("(what|who|why|how)\\s+(is|are|does|do)\\b.*\\?");
    }

    private static boolean containsAny(String normalized, String... values) {
        for (String value : values) {
            if (normalized.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }

    public enum Depth {
        CONVERSATIONAL,
        DIRECT,
        PLANNED,
        DEEP
    }

    public record Decision(
            Depth depth,
            boolean deepActive,
            boolean includePlanTool,
            int maximumToolCalls,
            int maximumProviderRounds,
            int maximumContinuationCorrections
    ) {
    }
}
