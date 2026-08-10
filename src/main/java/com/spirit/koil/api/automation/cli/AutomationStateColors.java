package com.spirit.koil.api.automation.cli;

import com.spirit.koil.api.model.ModelActivityState;
import com.spirit.koil.api.model.ModelSemanticPalette;

public final class AutomationStateColors {
    private AutomationStateColors() {
    }

    public static int color(String state) {
        return ModelSemanticPalette.color(ModelActivityState.fromLegacy(state));
    }

    public static String section(String state) {
        return ModelSemanticPalette.section(ModelActivityState.fromLegacy(state));
    }

    public static String normalizeState(String state) {
        return ModelActivityState.fromLegacy(state).id();
    }
}
