package com.spirit.koil.api.performance;

public enum PerformanceBottleneck {
    HEALTHY("Stable", 0xFF2DA700, "No active bottleneck"),
    CPU("CPU", 0xFFE06A21, "Tick, entity, logic, or simulation pressure"),
    GPU("GPU", 0xFF8B39DD, "Rendering, shader, visual effect, or resolution pressure"),
    MEMORY("Memory", 0xFFA7003A, "RAM pressure or garbage collection stutter"),
    VRAM("VRAM", 0xFFB0199E, "Texture, shader, or high-resolution asset pressure"),
    CHUNK_STORAGE("Chunk/Storage", 0xFFE3B735, "Chunk loading or disk streaming pressure"),
    SHADER_RENDER("Shader/Render", 0xFF5D54D8, "Shader or render pipeline pressure"),
    ENTITY_TICK("Entity/Tick", 0xFFE6862C, "Entity count or world tick pressure"),
    MOD_OVERLOAD("Mod Overload", 0xFFC32222, "Mod conflict, startup cost, or overload risk"),
    UNKNOWN("Unknown", 0xFF8D8D8D, "Not enough data yet");

    private final String label;
    private final int color;
    private final String description;

    PerformanceBottleneck(String label, int color, String description) {
        this.label = label;
        this.color = color;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public int color() {
        return color;
    }

    public String description() {
        return description;
    }
}
