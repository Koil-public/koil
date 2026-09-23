package com.spirit.koil.api.design.particle;

/** Shared accessibility/performance settings that a Koil config screen can persist. */
public final class UiParticleSettings {
    private static float density = 1.0F;
    private static float motion = 1.0F;
    private static float soundVolume = 1.0F;
    private static boolean sounds = true;
    private static boolean flashing = true;
    private static boolean debug;
    private static boolean particleInteractions = true;
    private static boolean hoverInteractions = true;
    private static boolean clickInteractions = true;
    private static boolean dragInteractions = true;
    private static boolean swipeInteractions = true;
    private static boolean scrollInteractions = true;
    private static boolean keyboardInteractions = true;
    private static boolean relationInteractions = true;
    private static boolean constraintPhysics = true;
    private static UiParticleEngine.PerformanceMode performanceMode = UiParticleEngine.PerformanceMode.AUTO;

    private UiParticleSettings() { }

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
    public static boolean particleInteractions() { return particleInteractions; }
    public static void particleInteractions(boolean value) { particleInteractions = value; }
    public static boolean hoverInteractions() { return hoverInteractions; }
    public static void hoverInteractions(boolean value) { hoverInteractions = value; }
    public static boolean clickInteractions() { return clickInteractions; }
    public static void clickInteractions(boolean value) { clickInteractions = value; }
    public static boolean dragInteractions() { return dragInteractions; }
    public static void dragInteractions(boolean value) { dragInteractions = value; }
    public static boolean swipeInteractions() { return swipeInteractions; }
    public static void swipeInteractions(boolean value) { swipeInteractions = value; }
    public static boolean scrollInteractions() { return scrollInteractions; }
    public static void scrollInteractions(boolean value) { scrollInteractions = value; }
    public static boolean keyboardInteractions() { return keyboardInteractions; }
    public static void keyboardInteractions(boolean value) { keyboardInteractions = value; }
    public static boolean relationInteractions() { return relationInteractions; }
    public static void relationInteractions(boolean value) { relationInteractions = value; }
    public static boolean constraintPhysics() { return constraintPhysics; }
    public static void constraintPhysics(boolean value) { constraintPhysics = value; }
    public static UiParticleEngine.PerformanceMode performanceMode() { return performanceMode; }
    public static void performanceMode(UiParticleEngine.PerformanceMode value) { performanceMode = value == null ? UiParticleEngine.PerformanceMode.AUTO : value; }

    public static void apply(UiParticleEngine engine) {
        if (engine == null) return;
        engine.setDensityScale(density);
        engine.setMotionScale(motion);
        engine.setSoundEnabled(sounds);
        engine.setSoundVolume(soundVolume);
        engine.setFlashingEnabled(flashing);
        engine.setDebugEnabled(debug);
        engine.setParticleInteractionsEnabled(particleInteractions);
        engine.setHoverInteractionsEnabled(hoverInteractions);
        engine.setClickInteractionsEnabled(clickInteractions);
        engine.setDragInteractionsEnabled(dragInteractions);
        engine.setSwipeInteractionsEnabled(swipeInteractions);
        engine.setScrollInteractionsEnabled(scrollInteractions);
        engine.setKeyboardInteractionsEnabled(keyboardInteractions);
        engine.setRelationInteractionsEnabled(relationInteractions);
        engine.setConstraintPhysicsEnabled(constraintPhysics);
        engine.setPerformanceMode(performanceMode);
    }

    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
