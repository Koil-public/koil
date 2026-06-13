package com.spirit.koil.api.util.application;

import com.spirit.koil.api.console.ConsoleRequestBridge;

public final class DesktopUiSoundHelper {
    private static long lastClickAt;

    private DesktopUiSoundHelper() {
    }

    public static void playClick() {
        long now = System.currentTimeMillis();
        if (now - lastClickAt < 40L) {
            return;
        }
        lastClickAt = now;
        ConsoleRequestBridge.submitUiSound("click");
    }

    public static void playFocus() {
        ConsoleRequestBridge.submitUiSound("focus");
    }
}
