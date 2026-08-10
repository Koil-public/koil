package com.spirit.koil.api.model.planning;

import com.spirit.koil.api.model.ModelToolDefinition;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Selects a compact first provider round for an unambiguous, self-contained
 * Automation action. This changes request shape only: the selected model,
 * registered tool schema, approval, executor, KTL, result validation, and
 * continuation loop remain authoritative.
 */
public final class AutomationToolCallLatencyPolicy {
    public static final int DIRECT_TOOL_OUTPUT_TOKENS = 96;

    private static final Set<String> NON_ACTION_COMPANIONS = Set.of(
            "automation.cancel",
            "input.release",
            "input.release_all"
    );

    private AutomationToolCallLatencyPolicy() {
    }

    public static Decision evaluate(
            String prompt,
            AutomationThinkingPolicy.Decision thinking,
            Set<String> requiredToolIds,
            List<ModelToolDefinition> roundTools,
            boolean planningModeEnabled,
            boolean firstProviderRound
    ) {
        if (!firstProviderRound) return Decision.full("continuation_round");
        if (planningModeEnabled) return Decision.full("planning_mode");
        if (thinking == null || thinking.depth() != AutomationThinkingPolicy.Depth.DIRECT
                || thinking.includePlanTool() || thinking.deepActive()) {
            return Decision.full("non_direct_reasoning");
        }
        if (requiredToolIds == null || requiredToolIds.size() != 1) {
            return Decision.full("not_one_required_action");
        }
        if (roundTools == null || roundTools.isEmpty()) return Decision.full("missing_tool");

        Set<String> suppliedIds = roundTools.stream()
                .map(ModelToolDefinition::id)
                .collect(Collectors.toUnmodifiableSet());
        String requiredId = requiredToolIds.iterator().next();
        if (!suppliedIds.contains(requiredId)) return Decision.full("required_tool_not_supplied");
        if (suppliedIds.stream().anyMatch(id -> !id.equals(requiredId) && !NON_ACTION_COMPANIONS.contains(id))) {
            return Decision.full("requires_companion_evidence_or_choice");
        }

        String normalized = normalize(prompt);
        if (normalized.isBlank() || normalized.length() > 160 || normalized.indexOf('?') >= 0) {
            return Decision.full("not_a_short_directive");
        }
        String padded = " " + normalized + " ";
        if (containsAny(padded,
                " and ", " then ", " after ", " before ", " while ", " until ",
                " if ", " unless ", " when ", " because ", " so that ",
                " all ", " every ", " each ", " multiple ", " repeatedly ",
                " twice ", " thrice ", " several ", " times ",
                " plan ", " verify ", " check ", " inspect ", " search ", " find ",
                " choose ", " best ", " safest ", " nearest ", " explain ", " why ")) {
            return Decision.full("conditional_or_compound");
        }
        if (containsAny(padded,
                " near ", " around ", " toward ", " towards ", " onto ", " over ",
                " under ", " beside ", " across ", " through ", " from ", " to ")) {
            return Decision.full("spatial_context");
        }
        if (containsReference(normalized)) return Decision.full("conversation_reference");

        return new Decision(true, "single_self_contained_action", DIRECT_TOOL_OUTPUT_TOKENS, true);
    }

    public static boolean useDirectVerifiedResultRound(
            boolean directToolDecisionSession,
            int toolResultsReceived,
            int successfulActionToolOutputs,
            boolean everyKnownObjectiveCompleted,
            boolean reviewedPlanPresent
    ) {
        return directToolDecisionSession
                && toolResultsReceived > 0
                && successfulActionToolOutputs > 0
                && everyKnownObjectiveCompleted
                && !reviewedPlanPresent;
    }

    private static boolean containsReference(String normalized) {
        String padded = " " + normalized + " ";
        return containsAny(padded,
                " again ", " continue ", " resume ", " same ", " previous ",
                " that ", " this ", " those ", " these ", " them ", " there ",
                " do it ", " use it ");
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    public record Decision(
            boolean directToolDecision,
            String reason,
            int maximumOutputTokens,
            boolean freshConversationWindow
    ) {
        private static Decision full(String reason) {
            return new Decision(false, reason, 0, false);
        }
    }
}
