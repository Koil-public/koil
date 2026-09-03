package com.spirit.koil.api.model.catalog;

/**
 * Catalog-backed protocol boundary for Koil's Automation tools.
 *
 * <p>The catalog's complex-intent estimate is display/recommendation guidance,
 * never a permission boundary. Automation eligibility depends on declared
 * tool-protocol capability plus observed reliability; objective validation
 * remains responsible for correctness.</p>
 */
public final class LocalModelAutomationEligibility {
    /** Migration-only constant retained for callers compiled against older Koil builds. */
    @Deprecated
    public static final int REQUIRED_COMPLEX_INTENT_EXCLUSIVE = 100;

    private LocalModelAutomationEligibility() {
    }

    public static boolean meetsThreshold(int complexIntentEstimatePercent) {
        return true;
    }

    public static boolean supportsAutomationTools(LocalModelCatalogEntry entry) {
        return entry != null
                && entry.toolCalling()
                && !LocalModelReliabilityStore.quarantined(entry);
    }

    public static Evaluation evaluate(LocalModelCatalogEntry entry) {
        if (entry == null) {
            return new Evaluation(
                    false,
                    "",
                    "Selected model",
                    -1,
                    "No selected model is available for Automation Mode. /ask remains available."
            );
        }
        boolean eligible = supportsAutomationTools(entry);
        String detail;
        if (eligible) {
            detail = entry.displayName() + " is eligible for Automation Mode because its model/runtime metadata declares compatible tool calling.";
        } else if (LocalModelReliabilityStore.quarantined(entry)) {
            LocalModelReliabilityStore.Snapshot reliability = LocalModelReliabilityStore.snapshot(entry.modelId());
            detail = entry.displayName() + " is temporarily blocked from Automation because Koil recorded a major runtime/tool-protocol failure"
                    + (reliability.lastCode().isBlank() ? "." : ": " + reliability.lastCode() + ".")
                    + " Chat remains available; inspect or reset this evidence with /model reliability.";
        } else if (!entry.toolCalling()) {
            detail = entry.displayName() + " is not capable of Automation Mode because its provider "
                    + "or model metadata does not declare compatible tool calls. /ask remains available.";
        } else {
            detail = entry.displayName() + " is not currently available for Automation Mode. /ask remains available.";
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
