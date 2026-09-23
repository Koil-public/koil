package com.spirit.koil.api.design.sprite.core;

/** Fixed-step clock separating Minecraft logic, actor physics and rendering. */
public final class SceneClock {
    public static final double MINECRAFT_STEP_SECONDS = 1.0D / 20.0D;
    public static final double PHYSICS_STEP_SECONDS = 1.0D / 60.0D;
    private static final double MAX_FRAME_SECONDS = 0.10D;
    private static final int MAX_GAME_STEPS_PER_FRAME = 4;
    private static final int MAX_PHYSICS_STEPS_PER_FRAME = 12;

    private double gameAccumulator;
    private double physicsAccumulator;
    private long gameTick;
    private long physicsStep;

    public void reset() {
        gameAccumulator = 0.0D;
        physicsAccumulator = 0.0D;
        gameTick = 0L;
        physicsStep = 0L;
    }

    public void advance(double frameSeconds, Runnable gameTickAction, Runnable physicsAction) {
        double dt = Math.max(0.0D, Math.min(MAX_FRAME_SECONDS, frameSeconds));
        gameAccumulator += dt;
        physicsAccumulator += dt;

        int gameSteps = 0;
        while (gameAccumulator >= MINECRAFT_STEP_SECONDS && gameSteps++ < MAX_GAME_STEPS_PER_FRAME) {
            gameAccumulator -= MINECRAFT_STEP_SECONDS;
            gameTick++;
            if (gameTickAction != null) gameTickAction.run();
        }
        if (gameSteps > MAX_GAME_STEPS_PER_FRAME) gameAccumulator = 0.0D;

        int physicsSteps = 0;
        while (physicsAccumulator >= PHYSICS_STEP_SECONDS && physicsSteps++ < MAX_PHYSICS_STEPS_PER_FRAME) {
            physicsAccumulator -= PHYSICS_STEP_SECONDS;
            physicsStep++;
            if (physicsAction != null) physicsAction.run();
        }
        if (physicsSteps > MAX_PHYSICS_STEPS_PER_FRAME) physicsAccumulator = 0.0D;
    }

    public long gameTick() { return gameTick; }
    public long physicsStep() { return physicsStep; }
    public float renderAlpha() {
        return (float) Math.max(0.0D, Math.min(1.0D, physicsAccumulator / PHYSICS_STEP_SECONDS));
    }

    /** Partial Minecraft tick used by native block/entity animations such as bells. */
    public float gameRenderAlpha() {
        return (float) Math.max(0.0D, Math.min(1.0D, gameAccumulator / MINECRAFT_STEP_SECONDS));
    }
}
