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
        return evaluate(entry, entry != null && entry.toolCalling());
    }

    /**
     * Evaluates the selected model using Koil's already-resolved tool gate.
     * Catalog capability is not re-checked here, preventing stale discovery
     * metadata from vetoing stronger artifact/runtime evidence.
     */
    public static Evaluation evaluate(LocalModelCatalogEntry entry, boolean toolsEnabled) {
        if (entry == null) {
            return new Evaluation(
                    false,
                    "",
                    "Selected model",
                    -1,
                    "No selected model is available for Automation Mode. /ask remains available."
            );
        }
        boolean quarantined = LocalModelReliabilityStore.quarantined(entry);
        boolean eligible = toolsEnabled && !quarantined;
        String detail;
        if (eligible) {
            detail = entry.displayName() + " is eligible for Automation Mode because the selected model/runtime tool gate is enabled.";
        } else if (quarantined) {
            LocalModelReliabilityStore.Snapshot reliability = LocalModelReliabilityStore.snapshot(entry.modelId());
            detail = entry.displayName() + " is temporarily blocked from Automation because Koil recorded a major runtime/tool-protocol failure"
                    + (reliability.lastCode().isBlank() ? "." : ": " + reliability.lastCode() + ".")
                    + " Chat remains available; inspect or reset this evidence with /model reliability.";
        } else {
            detail = entry.displayName() + " does not currently have enough positive model/runtime evidence to enable the tool gate. /ask remains available.";
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
