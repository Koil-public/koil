package com.spirit.koil.api.design.particle;

/** Shared accessibility/performance settings that a Koil config screen can persist. */
public final class KoilUiParticleSettings {
    private static float density = 1.0F;
    private static float motion = 1.0F;
    private static float soundVolume = 1.0F;
    private static boolean sounds = true;
    private static boolean flashing = true;
    private static boolean debug;
    private static KoilUiParticleEngine.PerformanceMode performanceMode = KoilUiParticleEngine.PerformanceMode.AUTO;

    private KoilUiParticleSettings() { }

    public static float density() { return density; }
    public static void density(float value) { density = clamp(value, 0.0F, 2.0F); }
    public static float motion() { return motion; }
    public static void motion(float value) { motion = clamp(value, 0.0F, 2.0F); }
    public static float soundVolume() { return soundVolume; }
    public static void soundVolume(float value) { soundVolume = clamp(value, 0.0F, 1.5F); }
    public static boolean sounds() { return sounds; }
    public static void sounds(boolean value) { sounds = value; }
    public static boolean flashing() { return flashing; }
    public static void flashing(boolean value) { flashing = value; }
    public static boolean debug() { return debug; }
    public static void debug(boolean value) { debug = value; }
    public static KoilUiParticleEngine.PerformanceMode performanceMode() { return performanceMode; }
    public static void performanceMode(KoilUiParticleEngine.PerformanceMode value) { performanceMode = value == null ? KoilUiParticleEngine.PerformanceMode.AUTO : value; }

    public static void apply(KoilUiParticleEngine engine) {
        if (engine == null) return;
        engine.setDensityScale(density);
        engine.setMotionScale(motion);
        engine.setSoundEnabled(sounds);
        engine.setSoundVolume(soundVolume);
        engine.setFlashingEnabled(flashing);
        engine.setDebugEnabled(debug);
        engine.setPerformanceMode(performanceMode);
    }

    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
