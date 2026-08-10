package com.spirit.koil.api.model.presence;

/**
 * World-only status-line color projection. The depth-tested pass uses local
 * world light, while the see-through pass is additionally dimmed so an
 * occluded name remains legible without looking emissive through terrain.
 */
public final class ModelPresenceWorldLineStyle {
    private static final double MINIMUM_LIGHT_FACTOR = 0.28D;
    private static final double OCCLUDED_COLOR_FACTOR = 0.34D;
    private static final double OCCLUDED_ALPHA_FACTOR = 0.62D;

    private ModelPresenceWorldLineStyle() {
    }

    /**
     * Converts Minecraft's separate sky/block samples into the light level
     * used by the world status line. Raw sky light describes sky access and
     * remains high at midnight, so it must be modulated by the solar cycle.
     * Block light remains authoritative and can brighten the line at night.
     */
    public static int effectiveLightLevel(int blockLight, int skyLight, long timeOfDay) {
        int block = clampLight(blockLight);
        int sky = clampLight(skyLight);
        long dayTick = Math.floorMod(timeOfDay, 24_000L);
        double solarAngle = (dayTick - 6_000L) / 24_000.0D * Math.PI * 2.0D;
        double daylight = (Math.cos(solarAngle) + 1.0D) * 0.5D;
        double skyFactor = 0.25D + daylight * 0.75D;
        int adjustedSky = clampLight((int) Math.round(sky * skyFactor));
        return Math.max(block, adjustedSky);
    }

    public static Colors colors(int semanticArgb, int localLightLevel) {
        int light = clampLight(localLightLevel);
        double worldFactor = MINIMUM_LIGHT_FACTOR
                + (1.0D - MINIMUM_LIGHT_FACTOR) * (light / 15.0D);
        int visible = multiply(semanticArgb, worldFactor, 1.0D);
        int occluded = multiply(semanticArgb,
                worldFactor * OCCLUDED_COLOR_FACTOR,
                OCCLUDED_ALPHA_FACTOR);
        return new Colors(visible, occluded);
    }

    private static int multiply(int argb, double colorFactor, double alphaFactor) {
        int alpha = argb >>> 24 & 255;
        if (alpha == 0) alpha = 255;
        int red = argb >>> 16 & 255;
        int green = argb >>> 8 & 255;
        int blue = argb & 255;
        return clamp(alpha * alphaFactor) << 24
                | clamp(red * colorFactor) << 16
                | clamp(green * colorFactor) << 8
                | clamp(blue * colorFactor);
    }

    private static int clamp(double value) {
        return Math.max(0, Math.min(255, (int) Math.round(value)));
    }

    private static int clampLight(int value) {
        return Math.max(0, Math.min(15, value));
    }

    public record Colors(int visibleArgb, int occludedArgb) {
    }
}
