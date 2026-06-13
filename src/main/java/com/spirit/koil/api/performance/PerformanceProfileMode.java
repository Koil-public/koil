package com.spirit.koil.api.performance;

public enum PerformanceProfileMode {
    AUTO("Auto", 0xFF00A8D8),
    LOW_END("Low End", 0xFF2DA700),
    BALANCED("Balanced", 0xFF2F83CC),
    HIGH_FPS("High FPS", 0xFF53D83E),
    QUALITY("Quality", 0xFF7400A4),
    LAPTOP_BATTERY_SAVER("Laptop/Battery Saver", 0xFFE3B735),
    STREAMING_RECORDING("Streaming/Recording", 0xFFA7003A),
    SHADER_FRIENDLY("Shader Friendly", 0xFF8B39DD),
    SERVER_FRIENDLY("Server Friendly", 0xFF6D8495),
    CUSTOM("Custom", 0xFFE8E8E8);

    private final String label;
    private final int color;

    PerformanceProfileMode(String label, int color) {
        this.label = label;
        this.color = color;
    }

    public String label() {
        return label;
    }

    public int color() {
        return color;
    }

    public static PerformanceProfileMode fromCommand(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (PerformanceProfileMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return BALANCED;
    }
}
