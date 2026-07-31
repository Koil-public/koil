package com.spirit.koil.api.automation.runtime;

public record AutomationPositionSnapshot(
        double x,
        double y,
        double z,
        String dimension
) {
    public AutomationPositionSnapshot {
        dimension = dimension == null ? "" : dimension;
    }
}
