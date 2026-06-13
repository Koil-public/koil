package com.spirit.koil.api.automation;

import com.google.gson.JsonElement;
import com.spirit.koil.api.util.file.json.JSONFileEditor;

public final class AutomationSettings {
    private static final String CONFIG_PATH = "./koil/sys/config.json";
    private static final String CHAT_USERNAME_TRIGGER = "allowAutomationChatUsernameTrigger";

    private AutomationSettings() {
    }

    public static boolean allowChatUsernameTrigger() {
        return readBoolean(CHAT_USERNAME_TRIGGER, false);
    }

    private static boolean readBoolean(String key, boolean fallback) {
        try {
            JsonElement value = JSONFileEditor.getValueFromJson(CONFIG_PATH, key);
            return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }
}
