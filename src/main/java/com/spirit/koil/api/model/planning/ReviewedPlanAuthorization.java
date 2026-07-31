package com.spirit.koil.api.model.planning;

import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;

/**
 * Exact-step authorization created only after a validated plan is approved.
 */
public final class ReviewedPlanAuthorization {
    private final ValidatedAutomationPlan plan;
    private boolean approved;

    public ReviewedPlanAuthorization(ValidatedAutomationPlan plan) {
        this.plan = plan;
    }

    public void approve() {
        this.approved = true;
    }

    public void reject() {
        this.approved = false;
    }

    public boolean authorizesExactStep(int oneBasedIndex, ModelToolCall call) {
        if (!this.approved || call == null) {
            return false;
        }
        int position = oneBasedIndex - 1;
        if (position < 0 || position >= this.plan.steps().size()) {
            return false;
        }
        ValidatedAutomationPlan.Step step = this.plan.steps().get(position);
        return step.toolId().equals(call.toolId())
                && step.arguments().equals(call.arguments());
    }

    public static boolean allowsUnplannedDiagnostic(ModelToolCall call) {
        return call != null && !LocalModelToolCatalog.requiresFreshApproval(call.toolId());
    }
}
