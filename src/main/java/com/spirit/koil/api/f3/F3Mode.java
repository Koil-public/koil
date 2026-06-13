package com.spirit.koil.api.f3;

import java.util.Locale;

public enum F3Mode {
    SIMPLE("Simple"),
    NORMAL("Normal"),
    ADVANCED("Advanced"),
    DEVELOPER("Developer"),
    PERFORMANCE("Performance"),
    GRAPHS("Graphs"),
    WORLD("World"),
    PLAYER("Player"),
    TARGET("Target"),
    CREATOR("Creator"),
    INSPECTOR("Inspector"),
    MODPACK("Modpack"),
    COMPACT("Compact"),
    FULL("Full");

    private final String label;

    F3Mode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean developerDataEnabled() {
        return this == ADVANCED || this == DEVELOPER || this == MODPACK || this == FULL || this == CREATOR;
    }

    public static F3Mode fromCommand(String value) {
        if (value == null || value.isBlank()) {
            return NORMAL;
        }
        String normalized = value.toUpperCase(Locale.ROOT).replace('-', '_');
        for (F3Mode mode : values()) {
            if (mode.name().equals(normalized) || mode.label.toUpperCase(Locale.ROOT).equals(normalized)) {
                return mode;
            }
        }
        return NORMAL;
    }
}
