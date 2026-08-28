package com.spirit.koil.api.model.planning;

import com.spirit.koil.api.model.ModelToolDefinition;

import java.util.List;

/**
 * Compact first-round request shaping for small-model read-only lookups.
 * Final answering still uses the complete model contract after evidence is
 * returned, so this reduces schema/prefill cost without replacing reasoning.
 */
public final class InformationToolCallLatencyPolicy {
    public static final int MAXIMUM_OUTPUT_TOKENS = 128;

    private InformationToolCallLatencyPolicy() {
    }

    public static Decision evaluate(
        double modelParametersBillions,
        ConversationalReasoningPolicy.Decision reasoning,
        List<ModelToolDefinition> tools,
        boolean firstProviderRound
    ) {
        if (!firstProviderRound) return Decision.full("continuation_round");
        if (modelParametersBillions > 3.5D) return Decision.full("larger_model");
        if (reasoning == null || reasoning.reviewContext()) return Decision.full("conversation_context_required");
        if (tools == null || tools.isEmpty()) return Decision.full("no_information_tool");
        if (tools.stream().anyMatch(tool -> tool.confirmationRequired() || !tool.sideEffects().isEmpty())) {
            return Decision.full("non_read_only_tool");
        }
        return new Decision(true, "small_model_read_only_lookup", MAXIMUM_OUTPUT_TOKENS, true);
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
