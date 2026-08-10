package com.spirit.koil.api.console;

public enum ConsoleLevel {
    PLAIN("[ ]"),
    INFO("[-]"),
    WARN("[=]"),
    ERROR("[*]"),
    FATAL("[~]"),
    DEBUG("[>]"),
    UPDATE("[&]"),
    OTHER("[?]");

    private final String marker;

    ConsoleLevel(String marker) {
        this.marker = marker;
    }

    public String marker() {
        return this.marker;
    }
}
