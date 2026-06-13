package com.spirit.koil.api.console;

public enum ConsoleChannel {
    KOIL("koil"),
    PACKAGE("package"),
    MINECRAFT("minecraft"),
    CLI("cli");

    private final String id;

    ConsoleChannel(String id) {
        this.id = id;
    }

    public String id() {
        return this.id;
    }

    public static ConsoleChannel fromId(String id) {
        if (id == null) {
            return KOIL;
        }
        for (ConsoleChannel channel : values()) {
            if (channel.id.equalsIgnoreCase(id)) {
                return channel;
            }
        }
        return KOIL;
    }
}
