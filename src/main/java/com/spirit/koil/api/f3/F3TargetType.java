package com.spirit.koil.api.f3;

public enum F3TargetType {
    NONE("None", 0xFF8D8D8D),
    BLOCK("Block", 0xFFE3B735),
    FLUID("Fluid", 0xFF0085A4),
    ENTITY("Entity", 0xFFE06A21),
    PLAYER("Player", 0xFF76E6A0),
    ITEM("Item", 0xFF8FC5FF),
    CONTAINER("Container", 0xFFC8A24A),
    UNKNOWN("Unknown", 0xFF8D8D8D);

    private final String label;
    private final int color;

    F3TargetType(String label, int color) {
        this.label = label;
        this.color = color;
    }

    public String label() {
        return label;
    }

    public int color() {
        return color;
    }
}
