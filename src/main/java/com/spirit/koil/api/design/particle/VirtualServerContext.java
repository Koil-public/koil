package com.spirit.koil.api.design.particle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic server-shaped context for the sprite-side Minecraft simulation.
 *
 * <p>This is intentionally not a fake ServerWorld. It exposes the small set of
 * world/server facts that virtual block/entity services are allowed to depend on,
 * so those services do not read ad-hoc particle data or invent separate clocks.
 * It never owns, opens, joins or stores a Minecraft World; the sprite engine must
 * remain usable from title/menu screens.</p>
 */
public final class VirtualServerContext {
    public record SpawnPoint(int x, int y, int z) { }

    private long worldTick;
    private int skyLight = 15;
    private int blockLight;
    private long timeOfDay = 6000L;
    private boolean raining;
    private boolean thundering;
    private String dimensionId = "minecraft:overworld";
    private float temperature = 0.8F;
    private boolean ultraWarm;
    private String difficulty = "normal";
    private SpawnPoint spawnPoint = new SpawnPoint(0, 0, 0);
    private final Map<String, String> gameRules = new LinkedHashMap<>();

    public VirtualServerContext() {
        gameRules.put("randomTickSpeed", "3");
        gameRules.put("doMobSpawning", "true");
        gameRules.put("mobGriefing", "true");
        gameRules.put("doFireTick", "true");
        gameRules.put("doDaylightCycle", "true");
        gameRules.put("doWeatherCycle", "true");
    }

    public String runtimeMode() { return "detached"; }
    public boolean requiresGameplayWorld() { return false; }

    public long worldTick() { return worldTick; }
    public void worldTick(long worldTick) { this.worldTick = Math.max(0L, worldTick); }

    public int skyLight() { return skyLight; }
    public int blockLight() { return blockLight; }
    public long timeOfDay() { return timeOfDay; }
    public boolean raining() { return raining; }
    public boolean thundering() { return thundering; }
    public String dimensionId() { return dimensionId; }
    public float temperature() { return temperature; }
    public boolean ultraWarm() { return ultraWarm; }
    public String difficulty() { return difficulty; }
    public SpawnPoint spawnPoint() { return spawnPoint; }

    public void environment(int skyLight, int blockLight, long timeOfDay, boolean raining, boolean thundering,
                            String dimensionId, float temperature, boolean ultraWarm) {
        this.skyLight = clamp(skyLight, 0, 15);
        this.blockLight = clamp(blockLight, 0, 15);
        this.timeOfDay = Math.floorMod(timeOfDay, 24000L);
        this.raining = raining;
        this.thundering = thundering;
        this.dimensionId = dimensionId == null || dimensionId.isBlank() ? "minecraft:overworld" : dimensionId;
        this.temperature = Math.max(-2.0F, Math.min(2.0F, temperature));
        this.ultraWarm = ultraWarm;
    }

    public void difficulty(String difficulty) {
        String normalized = difficulty == null ? "normal" : difficulty.trim().toLowerCase(Locale.ROOT);
        this.difficulty = switch (normalized) {
            case "peaceful", "easy", "hard" -> normalized;
            default -> "normal";
        };
    }

    public void spawnPoint(int x, int y, int z) {
        this.spawnPoint = new SpawnPoint(x, y, z);
    }

    public void gameRule(String key, Object value) {
        if (key == null || key.isBlank() || value == null) return;
        gameRules.put(key.trim(), String.valueOf(value));
    }

    public String gameRule(String key, String fallback) {
        if (key == null) return fallback;
        return gameRules.getOrDefault(key, fallback);
    }

    public boolean gameRuleBoolean(String key, boolean fallback) {
        String value = gameRule(key, String.valueOf(fallback));
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    public int gameRuleInt(String key, int fallback) {
        try { return Integer.parseInt(gameRule(key, String.valueOf(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    public Map<String, String> gameRules() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(gameRules));
    }

    public boolean isOverworld() { return "minecraft:overworld".equals(dimensionId); }
    public boolean isNether() { return "minecraft:the_nether".equals(dimensionId); }
    public boolean isEnd() { return "minecraft:the_end".equals(dimensionId); }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
