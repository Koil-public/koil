package com.spirit.koil.api.automation;

import com.spirit.koil.api.automation.cli.AutomationChatHudState;
import com.spirit.koil.api.automation.cli.AutomationPresenceState;

public final class AutomationModeController {
    private static volatile boolean automationMode;

    private AutomationModeController() {
    }

    public static boolean isAutomationMode() {
        return automationMode;
    }

    public static void setAutomationMode(boolean enabled) {
        automationMode = enabled;
        AutomationPresenceState.updateLocalMode(enabled);
        if (!enabled) {
            AutomationChatHudState.hide();
        }
    }
}
