package com.spirit.koil.api.model.catalog;

/**
 * Catalog-backed performance boundary for Koil's in-depth Automation tools.
 *
 * <p>The complex-intent estimate is comparative catalog guidance, not a
 * benchmark score. Koil nevertheless needs one conservative, deterministic
 * floor before exposing actions that depend on reliable multi-step tool use.</p>
 */
public final class LocalModelAutomationEligibility {
    public static final int REQUIRED_COMPLEX_INTENT_EXCLUSIVE = 30;

    private LocalModelAutomationEligibility() {
    }

    public static boolean meetsThreshold(int complexIntentEstimatePercent) {
        return complexIntentEstimatePercent > REQUIRED_COMPLEX_INTENT_EXCLUSIVE;
    }

    public static boolean supportsAutomationTools(LocalModelCatalogEntry entry) {
        return entry != null
                && entry.toolCalling()
                && meetsThreshold(entry.complexReasoningEstimatePercent());
    }

    public static Evaluation evaluate(LocalModelCatalogEntry entry) {
        if (entry == null) {
            return new Evaluation(
                    false,
                    "",
                    "Selected model",
                    -1,
                    "The selected model is not capable of Automation Mode because it has no verified "
                            + "complex-intent estimate. Koil requires more than "
                            + REQUIRED_COMPLEX_INTENT_EXCLUSIVE + "%. /ask remains available."
            );
        }
        boolean eligible = supportsAutomationTools(entry);
        String detail;
        if (eligible) {
            detail = entry.displayName() + " is eligible for Automation Mode at "
                    + entry.complexReasoningEstimatePercent() + "% complex intent.";
        } else if (!entry.toolCalling()) {
            detail = entry.displayName() + " is not capable of Automation Mode because its provider "
                    + "does not support Koil tool calls. /ask remains available.";
        } else {
            detail = entry.displayName() + " is not capable of Automation Mode because its "
                    + entry.complexReasoningEstimatePercent() + "% complex-intent estimate does not exceed "
                    + "Koil's " + REQUIRED_COMPLEX_INTENT_EXCLUSIVE + "% requirement. /ask remains available.";
        }
        return new Evaluation(
                eligible,
                entry.id(),
                entry.displayName(),
                entry.complexReasoningEstimatePercent(),
                detail
        );
    }

    public record Evaluation(
            boolean eligible,
            String catalogId,
            String displayName,
            int complexIntentEstimatePercent,
            String detail
    ) {
    }
}
