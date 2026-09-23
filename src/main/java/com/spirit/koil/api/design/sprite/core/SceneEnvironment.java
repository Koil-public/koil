package com.spirit.koil.api.design.sprite.core;

import net.minecraft.util.Identifier;
import net.minecraft.world.biome.BiomeEffects;

/** Detached environment values used when no gameplay world exists. */
public final class SceneEnvironment {
    private Identifier biomeId = new Identifier("minecraft", "plains");
    private Identifier dimensionId = new Identifier("minecraft", "overworld");
    private int waterColor = 0x3F76E4;
    private int foliageColor = 0x59AE30;
    private int grassColor = 0x91BD59;
    private BiomeEffects.GrassColorModifier grassColorModifier = BiomeEffects.GrassColorModifier.NONE;
    private int skyLight = 15;
    private int blockLight;
    private long timeOfDay = 6000L;
    private float temperature = 0.8F;
    private float downfall = 0.4F;
    private boolean raining;
    private boolean thundering;

    public Identifier biomeId() { return biomeId; }
    public Identifier dimensionId() { return dimensionId; }
    public int waterColor() { return waterColor; }
    public int foliageColor() { return foliageColor; }
    public int grassColor() { return grassColor; }
    public BiomeEffects.GrassColorModifier grassColorModifier() { return grassColorModifier; }
    public int grassColorAt(double x, double z) {
        return grassColorModifier.getModifiedGrassColor(x, z, grassColor) & 0xFFFFFF;
    }
    public int skyLight() { return skyLight; }
    public int blockLight() { return blockLight; }
    public long timeOfDay() { return timeOfDay; }
    public float temperature() { return temperature; }
    public float downfall() { return downfall; }
    public boolean raining() { return raining; }
    public boolean thundering() { return thundering; }

    public void setBiomeId(Identifier biomeId) { if (biomeId != null) this.biomeId = biomeId; }
    public void setDimensionId(Identifier dimensionId) { if (dimensionId != null) this.dimensionId = dimensionId; }
    public void setWaterColor(int rgb) { waterColor = rgb & 0xFFFFFF; }
    public void setFoliageColor(int rgb) { foliageColor = rgb & 0xFFFFFF; }
    public void setGrassColor(int rgb) { grassColor = rgb & 0xFFFFFF; }
    public void setGrassColorModifier(BiomeEffects.GrassColorModifier modifier) {
        grassColorModifier = modifier == null ? BiomeEffects.GrassColorModifier.NONE : modifier;
    }
    public void setGrassColorModifier(String serializedName) {
        if (serializedName == null || serializedName.isBlank()) {
            grassColorModifier = BiomeEffects.GrassColorModifier.NONE;
            return;
        }
        String wanted = serializedName.trim();
        for (BiomeEffects.GrassColorModifier modifier : BiomeEffects.GrassColorModifier.values()) {
            if (modifier.asString().equalsIgnoreCase(wanted) || modifier.name().equalsIgnoreCase(wanted)) {
                grassColorModifier = modifier;
                return;
            }
        }
        grassColorModifier = BiomeEffects.GrassColorModifier.NONE;
    }
    public void setSkyLight(int light) { skyLight = clampLight(light); }
    public void setBlockLight(int light) { blockLight = clampLight(light); }
    public void setTimeOfDay(long timeOfDay) { this.timeOfDay = Math.floorMod(timeOfDay, 24000L); }
    public void setTemperature(float temperature) { this.temperature = temperature; }
    public void setDownfall(float downfall) { this.downfall = Math.max(0.0F, Math.min(1.0F, downfall)); }
    public void setRaining(boolean raining) { this.raining = raining; }
    public void setThundering(boolean thundering) { this.thundering = thundering; }

    private static int clampLight(int value) { return Math.max(0, Math.min(15, value)); }
}
