package com.spirit.koil.api.console;

import com.spirit.client.gui.main.ConsoleToast;

public enum ConsoleLevel {
    PLAIN("[ ]", ConsoleToast.Type.CONSOLE),
    INFO("[-]", ConsoleToast.Type.CONSOLE_INFO),
    WARN("[=]", ConsoleToast.Type.CONSOLE_WARNING),
    ERROR("[*]", ConsoleToast.Type.CONSOLE_ERROR),
    FATAL("[~]", ConsoleToast.Type.CONSOLE_FATAL),
    DEBUG("[>]", ConsoleToast.Type.CONSOLE_DEBUG),
    UPDATE("[&]", ConsoleToast.Type.CONSOLE_UPDATE),
    OTHER("[?]", ConsoleToast.Type.CONSOLE_OTHER);

    private final String marker;
    private final ConsoleToast.Type toastType;

    ConsoleLevel(String marker, ConsoleToast.Type toastType) {
        this.marker = marker;
        this.toastType = toastType;
    }

    public String marker() {
        return this.marker;
    }

    public ConsoleToast.Type toastType() {
        return this.toastType;
    }
}
