package com.spirit.koil.api.model;

/** Effective agent limits derived from the selected model and provider. */
public record ModelAgentCapabilityProfile(
        String modelId,
        String providerId,
        ToolReliability toolReliability,
        boolean jsonSchemaReliable,
        boolean multipleToolCallsReliable,
        int maximumRecommendedToolsPerRound,
        PlanningReliability planningReliability,
        int contextWindowTokens,
        boolean longPromptReliable,
        boolean malformedCallRepairSupported,
        boolean parallelChoicesReliable,
        int recommendedReasoningDepth,
        String providerToolFormat,
        boolean stagedExecution
) {
    public ModelAgentCapabilityProfile {
        modelId = clean(modelId);
        providerId = clean(providerId);
        toolReliability = toolReliability == null ? ToolReliability.NONE : toolReliability;
        maximumRecommendedToolsPerRound = Math.max(0, maximumRecommendedToolsPerRound);
        planningReliability = planningReliability == null ? PlanningReliability.WEAK : planningReliability;
        contextWindowTokens = Math.max(0, contextWindowTokens);
        recommendedReasoningDepth = Math.max(1, recommendedReasoningDepth);
        providerToolFormat = clean(providerToolFormat);
    }

    public boolean canAutomate() {
        return this.toolReliability != ToolReliability.NONE;
    }

    public enum ToolReliability { NONE, WEAK, RELIABLE }

    public enum PlanningReliability { WEAK, NORMAL, RELIABLE }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
