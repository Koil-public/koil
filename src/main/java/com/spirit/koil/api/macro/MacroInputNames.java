package com.spirit.koil.api.macro;

import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;

public final class MacroInputNames {
    private MacroInputNames() {
    }

    public static String triggerName(MacroTriggerType type, int code) {
        if (type == null || type == MacroTriggerType.NONE || code < 0) {
            return "Unbound";
        }
        return keyName(type == MacroTriggerType.MOUSE, code);
    }

    public static String keyName(boolean mouse, int code) {
        if (code < 0) {
            return "Unbound";
        }
        try {
            InputUtil.Key key = (mouse ? InputUtil.Type.MOUSE : InputUtil.Type.KEYSYM).createFromCode(code);
            Text text = key.getLocalizedText();
            return text == null ? (mouse ? "Mouse " : "Key ") + code : text.getString();
        } catch (Exception ignored) {
            return (mouse ? "Mouse " : "Key ") + code;
        }
    }
}
