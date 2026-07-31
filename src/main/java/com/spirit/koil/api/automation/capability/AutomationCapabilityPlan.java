package com.spirit.koil.api.automation.capability;

import com.spirit.koil.api.automation.AutomationRequest;

public record AutomationCapabilityPlan(
        String capabilityId,
        String objective,
        Action action,
        AutomationRequest request
) {
    public enum Action {
        EXECUTE_PLAN,
        SUBMIT_COMMAND,
        CANCEL_CURRENT
    }
}
