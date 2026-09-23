package com.spirit.koil.api.automation.goal;

/** Read-only result of compiling a proposed high-level goal. */
public record AutomationGoalSimulation(
        AutomationGoal goal,
        AutomationGoalGraph graph,
        AutomationGoalFailureCode blockedCode,
        String detail
) {
    public AutomationGoalSimulation {
        detail = detail == null ? "" : detail;
    }

    public boolean blocked() {
        return blockedCode != null;
    }
}
