package com.spirit.koil.api.f3;

public record F3DataLine(
        String label,
        String value,
        String state,
        int color,
        String tooltip
) {
    public static F3DataLine of(String label, String value) {
        return new F3DataLine(label, value, "info", 0xFFE6EDF5, "");
    }

    public static F3DataLine state(String label, String value, String state, int color, String tooltip) {
        return new F3DataLine(label, value, state, color, tooltip == null ? "" : tooltip);
    }
}
