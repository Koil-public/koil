package com.spirit.koil.api.design.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.block.Block;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Reusable screen-space particle engine for Koil UI.
 *
 * <p>The normal render path uses Minecraft 1.20.1's own particle textures and
 * animation families, rendered as small screen-space sprites. Primitive drawing
 * remains only as a compatibility fallback for custom effects.</p>
 *
 * <p>Particles capture only the UI elements and sprites they overlap at birth.
 * Those specific birth overlaps are temporarily non-colliding until the particle
 * fully exits them. The immunity is then discarded permanently, so re-entry uses
 * normal collision physics. There is no timer-based spawn immunity.</p>
 *
 * <p>Frame order for a protected widget:</p>
 * <pre>
 * engine.beginFrame(...)
 * engine.renderBehind(context)
 * widget.render(...)
 * engine.renderForeground(context)
 * </pre>
 */
public final class KoilUiParticleEngine {
    public static final int DEFAULT_MAX_PARTICLES = 420;
    /**
     * Compatibility constant retained for older JSON/program code. Timed spawn
     * immunity is no longer used. Birth immunity is overlap-driven.
     */
    @Deprecated
    public static final float SPAWN_COLLISION_INVULNERABILITY_SECONDS = 0.0F;
    private static final float SOURCE_ERUPTION_MAX_SECONDS = 0.72F;
    private static final float SOURCE_CAPTURE_PADDING = 8.0F;
    private static final float SOURCE_FRONT_ALPHA_FLOOR = 0.94F;
    private static final float SOURCE_BACK_MIN_LIFETIME_SECONDS = 1.85F;

    private final Random random = new Random();
    private final List<Particle> particles = new ArrayList<>();
    private final List<Particle> pendingParticles = new ArrayList<>();
    private final List<Pulse> pulses = new ArrayList<>();
    private final List<UiCollider> buttonColliders = new ArrayList<>();
    private final Map<String, Double> emissionAccumulators = new HashMap<>();
    private final EffectContext effectContext = new EffectContext();
    private final List<String> effectPool = new ArrayList<>();
    private final Map<Identifier, Boolean> textureAvailability = new HashMap<>();
    private final List<ForceField> forceFields = new ArrayList<>();
    private SpawnOverrides activeSpawnOverrides = SpawnOverrides.EMPTY;
    private long spawnSequence;
    private long particleSequence;
    private String currentScreenId = "hud";

    private boolean debugEnabled;
    private boolean soundEnabled = true;
    private boolean flashingEnabled = true;
    private float densityScale = 1.0F;
    private float motionScale = 1.0F;
    private float soundVolume = 1.0F;
    private PerformanceMode performanceMode = PerformanceMode.AUTO;
    private float adaptiveQuality = 1.0F;
    private float smoothedFrameSeconds = 1.0F / 60.0F;

    private KoilUiParticleEffect currentEffect;
    private KoilUiParticleEffect previousEffect;
    private boolean wasActive;
    private boolean active;
    private long lastFrameNanos = System.nanoTime();
    private int maxParticles = DEFAULT_MAX_PARTICLES;
    private boolean updatingParticles;

    private int screenWidth = 1;
    private int screenHeight = 1;
    private int targetX;
    private int targetY;
    private int targetWidth = 1;
    private int targetHeight = 1;
    private float centerX;
    private float centerY;

    public KoilUiParticleEngine() {
        KoilUiParticlePackManager.loadBuiltinCatalogsOnce();
        KoilUiParticlePackManager.loadDefaultDirectoryOnce();
        KoilGameParticleRegistryBridge.registerAllAvailable();
        KoilUiParticleSettings.apply(this);
    }

    public void reset() {
        particles.clear();
        pendingParticles.clear();
        pulses.clear();
        buttonColliders.clear();
        emissionAccumulators.clear();
        forceFields.clear();
        textureAvailability.clear();
        currentEffect = null;
        previousEffect = null;
        wasActive = false;
        active = false;
        lastFrameNanos = System.nanoTime();
    }

    public void clearParticles() {
        particles.clear();
        pendingParticles.clear();
        pulses.clear();
        emissionAccumulators.clear();
        forceFields.clear();
    }

    public void setDebugEnabled(boolean enabled) { this.debugEnabled = enabled; }
    public boolean isDebugEnabled() { return debugEnabled; }
    public void setSoundEnabled(boolean enabled) { this.soundEnabled = enabled; KoilUiParticleSoundEngine.setEnabled(enabled); }
    public boolean isSoundEnabled() { return soundEnabled; }
    public void setSoundVolume(float volume) { this.soundVolume = clamp(volume, 0.0F, 1.5F); KoilUiParticleSoundEngine.setVolumeScale(this.soundVolume); }
    public float getSoundVolume() { return soundVolume; }
    public void setFlashingEnabled(boolean enabled) { this.flashingEnabled = enabled; }
    public boolean isFlashingEnabled() { return flashingEnabled; }
    public void setDensityScale(float scale) { this.densityScale = clamp(scale, 0.0F, 2.0F); }
    public float getDensityScale() { return densityScale; }
    public void setMotionScale(float scale) { this.motionScale = clamp(scale, 0.0F, 2.0F); }
    public float getMotionScale() { return motionScale; }
    public void setPerformanceMode(PerformanceMode mode) { this.performanceMode = mode == null ? PerformanceMode.AUTO : mode; }
    public PerformanceMode getPerformanceMode() { return performanceMode; }
    public float getAdaptiveQuality() { return adaptiveQuality; }
    public int getParticleCount() { return particles.size() + pendingParticles.size(); }
    public int getButtonColliderCount() { return buttonColliders.size(); }
    public float getEstimatedFps() { return smoothedFrameSeconds <= 0.0001F ? 0.0F : 1.0F / smoothedFrameSeconds; }
    /** Call after a resource-pack reload when an engine instance remains alive. */
    public void invalidateTextureCache() {
        textureAvailability.clear();
        KoilUiParticleTextureMetadata.clearCache();
        KoilGameParticleRegistryBridge.invalidateResourceCache();
    }

    public void setMaxParticles(int maxParticles) {
        this.maxParticles = Math.max(32, maxParticles);
        trimToLimit();
    }

    public int getMaxParticles() {
        return maxParticles;
    }

    /**
     * Restricts random selection to the supplied registered ids. Passing no ids
     * restores the full registry pool, including third-party registered effects.
     */
    public void setEffectPool(String... ids) {
        effectPool.clear();
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            if (id == null) {
                continue;
            }
            String normalized = id.trim().toLowerCase();
            if (!normalized.isEmpty() && !effectPool.contains(normalized)) {
                effectPool.add(normalized);
            }
        }
    }

    public String getCurrentEffectId() {
        return currentEffect == null ? "" : currentEffect.id();
    }

    /** Force a registered effect for the next/current activation. */
    public boolean selectEffect(String id, boolean restart) {
        KoilUiParticleEffect effect = KoilUiParticleRegistry.get(id);
        if (effect == null) {
            return false;
        }
        previousEffect = currentEffect;
        currentEffect = effect;
        emissionAccumulators.clear();
        if (restart) {
            clearParticles();
            KoilUiParticleSoundProfile.Cue beginCue = currentEffect.beginSound();
            if (soundEnabled && beginCue != null) KoilUiParticleSoundEngine.play(beginCue);
            currentEffect.onBegin(effectContext);
        }
        return true;
    }


    /** Triggers a registered effect at normal intensity. */
    public boolean trigger(String id) {
        return trigger(id, 1.0F);
    }

    /**
     * Triggers a registered effect as a one-shot eruption with a temporary
     * intensity multiplier. The multiplier affects the particles emitted by
     * the effect's begin phase without permanently changing the user's density
     * or motion settings.
     *
     * <p>This is the common entry point for KTL, JSON-driven UI actions,
     * debugging tools, and other Koil systems that need to fire a named effect
     * directly.</p>
     */
    public boolean trigger(String id, float scale) {
        return trigger(id, scale, SpawnOverrides.EMPTY, true);
    }

    /**
     * Triggers an effect without forcing callers to mutate the registered JSON
     * definition. Overrides exist only for particles emitted by this trigger.
     */
    public boolean trigger(String id, float scale, SpawnOverrides overrides, boolean clearExisting) {
        if (id == null || id.isBlank() || !KoilUiParticleRegistry.contains(id)) return false;

        float safeScale = clamp(scale, 0.05F, 8.0F);
        float previousDensity = densityScale;
        float previousMotion = motionScale;
        SpawnOverrides previousOverrides = activeSpawnOverrides;
        try {
            // /sprite scale is primarily a visual scale. Density is controlled
            // by count, so repeated triggers do not silently multiply emission.
            activeSpawnOverrides = (overrides == null ? SpawnOverrides.EMPTY : overrides)
                    .withDefaultVisualScale(safeScale);
            densityScale = previousDensity;
            motionScale = previousMotion;
            KoilUiParticleEffect effect = KoilUiParticleRegistry.get(id);
            if (effect == null) return false;
            previousEffect = currentEffect;
            currentEffect = effect;
            emissionAccumulators.clear();
            if (clearExisting) clearParticles();
            KoilUiParticleSoundProfile.Cue beginCue = currentEffect.beginSound();
            if (soundEnabled && beginCue != null) KoilUiParticleSoundEngine.play(beginCue);
            spawnSequence++;
            currentEffect.onBegin(effectContext);
            return true;
        } finally {
            activeSpawnOverrides = previousOverrides;
            densityScale = previousDensity;
            motionScale = previousMotion;
        }
    }

    /** Additive trigger used by /sprite count so existing particles are never cleared. */
    public boolean triggerAdditive(String id, float visualScale, SpawnOverrides overrides) {
        return trigger(id, visualScale, overrides, false);
    }

    /**
     * Fires an effect at arbitrary screen coordinates without advancing the
     * simulation clock. Useful for HUD/network commands and scripted systems.
     */
    public boolean triggerAt(String id, float x, float y, float width, float height,
                             float visualScale, SpawnOverrides overrides, boolean clearExisting) {
        this.targetX = Math.round(x);
        this.targetY = Math.round(y);
        this.targetWidth = Math.max(1, Math.round(width));
        this.targetHeight = Math.max(1, Math.round(height));
        this.centerX = x + width * 0.5F;
        this.centerY = y + height * 0.5F;
        return trigger(id, visualScale, overrides, clearExisting);
    }

    public void beginFrame(
            int screenWidth,
            int screenHeight,
            int targetX,
            int targetY,
            int targetWidth,
            int targetHeight,
            String widgetProfileId,
            boolean active
    ) {
        KoilUiParticleWidgetProfileRegistry.apply(this, widgetProfileId);
        beginFrame(screenWidth, screenHeight, targetX, targetY, targetWidth, targetHeight, active);
    }

    public void beginFrame(
            int screenWidth,
            int screenHeight,
            int targetX,
            int targetY,
            int targetWidth,
            int targetHeight,
            boolean active
    ) {
        // Main.reloadDesign() can change the active theme while this engine is alive.
        // This is a cheap path comparison unless the themed particle directory changed.
        KoilUiParticlePackManager.loadDefaultDirectoryOnce();

        this.screenWidth = Math.max(1, screenWidth);
        this.screenHeight = Math.max(1, screenHeight);
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetWidth = Math.max(1, targetWidth);
        this.targetHeight = Math.max(1, targetHeight);
        this.centerX = targetX + this.targetWidth * 0.5F;
        this.centerY = targetY + this.targetHeight * 0.5F;
        this.active = active;
        MinecraftClient minecraft = MinecraftClient.getInstance();
        Screen currentScreen = minecraft == null ? null : minecraft.currentScreen;
        this.currentScreenId = currentScreen == null ? "hud" : currentScreen.getClass().getName();
        collectButtonColliders();

        float dt = frameDeltaSeconds();
        updateAdaptiveQuality(dt);
        updateForceFields(dt);

        if (active && !wasActive) {
            KoilUiParticleEffect next = KoilUiParticleRegistry.random(random, previousEffect, effectPool);
            if (next != null) {
                currentEffect = next;
                previousEffect = next;
                emissionAccumulators.clear();
                forceFields.clear();
                KoilUiParticleSoundProfile.Cue beginCue = currentEffect.beginSound();
                if (soundEnabled && beginCue != null) KoilUiParticleSoundEngine.play(beginCue);
                currentEffect.onBegin(effectContext);
            }
        }

        if (active && currentEffect != null) {
            currentEffect.onTick(effectContext, dt);
        } else if (!active) {
            emissionAccumulators.clear();
        }

        updateParticles(dt);
        updatePulses(dt);
        wasActive = active;
    }

    public void renderBehind(DrawContext context) {
        renderPulses(context, Layer.BACK);
        renderParticles(context, Layer.BACK);
    }

    public void renderForeground(DrawContext context) {
        renderPulses(context, Layer.FRONT);
        renderParticles(context, Layer.FRONT);
        if (debugEnabled) renderDebug(context);
    }

    private float frameDeltaSeconds() {
        long now = System.nanoTime();
        long elapsed = now - lastFrameNanos;
        lastFrameNanos = now;
        if (elapsed <= 0L) {
            return 1.0F / 60.0F;
        }
        return clamp(elapsed / 1_000_000_000.0F, 1.0F / 240.0F, 0.05F);
    }

    private void updateAdaptiveQuality(float dt) {
        smoothedFrameSeconds = smoothedFrameSeconds * 0.92F + dt * 0.08F;
        adaptiveQuality = switch (performanceMode) {
            case HIGH -> 1.0F;
            case MEDIUM -> 0.72F;
            case LOW -> 0.46F;
            case AUTO -> {
                float fps = 1.0F / Math.max(0.001F, smoothedFrameSeconds);
                if (fps >= 58.0F) yield 1.0F;
                if (fps >= 45.0F) yield 0.82F;
                if (fps >= 32.0F) yield 0.62F;
                yield 0.42F;
            }
        };
    }

    private float effectiveDensityScale() {
        return densityScale * adaptiveQuality;
    }

    private void fireParticleEvent(
            Particle particle,
            ParticleEventHandler handler,
            CollisionKind kind,
            float normalX,
            float normalY,
            float impactSpeed,
            float deltaSeconds
    ) {
        if (handler == null || particle == null) return;
        handler.handle(new ParticleEventContext(particle, kind, normalX, normalY, impactSpeed, deltaSeconds));
    }

    private void notifyBounce(Particle particle, CollisionKind kind, float normalX, float normalY, float impactSpeed) {
        if (particle == null || particle.removeRequested || impactSpeed < 8.0F) return;
        if (particle.age - particle.lastBounceAge < 0.035F) return;
        particle.lastBounceAge = particle.age;
        particle.bounceCount++;
        if (soundEnabled && particle.soundProfile != null && particle.soundProfile.bounce() != null
                && impactSpeed >= particle.soundProfile.minimumBounceSpeed()) {
            float impactVolume = clamp(impactSpeed / 120.0F, 0.32F, 1.0F);
            KoilUiParticleSoundEngine.play(particle.soundProfile.bounce(), impactVolume, 1.0F);
        }
        applyMaterialCollisionResponse(particle, kind, normalX, normalY, impactSpeed);
        fireParticleEvent(particle, particle.onBounce, kind, normalX, normalY, impactSpeed, 0.0F);
    }

    private void updateParticles(float dt) {
        updatingParticles = true;
        try {
            Iterator<Particle> iterator = particles.iterator();
            while (iterator.hasNext()) {
                Particle particle = iterator.next();
                particle.age += dt;

                if (particle.age >= particle.lifetime) {
                    fireParticleEvent(particle, particle.onExpire, CollisionKind.EXPIRE, 0.0F, 0.0F, 0.0F, 0.0F);
                    if (soundEnabled && particle.soundProfile != null && particle.soundProfile.expire() != null) {
                        KoilUiParticleSoundEngine.play(particle.soundProfile.expire());
                    }
                    iterator.remove();
                    continue;
                }

                if (particle.stuckUntil > particle.age || particle.sleeping) {
                    particle.vx = 0.0F;
                    particle.vy = 0.0F;
                    fireParticleEvent(particle, particle.onTick, CollisionKind.TICK, 0.0F, 0.0F, 0.0F, dt);
                    continue;
                }

                float physicsDt = dt * motionScale;
                applyForceFields(particle, physicsDt);
                applyBehavior(particle, physicsDt);

                particle.vy += particle.gravity * physicsDt;
                float frameDrag = (float) Math.pow(clamp(particle.drag, 0.0F, 1.0F), physicsDt * 60.0F);
                particle.vx *= frameDrag;
                particle.vy *= frameDrag;
                particle.prevX = particle.x;
                particle.prevY = particle.y;
                particle.x += particle.vx * physicsDt;
                particle.y += particle.vy * physicsDt;
                particle.rotation += particle.angularVelocity * physicsDt;
                particle.angularVelocity *= (float) Math.pow(clamp(particle.angularDrag, 0.0F, 1.0F), physicsDt * 60.0F);
                if (particle.stretchWithVelocity) {
                    float speed = (float) Math.sqrt(particle.vx * particle.vx + particle.vy * particle.vy);
                    particle.dynamicStretchY = clamp(1.0F + speed / 180.0F, 1.0F, 1.65F);
                    particle.dynamicStretchX = 1.0F / (float) Math.sqrt(particle.dynamicStretchY);
                }

                ParticleBounds collisionBounds = particleVisualBounds(particle, true);
                if (particle.collideButtons) {
                    resolveButtonCollisions(particle, collisionBounds.halfWidth(), collisionBounds.halfHeight());
                } else if (particle.collideTarget) {
                    resolveTargetCollision(particle, collisionBounds.halfWidth(), collisionBounds.halfHeight());
                }
                if (!particle.removeRequested && particle.collideScreen) {
                    resolveScreenCollision(particle, collisionBounds.halfWidth(), collisionBounds.halfHeight());
                }

                if (!particle.removeRequested) {
                    fireParticleEvent(particle, particle.onTick, CollisionKind.TICK, 0.0F, 0.0F, 0.0F, dt);
                }

                if (particle.removeRequested) {
                    iterator.remove();
                    continue;
                }

                if (particle.x < -140.0F || particle.x > screenWidth + 140.0F
                        || particle.y < -160.0F || particle.y > screenHeight + 160.0F) {
                    iterator.remove();
                }
            }
        } finally {
            updatingParticles = false;
        }

        if (!pendingParticles.isEmpty()) {
            int room = Math.max(0, maxParticles - particles.size());
            if (room > 0) {
                particles.addAll(pendingParticles.subList(0, Math.min(room, pendingParticles.size())));
            }
            pendingParticles.clear();
        }
        updateParticleInteractions();
    }

    private void applyBehavior(Particle particle, float dt) {
        switch (particle.behavior) {
            case BALLISTIC -> {
            }
            case WANDER -> {
                float time = particle.age * particle.frequency + particle.seed;
                particle.vx += (float) Math.sin(time * 1.71F) * particle.force * dt;
                particle.vy += (float) Math.cos(time * 1.29F) * particle.force * 0.55F * dt;
            }
            case ORBIT -> {
                float dx = particle.x - particle.anchorX;
                float dy = particle.y - particle.anchorY;
                float distance = Math.max(1.0F, (float) Math.sqrt(dx * dx + dy * dy));
                float nx = dx / distance;
                float ny = dy / distance;
                float radialError = particle.orbitRadius - distance;
                float tangentX = -ny * particle.force;
                float tangentY = nx * particle.force;
                particle.vx += (tangentX + nx * radialError * particle.spring) * dt;
                particle.vy += (tangentY + ny * radialError * particle.spring) * dt;
            }
            case VORTEX -> {
                float dx = particle.x - particle.anchorX;
                float dy = particle.y - particle.anchorY;
                float distance = Math.max(2.0F, (float) Math.sqrt(dx * dx + dy * dy));
                float nx = dx / distance;
                float ny = dy / distance;
                float direction = particle.force >= 0.0F ? 1.0F : -1.0F;
                float spin = Math.abs(particle.force);
                particle.vx += (-ny * spin * direction - nx * particle.spring) * dt;
                particle.vy += (nx * spin * direction - ny * particle.spring) * dt;
            }
            case ATTRACT -> {
                float dx = particle.anchorX - particle.x;
                float dy = particle.anchorY - particle.y;
                float distanceSq = Math.max(24.0F, dx * dx + dy * dy);
                float distance = (float) Math.sqrt(distanceSq);
                float strength = particle.force / distanceSq;
                particle.vx += (dx / distance) * strength * 920.0F * dt;
                particle.vy += (dy / distance) * strength * 920.0F * dt;
            }
            case RISE_AND_WANDER -> {
                float time = particle.age * particle.frequency + particle.seed;
                particle.vx += (float) Math.sin(time) * particle.force * dt;
                particle.vy -= particle.lift * dt;
            }
            case FALL_AND_SWAY -> {
                float time = particle.age * particle.frequency + particle.seed;
                particle.vx += (float) Math.sin(time) * particle.force * dt;
            }
            case RISING_SMOKE -> {
                float time = particle.age * particle.frequency + particle.seed;
                particle.vx += (float) Math.sin(time * 0.83F) * Math.max(3.0F, particle.force) * dt;
                particle.vy -= Math.max(7.0F, particle.lift) * dt;
                particle.visualGrowth = 1.0F + clamp(particle.age / particle.lifetime, 0.0F, 1.0F) * 0.55F;
            }
            case DRIFT -> {
                float time = particle.age * particle.frequency + particle.seed;
                particle.vx += (float) Math.sin(time) * Math.max(2.5F, particle.force) * dt;
                particle.vy += (float) Math.cos(time * 0.67F) * Math.max(1.5F, particle.force * 0.35F) * dt;
            }
            case FLUTTER -> {
                float time = particle.age * Math.max(2.5F, particle.frequency) + particle.seed;
                particle.vx += (float) Math.sin(time) * Math.max(7.0F, particle.force) * dt;
                particle.rotation += (float) Math.sin(time * 0.63F) * 38.0F * dt;
            }
            case RICOCHET -> {
                // Ballistic motion with collision response handled by the solver.
            }
            case FUSE_BURN -> {
                particle.vy -= Math.max(0.0F, particle.lift) * 0.25F * dt;
            }
            case ROCKET_ASCENT -> {
                particle.vy -= Math.max(12.0F, particle.lift) * dt;
                particle.vx += (float) Math.sin(particle.age * 8.0F + particle.seed) * 3.0F * dt;
            }
            case BURST_SHELL -> {
                // Ballistic with a slightly stronger late-life drag handled by base drag.
            }
            case STICKY_HONEY -> {
                float time = particle.age * 5.0F + particle.seed;
                particle.vx += (float) Math.sin(time) * 2.2F * dt;
            }
            case SPLASHING_LIQUID -> {
                // Ballistic until a collision, where optional splash children are emitted.
            }
            case WOBBLE_SLIME -> {
                float time = particle.age * 10.0F + particle.seed;
                particle.visualSquash = 1.0F + (float) Math.sin(time) * 0.08F;
            }
            case SPIRAL -> {
                float time = particle.age * Math.max(4.0F, particle.frequency) + particle.seed;
                particle.vx += (float) Math.cos(time) * Math.max(6.0F, particle.force) * dt;
                particle.vy += (float) Math.sin(time) * Math.max(6.0F, particle.force) * dt - Math.max(0.0F, particle.lift) * dt;
            }
        }
    }


    /**
     * Burst-like particles authored inside the target are projected to the
     * perimeter along their velocity before the first physics step. This makes
     * explosions read as eruptions from the widget frame even when an effect
     * author used ctx.particle(shape) instead of borderSpawn().
     */
    private void projectBurstSpawnToSourcePerimeter(Particle particle) {
        if (particle == null) {
            return;
        }

        float left = targetX;
        float right = targetX + targetWidth;
        float top = targetY;
        float bottom = targetY + targetHeight;
        boolean inside = particle.x > left && particle.x < right
                && particle.y > top && particle.y < bottom;
        if (!inside) {
            return;
        }

        float speedSq = particle.vx * particle.vx + particle.vy * particle.vy;
        if (speedSq < 26.0F * 26.0F) {
            return;
        }

        float tx = Float.POSITIVE_INFINITY;
        float ty = Float.POSITIVE_INFINITY;

        if (particle.vx > 0.001F) {
            tx = (right - particle.x) / particle.vx;
        } else if (particle.vx < -0.001F) {
            tx = (left - particle.x) / particle.vx;
        }

        if (particle.vy > 0.001F) {
            ty = (bottom - particle.y) / particle.vy;
        } else if (particle.vy < -0.001F) {
            ty = (top - particle.y) / particle.vy;
        }

        float travel = Math.min(tx > 0.0F ? tx : Float.POSITIVE_INFINITY,
                ty > 0.0F ? ty : Float.POSITIVE_INFINITY);
        if (!Float.isFinite(travel)) {
            return;
        }

        particle.x += particle.vx * travel;
        particle.y += particle.vy * travel;
        particle.prevX = particle.x;
        particle.prevY = particle.y;
    }

    /**
     * Captures the hovered target when the particle is born overlapping it.
     * Immunity is overlap-driven: the target stays non-colliding only until the
     * particle fully exits the target bounds. Re-entry is physical.
     */
    private void prepareSourceButtonExit(Particle particle) {
        if (particle == null || !particle.sourceButtonExit) {
            return;
        }

        ParticleBounds visualBounds = particleVisualBounds(particle, true);
        float halfWidth = visualBounds.halfWidth();
        float halfHeight = visualBounds.halfHeight();
        if (aabbOverlaps(
                particle.x - halfWidth, particle.y - halfHeight,
                particle.x + halfWidth, particle.y + halfHeight,
                targetX, targetY, targetX + targetWidth, targetY + targetHeight)) {
            particle.ignoreSourceButtonUntilExit = true;
            particle.sourceButtonCaptured = true;
            particle.spawnTargetImmunity = true;
            particle.sourceLeft = targetX;
            particle.sourceTop = targetY;
            particle.sourceRight = targetX + targetWidth;
            particle.sourceBottom = targetY + targetHeight;
            if (particle.layer == Layer.BACK) {
                particle.lifetime = Math.max(
                        particle.lifetime,
                        SOURCE_BACK_MIN_LIFETIME_SECONDS + random.nextFloat() * 0.65F
                );
            }
        }
    }

    private boolean isSourceButtonCollider(Particle particle, UiCollider collider) {
        if (!particle.sourceButtonCaptured) {
            return false;
        }
        // Coordinates are intentionally tolerant because some widgets mutate
        // width by a pixel during layout/scale changes.
        return Math.abs(collider.left - particle.sourceLeft) <= 1.5F
                && Math.abs(collider.top - particle.sourceTop) <= 1.5F
                && Math.abs(collider.right - particle.sourceRight) <= 1.5F
                && Math.abs(collider.bottom - particle.sourceBottom) <= 1.5F;
    }

    private boolean sourceEruptionVisible(Particle particle) {
        if (!particle.sourceButtonCaptured) return false;
        ParticleBounds bounds = particleVisualBounds(particle, true);
        return aabbOverlaps(
                particle.x - bounds.halfWidth(), particle.y - bounds.halfHeight(),
                particle.x + bounds.halfWidth(), particle.y + bounds.halfHeight(),
                particle.sourceLeft, particle.sourceTop, particle.sourceRight, particle.sourceBottom);
    }

    /**
     * Safety valve for pathological low-speed particles. If an effect leaves a
     * particle inside the source for too long, move it to the nearest perimeter
     * and preserve an outward component instead of allowing a permanent trap.
     */
    private void forceParticleOutOfSource(Particle particle, float radius) {
        float left = particle.sourceLeft - radius;
        float right = particle.sourceRight + radius;
        float top = particle.sourceTop - radius;
        float bottom = particle.sourceBottom + radius;

        float toLeft = Math.abs(particle.x - left);
        float toRight = Math.abs(right - particle.x);
        float toTop = Math.abs(particle.y - top);
        float toBottom = Math.abs(bottom - particle.y);
        float minimum = Math.min(Math.min(toLeft, toRight), Math.min(toTop, toBottom));
        float minimumExitSpeed = 34.0F;

        if (minimum == toLeft) {
            particle.x = left - 0.5F;
            particle.vx = -Math.max(Math.abs(particle.vx), minimumExitSpeed);
        } else if (minimum == toRight) {
            particle.x = right + 0.5F;
            particle.vx = Math.max(Math.abs(particle.vx), minimumExitSpeed);
        } else if (minimum == toTop) {
            particle.y = top - 0.5F;
            particle.vy = -Math.max(Math.abs(particle.vy), minimumExitSpeed);
        } else {
            particle.y = bottom + 0.5F;
            particle.vy = Math.max(Math.abs(particle.vy), minimumExitSpeed);
        }

        particle.prevX = particle.x;
        particle.prevY = particle.y;
        particle.ignoreSourceButtonUntilExit = false;
    }

    private boolean insideExpandedCollider(Particle particle, UiCollider collider, float radius) {
        return particle.x > collider.left - radius
                && particle.x < collider.right + radius
                && particle.y > collider.top - radius
                && particle.y < collider.bottom + radius;
    }


    private boolean aabbOverlaps(float leftA, float topA, float rightA, float bottomA,
                                 float leftB, float topB, float rightB, float bottomB) {
        return rightA > leftB && leftA < rightB && bottomA > topB && topA < bottomB;
    }

    /**
     * Captures only geometry and sprites physically overlapped at birth. Those
     * exact overlaps are ignored until the new particle fully leaves them.
     */
    private void captureSpawnOverlapImmunity(Particle particle) {
        if (particle == null) return;

        ParticleBounds bounds = particleVisualBounds(particle, true);
        float left = particle.x - bounds.halfWidth();
        float right = particle.x + bounds.halfWidth();
        float top = particle.y - bounds.halfHeight();
        float bottom = particle.y + bounds.halfHeight();

        particle.spawnElementImmunities.clear();
        if (particle.collideButtons) {
            for (UiCollider collider : buttonColliders) {
                if (aabbOverlaps(left, top, right, bottom, collider.left, collider.top, collider.right, collider.bottom)) {
                    particle.spawnElementImmunities.add(new SpawnElementImmunity(
                            collider.left, collider.top, collider.right, collider.bottom));
                }
            }
        }

        if (particle.collideTarget && aabbOverlaps(
                left, top, right, bottom,
                targetX, targetY, targetX + targetWidth, targetY + targetHeight)) {
            particle.spawnTargetImmunity = true;
        }

        particle.spawnParticleImmunities.clear();
        captureOverlappingParticles(particle, particles);
        captureOverlappingParticles(particle, pendingParticles);
    }

    private void captureOverlappingParticles(Particle particle, List<Particle> candidates) {
        ParticleBounds boundsA = particleVisualBounds(particle, true);
        for (Particle other : candidates) {
            if (other == null || other == particle || other.removeRequested || other.particleId == 0L) continue;
            ParticleBounds boundsB = particleVisualBounds(other, true);
            if (aabbOverlaps(
                    particle.x - boundsA.halfWidth(), particle.y - boundsA.halfHeight(),
                    particle.x + boundsA.halfWidth(), particle.y + boundsA.halfHeight(),
                    other.x - boundsB.halfWidth(), other.y - boundsB.halfHeight(),
                    other.x + boundsB.halfWidth(), other.y + boundsB.halfHeight())) {
                particle.spawnParticleImmunities.add(other.particleId);
            }
        }
    }

    private boolean hasActiveElementSpawnImmunity(Particle particle, UiCollider collider,
                                                   float halfWidth, float halfHeight) {
        if (particle.spawnElementImmunities.isEmpty()) return false;
        Iterator<SpawnElementImmunity> iterator = particle.spawnElementImmunities.iterator();
        while (iterator.hasNext()) {
            SpawnElementImmunity immunity = iterator.next();
            if (!sameColliderBounds(immunity, collider)) continue;

            boolean stillOverlapping = aabbOverlaps(
                    particle.x - halfWidth, particle.y - halfHeight,
                    particle.x + halfWidth, particle.y + halfHeight,
                    collider.left, collider.top, collider.right, collider.bottom);
            if (stillOverlapping) return true;

            // Once fully clear, this specific birth immunity is gone forever.
            iterator.remove();
            if (isSourceButtonCollider(particle, collider)) {
                particle.ignoreSourceButtonUntilExit = false;
                particle.spawnTargetImmunity = false;
            }
            // Skip the collider for this final exit step so the swept solver
            // cannot bounce the particle back into the element it just cleared.
            return true;
        }
        return false;
    }

    private boolean sameColliderBounds(SpawnElementImmunity immunity, UiCollider collider) {
        return Math.abs(immunity.left - collider.left) <= 1.5F
                && Math.abs(immunity.top - collider.top) <= 1.5F
                && Math.abs(immunity.right - collider.right) <= 1.5F
                && Math.abs(immunity.bottom - collider.bottom) <= 1.5F;
    }

    private boolean particlesOverlap(Particle a, Particle b) {
        ParticleBounds boundsA = particleVisualBounds(a, true);
        ParticleBounds boundsB = particleVisualBounds(b, true);
        return aabbOverlaps(
                a.x - boundsA.halfWidth(), a.y - boundsA.halfHeight(),
                a.x + boundsA.halfWidth(), a.y + boundsA.halfHeight(),
                b.x - boundsB.halfWidth(), b.y - boundsB.halfHeight(),
                b.x + boundsB.halfWidth(), b.y + boundsB.halfHeight());
    }

    private void refreshSpawnParticleImmunities() {
        if (particles.isEmpty()) return;
        Map<Long, Particle> byId = new HashMap<>();
        for (Particle particle : particles) {
            if (particle != null && !particle.removeRequested && particle.particleId != 0L) {
                byId.put(particle.particleId, particle);
            }
        }
        for (Particle particle : particles) {
            if (particle.spawnParticleImmunities.isEmpty()) continue;
            Iterator<Long> iterator = particle.spawnParticleImmunities.iterator();
            while (iterator.hasNext()) {
                Particle other = byId.get(iterator.next());
                if (other == null || !particlesOverlap(particle, other)) {
                    // Once the pair separates, re-entry becomes physical.
                    iterator.remove();
                }
            }
        }
    }

    private boolean hasActiveParticleSpawnImmunity(Particle a, Particle b) {
        return a.spawnParticleImmunities.contains(b.particleId)
                || b.spawnParticleImmunities.contains(a.particleId);
    }

    private float particleCollisionRadius(Particle particle) {
        ParticleBounds bounds = particleVisualBounds(particle, true);
        return Math.max(bounds.halfWidth(), bounds.halfHeight());
    }

    /**
     * Rebuilds the screen-space collision world every rendered frame.
     * TexturedButtonWidget and normal vanilla button subclasses are covered
     * because they inherit ButtonWidget.
     */
    private void collectButtonColliders() {
        buttonColliders.clear();
        MinecraftClient client = MinecraftClient.getInstance();
        Screen screen = client == null ? null : client.currentScreen;
        if (screen == null) {
            return;
        }

        Set<Element> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectButtonCollidersRecursive(screen, visited);
        for (KoilUiColliderRegistry.Bounds bounds : KoilUiColliderRegistry.collect(screen)) {
            if (bounds != null && bounds.width() > 0.0F && bounds.height() > 0.0F) {
                buttonColliders.add(new UiCollider(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height()));
            }
        }
    }

    /**
     * Recurses through ParentElement containers so buttons nested inside vanilla
     * list widgets, option rows, tab containers, and similar compound controls
     * participate in particle collision too. Disabled buttons remain solid; only
     * invisible buttons are ignored.
     */
    private void collectButtonCollidersRecursive(Element element, Set<Element> visited) {
        if (element == null || !visited.add(element)) {
            return;
        }

        if (element instanceof ButtonWidget button && button.visible) {
            int width = button.getWidth();
            int height = button.getHeight();
            if (width > 0 && height > 0) {
                buttonColliders.add(new UiCollider(
                        button.getX(),
                        button.getY(),
                        button.getX() + width,
                        button.getY() + height
                ));
            }
        }

        if (element instanceof ParentElement parent) {
            for (Element child : parent.children()) {
                collectButtonCollidersRecursive(child, visited);
            }
        }
    }

    /**
     * Uses a swept point-vs-expanded-AABB test. Expanding by the particle radius
     * approximates swept-circle collision and prevents fast sparks from
     * tunnelling through narrow vanilla buttons.
     */
    private void resolveButtonCollisions(Particle particle, float halfWidth, float halfHeight) {
        for (UiCollider collider : buttonColliders) {
            // Ignore only colliders that this particle actually overlapped when
            // it was born. The immunity disappears permanently the moment the
            // particle's visual/physical bounds fully leave that collider.
            if (hasActiveElementSpawnImmunity(particle, collider, halfWidth, halfHeight)) {
                continue;
            }

            float left = collider.left - halfWidth;
            float right = collider.right + halfWidth;
            float top = collider.top - halfHeight;
            float bottom = collider.bottom + halfHeight;

            if (particle.prevX > left && particle.prevX < right
                    && particle.prevY > top && particle.prevY < bottom) {
                resolveEmbeddedCollision(particle, left, right, top, bottom);
                continue;
            }

            float dx = particle.x - particle.prevX;
            float dy = particle.y - particle.prevY;
            SweptHit hit = sweptAabb(particle.prevX, particle.prevY, dx, dy, left, right, top, bottom);
            if (hit == null) {
                if (particle.x > left && particle.x < right && particle.y > top && particle.y < bottom) {
                    resolveEmbeddedCollision(particle, left, right, top, bottom);
                }
                continue;
            }

            particle.x = particle.prevX + dx * hit.time + hit.normalX * 0.35F;
            particle.y = particle.prevY + dy * hit.time + hit.normalY * 0.35F;
            reflectVelocity(particle, hit.normalX, hit.normalY);
            if (particle.removeRequested) return;
            particle.prevX = particle.x;
            particle.prevY = particle.y;
        }
    }


    private boolean colliderOverlapsSourceTarget(Particle particle, UiCollider collider) {
        if (particle == null || collider == null || !particle.sourceButtonCaptured) return false;
        return collider.right > particle.sourceLeft
                && collider.left < particle.sourceRight
                && collider.bottom > particle.sourceTop
                && collider.top < particle.sourceBottom;
    }

    private SweptHit sweptAabb(
            float startX,
            float startY,
            float dx,
            float dy,
            float left,
            float right,
            float top,
            float bottom
    ) {
        float nearX;
        float farX;
        if (Math.abs(dx) < 0.0001F) {
            if (startX <= left || startX >= right) {
                return null;
            }
            nearX = Float.NEGATIVE_INFINITY;
            farX = Float.POSITIVE_INFINITY;
        } else {
            float tx1 = (left - startX) / dx;
            float tx2 = (right - startX) / dx;
            nearX = Math.min(tx1, tx2);
            farX = Math.max(tx1, tx2);
        }

        float nearY;
        float farY;
        if (Math.abs(dy) < 0.0001F) {
            if (startY <= top || startY >= bottom) {
                return null;
            }
            nearY = Float.NEGATIVE_INFINITY;
            farY = Float.POSITIVE_INFINITY;
        } else {
            float ty1 = (top - startY) / dy;
            float ty2 = (bottom - startY) / dy;
            nearY = Math.min(ty1, ty2);
            farY = Math.max(ty1, ty2);
        }

        float entry = Math.max(nearX, nearY);
        float exit = Math.min(farX, farY);
        if (entry > exit || exit < 0.0F || entry > 1.0F || entry < 0.0F) {
            return null;
        }

        if (nearX > nearY) {
            return new SweptHit(entry, dx > 0.0F ? -1.0F : 1.0F, 0.0F);
        }
        return new SweptHit(entry, 0.0F, dy > 0.0F ? -1.0F : 1.0F);
    }

    private void resolveEmbeddedCollision(Particle particle, float left, float right, float top, float bottom) {
        float toLeft = Math.abs(particle.x - left);
        float toRight = Math.abs(right - particle.x);
        float toTop = Math.abs(particle.y - top);
        float toBottom = Math.abs(bottom - particle.y);
        float minimum = Math.min(Math.min(toLeft, toRight), Math.min(toTop, toBottom));

        if (minimum == toLeft) {
            particle.x = left - 0.35F;
            reflectVelocity(particle, -1.0F, 0.0F);
        } else if (minimum == toRight) {
            particle.x = right + 0.35F;
            reflectVelocity(particle, 1.0F, 0.0F);
        } else if (minimum == toTop) {
            particle.y = top - 0.35F;
            reflectVelocity(particle, 0.0F, -1.0F);
        } else {
            particle.y = bottom + 0.35F;
            reflectVelocity(particle, 0.0F, 1.0F);
        }
    }

    private void reflectVelocity(Particle particle, float normalX, float normalY) {
        float restitution = clamp(particle.restitution, 0.05F, 0.95F);
        float normalSpeed = particle.vx * normalX + particle.vy * normalY;
        if (normalSpeed >= 0.0F) {
            return;
        }
        float impactSpeed = Math.abs(normalSpeed);

        particle.vx -= (1.0F + restitution) * normalSpeed * normalX;
        particle.vy -= (1.0F + restitution) * normalSpeed * normalY;

        if (normalX != 0.0F) {
            particle.vy *= particle.surfaceFriction;
        } else {
            particle.vx *= particle.surfaceFriction;
        }
        particle.angularVelocity *= -0.72F;
        notifyBounce(particle, CollisionKind.BUTTON, normalX, normalY, impactSpeed);
    }

    private void resolveTargetCollision(Particle particle, float halfWidth, float halfHeight) {
        if (particle.spawnTargetImmunity) {
            boolean stillOverlapping = aabbOverlaps(
                    particle.x - halfWidth, particle.y - halfHeight,
                    particle.x + halfWidth, particle.y + halfHeight,
                    targetX, targetY, targetX + targetWidth, targetY + targetHeight);
            if (stillOverlapping) {
                return;
            }
            particle.spawnTargetImmunity = false;
            particle.ignoreSourceButtonUntilExit = false;
        }
        float left = targetX - halfWidth;
        float right = targetX + targetWidth + halfWidth;
        float top = targetY - halfHeight;
        float bottom = targetY + targetHeight + halfHeight;
        if (particle.x <= left || particle.x >= right || particle.y <= top || particle.y >= bottom) {
            return;
        }

        float toLeft = Math.abs(particle.x - left);
        float toRight = Math.abs(right - particle.x);
        float toTop = Math.abs(particle.y - top);
        float toBottom = Math.abs(bottom - particle.y);
        float minimum = Math.min(Math.min(toLeft, toRight), Math.min(toTop, toBottom));
        float restitution = clamp(particle.restitution, 0.05F, 0.95F);
        float impactVx = particle.vx;
        float impactVy = particle.vy;
        float nx = 0.0F;
        float ny = 0.0F;

        if (minimum == toLeft) {
            particle.x = left;
            particle.vx = -Math.abs(particle.vx) * restitution;
            nx = -1.0F;
        } else if (minimum == toRight) {
            particle.x = right;
            particle.vx = Math.abs(particle.vx) * restitution;
            nx = 1.0F;
        } else if (minimum == toTop) {
            particle.y = top;
            particle.vy = -Math.abs(particle.vy) * restitution;
            ny = -1.0F;
        } else {
            particle.y = bottom;
            particle.vy = Math.abs(particle.vy) * restitution;
            ny = 1.0F;
        }
        particle.angularVelocity *= -0.78F;
        notifyBounce(particle, CollisionKind.TARGET, nx, ny, Math.abs(impactVx * nx + impactVy * ny));
    }

    private void resolveScreenCollision(Particle particle, float halfWidth, float halfHeight) {
        float restitution = clamp(particle.restitution, 0.05F, 0.95F);
        if (particle.x - halfWidth < 1.0F) {
            float impact = Math.abs(particle.vx);
            particle.x = 1.0F + halfWidth;
            particle.vx = Math.abs(particle.vx) * restitution;
            particle.angularVelocity *= -0.78F;
            notifyBounce(particle, CollisionKind.SCREEN, 1.0F, 0.0F, impact);
        } else if (particle.x + halfWidth > screenWidth - 1.0F) {
            float impact = Math.abs(particle.vx);
            particle.x = screenWidth - 1.0F - halfWidth;
            particle.vx = -Math.abs(particle.vx) * restitution;
            particle.angularVelocity *= -0.78F;
            notifyBounce(particle, CollisionKind.SCREEN, -1.0F, 0.0F, impact);
        }

        if (particle.removeRequested) return;

        if (particle.y - halfHeight < 1.0F && particle.bounceCeiling) {
            float impact = Math.abs(particle.vy);
            particle.y = 1.0F + halfHeight;
            particle.vy = Math.abs(particle.vy) * restitution;
            notifyBounce(particle, CollisionKind.SCREEN, 0.0F, 1.0F, impact);
        }
        if (particle.removeRequested) return;

        if (particle.y + halfHeight > screenHeight - 1.0F) {
            float impact = Math.abs(particle.vy);
            particle.y = screenHeight - 1.0F - halfHeight;
            particle.vy = -Math.abs(particle.vy) * restitution;
            particle.vx *= 0.82F;
            particle.angularVelocity *= 0.86F;
            notifyBounce(particle, CollisionKind.SCREEN, 0.0F, -1.0F, impact);
            if (Math.abs(particle.vy) < 7.0F) {
                particle.vy = 0.0F;
            }
        }
    }

    private void updatePulses(float dt) {
        Iterator<Pulse> iterator = pulses.iterator();
        while (iterator.hasNext()) {
            Pulse pulse = iterator.next();
            pulse.age += dt;
            if (pulse.age >= pulse.delay + pulse.duration) {
                iterator.remove();
            }
        }
    }

    /**
     * Renders pulse accents as a Minecraft-like ring of individual motes.
     * The previous rectangular target aura/pulse borders were the visible
     * square users saw every time an effect fired.
     */
    private void renderPulses(DrawContext context, Layer layer) {
        for (Pulse pulse : pulses) {
            if (pulse.layer != layer || pulse.age < pulse.delay) continue;
            float t = clamp((pulse.age - pulse.delay) / pulse.duration, 0.0F, 1.0F);
            float radius = pulse.startRadius + t * pulse.travel * pulse.scale;
            int alpha = Math.round((1.0F - t) * pulse.alpha);
            int points = Math.max(12, Math.min(40, Math.round(radius * 0.85F)));
            for (int i = 0; i < points; i++) {
                float angle = (float) (Math.PI * 2.0 * i / points + pulse.seed);
                float jitter = (float) Math.sin(i * 2.31F + pulse.seed * 7.0F) * 1.35F;
                int px = Math.round(centerX + (float) Math.cos(angle) * (radius + jitter));
                int py = Math.round(centerY + (float) Math.sin(angle) * (radius + jitter));
                int moteAlpha = Math.max(0, Math.min(255, alpha - (i % 3) * 12));
                context.fill(px, py, px + 1 + (i % 7 == 0 ? 1 : 0), py + 1, withAlpha(pulse.color, moteAlpha));
            }
        }
    }

    private void renderParticles(DrawContext context, Layer layer) {
        for (Particle particle : particles) {
            if (particle.layer != layer) {
                continue;
            }
            boolean eruptionVisible = sourceEruptionVisible(particle);
            if (layer == Layer.FRONT && particle.protectTarget && insideTargetProtection(particle)
                    && !eruptionVisible) {
                continue;
            }

            float normalizedAge = clamp(particle.age / particle.lifetime, 0.0F, 1.0F);
            float fadeIn = clamp(normalizedAge / Math.max(0.001F, particle.fadeIn), 0.0F, 1.0F);
            float fadeOut = clamp((1.0F - normalizedAge) / Math.max(0.001F, particle.fadeOut), 0.0F, 1.0F);
            float layerAlpha = layer == Layer.FRONT ? particle.frontAlpha : 1.0F;

            // During the eruption phase, let sparse foreground particles cross
            // the logo at near-full opacity. The button remains readable because
            // most particles are still on the back layer, but the effect regains
            // the explosive depth that was lost when target protection became
            // too aggressive.
            if (layer == Layer.FRONT && eruptionVisible) {
                layerAlpha = Math.max(layerAlpha, SOURCE_FRONT_ALPHA_FLOOR);
            }
            int alpha = Math.round(255.0F * Math.min(fadeIn, fadeOut) * particle.alpha * layerAlpha);
            if (alpha <= 0) {
                continue;
            }

            int color = withAlpha(particle.color, alpha);
            float x = snapCoordinate(particle.x, particle.pixelSnap, particle.vx);
            float y = snapCoordinate(particle.y, particle.pixelSnap, particle.vy);
            int size = Math.max(1, Math.round(particle.size));

            context.getMatrices().push();
            context.getMatrices().translate(x, y, 0.0F);
            float renderRotation = resolveRenderRotation(particle);
            if (renderRotation != 0.0F) {
                context.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotationDegrees(renderRotation));
            }
            renderParticleVisual(context, particle, size, color, alpha, normalizedAge);
            context.getMatrices().pop();
        }
    }


    private void renderParticleVisual(
            DrawContext context,
            Particle particle,
            int size,
            int color,
            int alpha,
            float normalizedAge
    ) {
        TextureSelection selection = resolveTextureSelection(particle, normalizedAge);
        if (selection != null) {
            if (textureExists(selection.texture())) {
                renderTextureParticle(context, selection, particle, alpha);
            } else {
                // Never expose Minecraft's missing-texture square or Koil's old
                // generic rectangular fallback for a texture-backed particle.
                // A tiny cross-shaped mote keeps the effect visible without the
                // recurring square artifact reported by /sprite users.
                int radius = Math.max(1, Math.round(resolveVisualPixels(particle) * 0.22F));
                context.fill(-radius, 0, radius + 1, 1, color);
                context.fill(0, -radius, 1, radius + 1, color);
            }
            return;
        }

        float sx = particle.dynamicStretchX * (particle.behavior == Behavior.WOBBLE_SLIME ? particle.visualSquash : 1.0F);
        float sy = particle.dynamicStretchY / (particle.behavior == Behavior.WOBBLE_SLIME ? particle.visualSquash : 1.0F);
        context.getMatrices().push();
        context.getMatrices().scale(sx, sy, 1.0F);
        renderShape(context, particle.shape, size, color, alpha, particle.variant);
        context.getMatrices().pop();
    }

    private TextureSelection resolveTextureSelection(Particle particle, float normalizedAge) {
        Identifier texture = particle.customTexture;
        boolean tintable = particle.textureTint;

        if (particle.textureSequence != null && !particle.textureSequence.isEmpty()) {
            int count = particle.textureSequence.size();
            int index;
            if (particle.animationLoop) {
                index = Math.floorMod((int) Math.floor(particle.age * 20.0F * particle.animationSpeed) + particle.variant, count);
            } else {
                index = Math.min(count - 1, Math.max(0, (int) Math.floor(normalizedAge * count)));
            }
            texture = particle.textureSequence.get(index);
        }

        if (texture == null) {
            KoilVanillaParticleSprites.SpriteSet set = particle.spriteSet != null
                    ? particle.spriteSet
                    : (particle.visualFamily != null && particle.visualFamily != VisualFamily.AUTO
                    ? KoilVanillaParticleSprites.forVisualFamily(particle.visualFamily)
                    : KoilVanillaParticleSprites.forShape(particle.shape));
            if (set != null) {
                texture = set.texture(normalizedAge, particle.variant, particle.age * particle.animationSpeed);
                tintable = set.tintable() && particle.textureTint;
            }
        }
        if (texture == null) return null;

        KoilUiParticleTextureMetadata.TextureInfo info = KoilUiParticleTextureMetadata.resolve(texture);
        KoilUiParticleTextureMetadata.FrameView frame = info.frameAt(
                particle.age, particle.animationSpeed, particle.animationLoop, particle.variant);
        return new TextureSelection(texture, tintable, info, frame);
    }

    private void renderTextureParticle(
            DrawContext context,
            TextureSelection selection,
            Particle particle,
            int alpha
    ) {
        ParticleBounds visual = particleVisualBounds(particle, selection, false);
        int drawWidth = Math.max(1, Math.round(visual.visualWidth()));
        int drawHeight = Math.max(1, Math.round(visual.visualHeight()));
        int left = -drawWidth / 2;
        int top = -drawHeight / 2;

        float red = 1.0F;
        float green = 1.0F;
        float blue = 1.0F;
        if (selection.tintable()) {
            red = ((particle.color >> 16) & 0xFF) / 255.0F;
            green = ((particle.color >> 8) & 0xFF) / 255.0F;
            blue = (particle.color & 0xFF) / 255.0F;
        }

        KoilUiParticleTextureMetadata.FrameView frame = selection.frame();
        RenderSystem.enableBlend();
        if (frame.interpolate() && particle.animationInterpolate && frame.interpolation() > 0.001F) {
            float currentAlpha = alpha / 255.0F * (1.0F - frame.interpolation());
            RenderSystem.setShaderColor(red, green, blue, currentAlpha);
            context.drawTexture(selection.texture(), left, top, drawWidth, drawHeight,
                    frame.sourceX(), frame.sourceY(), frame.sourceWidth(), frame.sourceHeight(),
                    frame.textureWidth(), frame.textureHeight());

            float nextAlpha = alpha / 255.0F * frame.interpolation();
            RenderSystem.setShaderColor(red, green, blue, nextAlpha);
            context.drawTexture(selection.texture(), left, top, drawWidth, drawHeight,
                    frame.nextSourceX(), frame.nextSourceY(), frame.sourceWidth(), frame.sourceHeight(),
                    frame.textureWidth(), frame.textureHeight());
        } else {
            RenderSystem.setShaderColor(red, green, blue, alpha / 255.0F);
            context.drawTexture(selection.texture(), left, top, drawWidth, drawHeight,
                    frame.sourceX(), frame.sourceY(), frame.sourceWidth(), frame.sourceHeight(),
                    frame.textureWidth(), frame.textureHeight());
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private record TextureSelection(
            Identifier texture,
            boolean tintable,
            KoilUiParticleTextureMetadata.TextureInfo info,
            KoilUiParticleTextureMetadata.FrameView frame
    ) { }

    private record ParticleBounds(float halfWidth, float halfHeight, float visualWidth, float visualHeight) { }

    /**
     * Computes the exact rendered footprint and matching physics half-extents.
     * Texture aspect ratio, animation frame size, velocity stretching, slime
     * squash, rotation and collisionScale all participate in the same result.
     */
    private ParticleBounds particleVisualBounds(Particle particle, TextureSelection selection, boolean includeRotation) {
        float basePixels = Math.max(1.0F, resolveVisualPixels(particle) * particle.visualGrowth);
        float sourceWidth = 1.0F;
        float sourceHeight = 1.0F;
        if (selection != null) {
            if (!particle.textureAutoSize && particle.textureWidth > 0 && particle.textureHeight > 0) {
                sourceWidth = particle.textureWidth;
                sourceHeight = particle.textureHeight;
            } else {
                sourceWidth = Math.max(1, selection.frame().sourceWidth());
                sourceHeight = Math.max(1, selection.frame().sourceHeight());
            }
        }
        float aspect = sourceWidth / Math.max(1.0F, sourceHeight);
        float width;
        float height;
        if (aspect >= 1.0F) {
            width = basePixels;
            height = basePixels / aspect;
        } else {
            height = basePixels;
            width = basePixels * aspect;
        }

        float sx = particle.dynamicStretchX * (particle.behavior == Behavior.WOBBLE_SLIME ? particle.visualSquash : 1.0F);
        float sy = particle.dynamicStretchY / (particle.behavior == Behavior.WOBBLE_SLIME ? particle.visualSquash : 1.0F);
        width = Math.max(1.0F, width * sx);
        height = Math.max(1.0F, height * sy);

        float halfW = width * 0.5F * particle.collisionScale;
        float halfH = height * 0.5F * particle.collisionScale;
        if (includeRotation) {
            float radians = (float) Math.toRadians(resolveRenderRotation(particle));
            float c = Math.abs((float) Math.cos(radians));
            float si = Math.abs((float) Math.sin(radians));
            float rotatedW = halfW * c + halfH * si;
            float rotatedH = halfW * si + halfH * c;
            halfW = rotatedW;
            halfH = rotatedH;
        }
        return new ParticleBounds(Math.max(0.5F, halfW), Math.max(0.5F, halfH), width, height);
    }

    private ParticleBounds particleVisualBounds(Particle particle, boolean includeRotation) {
        float normalizedAge = clamp(particle.age / Math.max(0.001F, particle.lifetime), 0.0F, 1.0F);
        return particleVisualBounds(particle, resolveTextureSelection(particle, normalizedAge), includeRotation);
    }

    private void renderShape(DrawContext context, Shape shape, int size, int color, int alpha, int variant) {
        int white = withAlpha(0xFFFFFF, Math.min(alpha, 210));
        int dark = withAlpha(darken(color & 0x00FFFFFF, 0.52F), Math.min(alpha, 190));
        switch (shape) {
            case PIXEL, DUST -> {
                context.fill(-size, -size, size + 1, size + 1, color);
                if (size >= 2) {
                    context.fill(-size, -size, size, -size + 1, white);
                }
            }
            case BLOCK_SHARD -> {
                context.fill(-size * 2, -size, size * 2 + 1, size + 1, color);
                context.fill(-size * 2, size, size * 2 + 1, size + 1, dark);
                context.fill(-size * 2, -size, -size * 2 + 1, size, white);
            }
            case SPARK, REDSTONE -> {
                context.fill(-size * 2, 0, size * 2 + 1, 1, color);
                context.fill(0, -size * 2, 1, size * 2 + 1, color);
                context.fill(0, 0, 1, 1, white);
            }
            case CRIT -> {
                context.fill(-size * 2, 0, size * 2 + 1, 1, color);
                context.fill(0, -size * 2, 1, size * 2 + 1, color);
                context.fill(-size, -size, size + 1, size + 1, withAlpha(color & 0x00FFFFFF, alpha / 2));
            }
            case GLYPH, ENCHANT -> {
                int w = Math.max(2, size * 2 + 1);
                context.drawBorder(-w, -w, w * 2 + 1, w * 2 + 1, color);
                if ((variant & 1) == 0) {
                    context.fill(-1, -w + 1, 1, w, white);
                    context.fill(-w + 1, 0, w, 1, color);
                } else {
                    context.fill(-w + 1, -1, w, 1, white);
                    context.fill(0, -w + 1, 1, w, color);
                }
            }
            case BUBBLE -> {
                int diameter = Math.max(3, size * 2 + 3);
                int half = diameter / 2;
                context.drawBorder(-half, -half, diameter, diameter, color);
                context.fill(-half + 1, -half + 1, -half + 2, -half + 2, white);
            }
            case DROP -> {
                context.fill(0, -size * 3, 1, size + 1, color);
                context.fill(-1, size, 2, size + 2, color);
                context.fill(0, size, 1, size + 1, white);
            }
            case SMOKE, CLOUD -> {
                context.fill(-size * 2, -size, size * 2 + 1, size + 1, color);
                context.fill(-size, -size * 2, size + 1, size * 2 + 1, color);
                context.fill(-size * 2, 0, -size, size + 1, dark);
                context.fill(0, -size * 2, size + 1, -size, white);
            }
            case FLAME, SOUL_FLAME -> {
                context.fill(-size, -size * 2, size + 1, size * 2 + 1, color);
                context.fill(-size * 2, 0, size * 2 + 1, size + 1, color);
                context.fill(0, -size, 1, size + 1, white);
                context.fill(-1, size, 1, size * 2 + 1, dark);
            }
            case NOTE -> {
                context.fill(-size, -size, size + 1, size + 1, color);
                context.fill(size, -size * 3, size + 1, size, color);
                context.fill(size, -size * 3, size * 3 + 1, -size * 2 + 1, color);
            }
            case HEART -> {
                int s = Math.max(1, size);
                context.fill(-s * 2, -s, 0, s + 1, color);
                context.fill(1, -s, s * 2 + 1, s + 1, color);
                context.fill(-s, -s * 2, 0, 0, color);
                context.fill(1, -s * 2, s + 1, 0, color);
                context.fill(-s, s, s + 1, s * 2 + 1, color);
                context.fill(0, s * 2, 1, s * 3 + 1, dark);
            }
            case PETAL, LEAF -> {
                context.fill(-size * 2, -size, size + 1, size + 1, color);
                context.fill(-size, size, size * 2 + 1, size + 2, color);
                context.fill(-size, 0, size + 1, 1, dark);
            }
            case SLIME -> {
                context.fill(-size * 2, -size * 2, size * 2 + 1, size * 2 + 1, color);
                context.drawBorder(-size * 2, -size * 2, size * 4 + 1, size * 4 + 1, dark);
                context.fill(-size, -size, 0, 0, white);
            }
            case END_ROD, STREAK -> {
                context.fill(-size * 4, 0, size * 2 + 1, 1, color);
                context.fill(-size * 2, -1, size, 2, white);
                context.fill(size * 2, 0, size * 3 + 1, 1, withAlpha(color & 0x00FFFFFF, alpha / 2));
            }
            case RIBBON -> {
                context.fill(-size * 2, -1, size * 2 + 1, 2, color);
                context.fill(-1, -size, 1, size + 1, white);
            }
            case STAR -> {
                context.fill(-size * 2, 0, size * 2 + 1, 1, color);
                context.fill(0, -size * 2, 1, size * 2 + 1, color);
                context.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotationDegrees(45.0F));
                context.fill(-size, 0, size + 1, 1, white);
                context.fill(0, -size, 1, size + 1, white);
            }
            case SNOW -> {
                context.fill(-size * 2, 0, size * 2 + 1, 1, color);
                context.fill(0, -size * 2, 1, size * 2 + 1, color);
                context.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotationDegrees(45.0F));
                context.fill(-size * 2, 0, size * 2 + 1, 1, withAlpha(color & 0x00FFFFFF, alpha / 2));
                context.fill(0, -size * 2, 1, size * 2 + 1, withAlpha(color & 0x00FFFFFF, alpha / 2));
            }
            case EXPERIENCE -> {
                context.drawBorder(-size * 2, -size * 2, size * 4 + 1, size * 4 + 1, color);
                context.fill(-size, -size, size + 1, size + 1, white);
            }
            case SCULK -> {
                context.fill(-size * 2, 0, size * 2 + 1, 1, color);
                context.fill(0, -size * 2, 1, size * 2 + 1, color);
                context.fill(-size, -size, size + 1, size + 1, dark);
                context.fill(0, 0, 1, 1, white);
            }
            case TOTEM -> {
                context.fill(-size, -size * 2, size + 1, size * 2 + 1, color);
                context.fill(-size * 2, -size, size * 2 + 1, size, color);
                context.fill(0, -size, 1, size + 1, white);
            }
        }
    }

    private boolean insideTargetProtection(Particle particle) {
        float pad = 3.0F + particle.size * 2.0F;
        return particle.x > targetX - pad
                && particle.x < targetX + targetWidth + pad
                && particle.y > targetY - pad
                && particle.y < targetY + targetHeight + pad;
    }

    private void addParticle(Particle particle) {
        if (particles.size() >= maxParticles) {
            particles.remove(0);
        }
        particles.add(particle);
    }

    private void trimToLimit() {
        while (particles.size() > maxParticles) {
            particles.remove(0);
        }
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
    }

    private static int darken(int rgb, float amount) {
        int r = Math.round(((rgb >> 16) & 0xFF) * amount);
        int g = Math.round(((rgb >> 8) & 0xFF) * amount);
        int b = Math.round((rgb & 0xFF) * amount);
        return (r << 16) | (g << 8) | b;
    }


    private void updateForceFields(float dt) {
        Iterator<ForceField> iterator = forceFields.iterator();
        while (iterator.hasNext()) {
            ForceField field = iterator.next();
            field.age += dt;
            if (field.lifetime > 0.0F && field.age >= field.lifetime) iterator.remove();
        }
    }

    private void applyForceFields(Particle particle, float dt) {
        for (ForceField field : forceFields) {
            if (field.type == ForceFieldType.WIND) {
                if (particle.x >= field.x && particle.x <= field.x + field.width
                        && particle.y >= field.y && particle.y <= field.y + field.height) {
                    particle.vx += field.forceX * dt;
                    particle.vy += field.forceY * dt;
                }
            } else {
                float dx = field.x - particle.x;
                float dy = field.y - particle.y;
                float distanceSq = dx * dx + dy * dy;
                if (distanceSq <= field.radius * field.radius && distanceSq > 0.01F) {
                    float distance = (float) Math.sqrt(distanceSq);
                    float nx = dx / distance;
                    float ny = dy / distance;
                    float falloff = 1.0F - clamp(distance / field.radius, 0.0F, 1.0F);
                    particle.vx += (nx * field.pull - ny * field.spin) * falloff * dt;
                    particle.vy += (ny * field.pull + nx * field.spin) * falloff * dt;
                }
            }
        }
    }

    private void updateParticleInteractions() {
        refreshSpawnParticleImmunities();
        int count = particles.size();
        for (int i = 0; i < count; i++) {
            Particle a = particles.get(i);
            if (!canEnterParticleContact(a)) continue;
            for (int j = i + 1; j < count; j++) {
                Particle b = particles.get(j);
                if (!canEnterParticleContact(b) || !layersCanInteract(a, b)) continue;

                ParticleBounds boundsA = particleVisualBounds(a, true);
                ParticleBounds boundsB = particleVisualBounds(b, true);
                float dx = b.x - a.x;
                float dy = b.y - a.y;
                float overlapX = boundsA.halfWidth() + boundsB.halfWidth() - Math.abs(dx);
                float overlapY = boundsA.halfHeight() + boundsB.halfHeight() - Math.abs(dy);
                if (overlapX <= 0.0F || overlapY <= 0.0F) continue;
                if (hasActiveParticleSpawnImmunity(a, b)) continue;

                if (shouldMerge(a, b)) {
                    mergeParticles(a, b);
                    continue;
                }

                ParticleContactMode contact = resolveContactMode(a, b);
                if (contact == ParticleContactMode.IGNORE) continue;
                if (contact == ParticleContactMode.KILL_BOTH) {
                    a.removeRequested = true;
                    b.removeRequested = true;
                    continue;
                }
                if (contact == ParticleContactMode.KILL_SELF) {
                    a.removeRequested = true;
                    continue;
                }
                if (contact == ParticleContactMode.KILL_OTHER) {
                    b.removeRequested = true;
                    continue;
                }

                // Resolve on the least-overlap axis, then use a mass-aware
                // impulse. This allows slime/block particles to bounce against
                // each other while generic sprite contacts can still be kill-on-contact.
                float nx = 0.0F;
                float ny = 0.0F;
                float penetration;
                if (overlapX < overlapY) {
                    nx = dx >= 0.0F ? 1.0F : -1.0F;
                    penetration = overlapX;
                } else {
                    ny = dy >= 0.0F ? 1.0F : -1.0F;
                    penetration = overlapY;
                }

                float invMassA = 1.0F / Math.max(0.001F, a.mass);
                float invMassB = 1.0F / Math.max(0.001F, b.mass);
                float invMassTotal = invMassA + invMassB;
                float correction = Math.max(0.0F, penetration + 0.05F);
                a.x -= nx * correction * (invMassA / invMassTotal);
                a.y -= ny * correction * (invMassA / invMassTotal);
                b.x += nx * correction * (invMassB / invMassTotal);
                b.y += ny * correction * (invMassB / invMassTotal);

                float relativeNormal = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
                if (relativeNormal < 0.0F) {
                    float restitution = clamp((a.restitution + b.restitution) * 0.5F, 0.0F, 1.15F);
                    float impulse = -(1.0F + restitution) * relativeNormal / invMassTotal;
                    a.vx -= impulse * invMassA * nx;
                    a.vy -= impulse * invMassA * ny;
                    b.vx += impulse * invMassB * nx;
                    b.vy += impulse * invMassB * ny;
                    float tangentFriction = clamp((a.surfaceFriction + b.surfaceFriction) * 0.5F, 0.0F, 1.0F);
                    if (nx != 0.0F) {
                        a.vy *= tangentFriction;
                        b.vy *= tangentFriction;
                    } else {
                        a.vx *= tangentFriction;
                        b.vx *= tangentFriction;
                    }
                    float impact = Math.abs(relativeNormal);
                    notifyBounce(a, CollisionKind.PARTICLE, -nx, -ny, impact);
                    notifyBounce(b, CollisionKind.PARTICLE, nx, ny, impact);
                }
            }
        }
        particles.removeIf(p -> p.removeRequested);
    }

    private boolean canEnterParticleContact(Particle particle) {
        return particle != null
                && !particle.removeRequested
                && particle.particleCollision;
    }

    private boolean layersCanInteract(Particle a, Particle b) {
        if (a.simulationLayerId == b.simulationLayerId) return true;
        if (!a.crossLayerInteractions || !b.crossLayerInteractions) return false;
        return a.interactionLayers.allows(a.simulationLayerId, b.simulationLayerId)
                && b.interactionLayers.allows(b.simulationLayerId, a.simulationLayerId);
    }

    private boolean shouldMerge(Particle a, Particle b) {
        if (!(a.mergeOnContact && b.mergeOnContact)) return false;
        if (a.mergeGroup == null || !a.mergeGroup.equals(b.mergeGroup)) return false;
        return a.contactMode == ParticleContactMode.MERGE || b.contactMode == ParticleContactMode.MERGE
                || a.contactMode == ParticleContactMode.AUTO || b.contactMode == ParticleContactMode.AUTO;
    }

    private void mergeParticles(Particle a, Particle b) {
        float weightA = Math.max(0.001F, a.mass);
        float weightB = Math.max(0.001F, b.mass);
        float total = weightA + weightB;
        a.vx = (a.vx * weightA + b.vx * weightB) / total;
        a.vy = (a.vy * weightA + b.vy * weightB) / total;
        a.mass = total;
        float areaA = Math.max(1.0F, resolveVisualPixels(a) * resolveVisualPixels(a));
        float areaB = Math.max(1.0F, resolveVisualPixels(b) * resolveVisualPixels(b));
        a.visualPixels = Math.min(32.0F, (float) Math.sqrt(areaA + areaB));
        a.lifetime = Math.max(a.lifetime, b.lifetime);
        a.temperature = (a.temperature * weightA + b.temperature * weightB) / total;
        a.charge += b.charge;
        a.interactionTags.addAll(b.interactionTags);
        b.removeRequested = true;
    }

    private ParticleContactMode resolveContactMode(Particle a, Particle b) {
        if (a.contactMode == ParticleContactMode.IGNORE || b.contactMode == ParticleContactMode.IGNORE) return ParticleContactMode.IGNORE;
        if (a.contactMode == ParticleContactMode.KILL_BOTH || b.contactMode == ParticleContactMode.KILL_BOTH) return ParticleContactMode.KILL_BOTH;
        if (a.contactMode == ParticleContactMode.KILL_SELF) return ParticleContactMode.KILL_SELF;
        if (b.contactMode == ParticleContactMode.KILL_SELF) return ParticleContactMode.KILL_OTHER;
        if (a.contactMode == ParticleContactMode.KILL_OTHER) return ParticleContactMode.KILL_OTHER;
        if (b.contactMode == ParticleContactMode.KILL_OTHER) return ParticleContactMode.KILL_SELF;
        if (a.contactMode == ParticleContactMode.MERGE || b.contactMode == ParticleContactMode.MERGE) {
            return shouldMerge(a, b) ? ParticleContactMode.MERGE : ParticleContactMode.KILL_BOTH;
        }
        if (a.contactMode == ParticleContactMode.BOUNCE || b.contactMode == ParticleContactMode.BOUNCE) return ParticleContactMode.BOUNCE;

        // AUTO uses solidity as the broad-phase "is this a physical body?"
        // property. Slime stays soft but solid, while smoke/spells can pass or
        // use an explicit kill/ignore mode.
        float pairSolidity = Math.min(a.solidity, b.solidity);
        return pairSolidity >= 0.25F ? ParticleContactMode.BOUNCE : ParticleContactMode.KILL_BOTH;
    }

    private void applyMaterialCollisionResponse(Particle particle, CollisionKind kind, float nx, float ny, float impactSpeed) {
        if (particle.stickToButtonsSeconds > 0.0F && (kind == CollisionKind.BUTTON || kind == CollisionKind.TARGET)) {
            particle.stuckUntil = Math.max(particle.stuckUntil, particle.age + particle.stickToButtonsSeconds);
            particle.vx = 0.0F;
            particle.vy = 0.0F;
        }
        if (particle.settleOnSurfaces && ny < -0.5F && impactSpeed < 34.0F) {
            particle.sleeping = true;
            particle.vx = 0.0F;
            particle.vy = 0.0F;
            particle.angularVelocity = 0.0F;
        }
        if (particle.material == KoilUiParticleMaterials.Material.WATER && impactSpeed > 28.0F
                && particle.bounceCount <= 2 && particles.size() + pendingParticles.size() < maxParticles - 4) {
            for (int i = 0; i < 3; i++) {
                effectContext.spawn(effectContext.particle(Shape.BUBBLE, particle.x, particle.y)
                        .sprite(KoilVanillaParticleSprites.SpriteSet.SPLASH)
                        .color(particle.color)
                        .sizeBand(SizeBand.SMALL)
                        .lifetime(0.35F + random.nextFloat() * 0.35F)
                        .velocity(random.nextFloat() * 40.0F - 20.0F, -18.0F - random.nextFloat() * 35.0F)
                        .gravity(85.0F).drag(0.96F).collideButtons(false).collideScreen(false)
                        .protectTarget(false));
            }
        }
    }

    private float resolveVisualPixels(Particle particle) {
        if (particle.visualPixels > 0.0F) return particle.visualPixels * particle.spriteScale;
        if (particle.sizeBand != null) {
            float fraction = particle.seed - (float) Math.floor(particle.seed);
            return (particle.sizeBand.minPixels() + (particle.sizeBand.maxPixels() - particle.sizeBand.minPixels()) * fraction)
                    * particle.spriteScale;
        }
        KoilVanillaParticleSprites.SpriteSet set = particle.spriteSet != null
                ? particle.spriteSet
                : (particle.visualFamily != null && particle.visualFamily != VisualFamily.AUTO
                ? KoilVanillaParticleSprites.forVisualFamily(particle.visualFamily)
                : KoilVanillaParticleSprites.forShape(particle.shape));
        float base = set == null ? 6.0F : set.recommendedPixels();
        float authored = clamp(particle.size, 0.55F, 2.5F);
        return clamp(base * (0.72F + authored * 0.34F) * particle.spriteScale, 2.0F, 20.0F);
    }

    private RotationPolicy resolveRotationPolicy(Particle particle) {
        if (particle.rotationPolicy != null) return particle.rotationPolicy;
        KoilVanillaParticleSprites.SpriteSet set = particle.spriteSet != null
                ? particle.spriteSet
                : (particle.visualFamily != null && particle.visualFamily != VisualFamily.AUTO
                ? KoilVanillaParticleSprites.forVisualFamily(particle.visualFamily)
                : KoilVanillaParticleSprites.forShape(particle.shape));
        return set == null ? RotationPolicy.FREE : set.rotationPolicy();
    }

    private float resolveRenderRotation(Particle particle) {
        return switch (resolveRotationPolicy(particle)) {
            case LOCKED -> 0.0F;
            case QUARTER_TURN -> Math.round(particle.rotation / 90.0F) * 90.0F;
            case FACE_VELOCITY -> (Math.abs(particle.vx) + Math.abs(particle.vy) < 0.1F)
                    ? particle.rotation : (float) Math.toDegrees(Math.atan2(particle.vy, particle.vx));
            case FREE -> particle.rotation;
        };
    }

    private float snapCoordinate(float value, PixelSnap snap, float velocity) {
        PixelSnap mode = snap == null ? PixelSnap.SOFT : snap;
        return switch (mode) {
            case NONE -> value;
            case FULL -> Math.round(value);
            case SOFT -> Math.abs(velocity) < 18.0F ? Math.round(value) : Math.round(value * 2.0F) * 0.5F;
        };
    }

    private boolean textureExists(Identifier texture) {
        Boolean cached = textureAvailability.get(texture);
        if (cached != null) return cached;
        MinecraftClient client = MinecraftClient.getInstance();
        boolean exists = client != null && client.getResourceManager() != null
                && client.getResourceManager().getResource(texture).isPresent();
        textureAvailability.put(texture, exists);
        return exists;
    }

    private void renderDebug(DrawContext context) {
        for (UiCollider collider : buttonColliders) {
            int x = Math.round(collider.left);
            int y = Math.round(collider.top);
            int w = Math.max(1, Math.round(collider.right - collider.left));
            int h = Math.max(1, Math.round(collider.bottom - collider.top));
            context.drawBorder(x, y, w, h, withAlpha(0x55FF55, 145));
        }
        int shown = 0;
        for (Particle particle : particles) {
            if (shown++ > 80) break;
            int x = Math.round(particle.x);
            int y = Math.round(particle.y);
            int ex = Math.round(particle.x + particle.vx * 0.05F);
            int ey = Math.round(particle.y + particle.vy * 0.05F);
            drawDebugLine(context, x, y, ex, ey, withAlpha(0x55D8FF, 155));
        }
    }

    private void drawDebugLine(DrawContext context, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            context.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public enum Layer {
        BACK,
        FRONT
    }

    public enum Behavior {
        BALLISTIC,
        WANDER,
        ORBIT,
        VORTEX,
        ATTRACT,
        RISE_AND_WANDER,
        FALL_AND_SWAY,
        RISING_SMOKE,
        DRIFT,
        FLUTTER,
        RICOCHET,
        FUSE_BURN,
        ROCKET_ASCENT,
        BURST_SHELL,
        STICKY_HONEY,
        SPLASHING_LIQUID,
        WOBBLE_SLIME,
        SPIRAL
    }

    public enum VisualFamily {
        AUTO,
        CRIT,
        ENCHANT_GLYPH,
        SMOKE,
        FIREWORK_SPARK,
        HEART,
        NOTE,
        BUBBLE,
        CHERRY_PETAL,
        SCULK_CHARGE,
        SLIME_SHARD,
        BLOCK_SHARD,
        SOUL_FLAME,
        PORTAL_MOTE,
        TOTEM,
        END_ROD,
        LAVA,
        HONEY,
        NAUTILUS,
        SPLASH,
        REDSTONE_DUST,
        EXPERIENCE,
        FLAME,
        EXPLOSION,
        SPELL
    }

    public enum SizeBand {
        TINY(2.0F, 4.0F), SMALL(4.0F, 6.0F), NORMAL(6.0F, 9.0F), LARGE(9.0F, 13.0F), HERO(13.0F, 18.0F);
        private final float minPixels;
        private final float maxPixels;
        SizeBand(float minPixels, float maxPixels) { this.minPixels = minPixels; this.maxPixels = maxPixels; }
        public float minPixels() { return minPixels; }
        public float maxPixels() { return maxPixels; }
    }

    public enum RotationPolicy { FREE, LOCKED, QUARTER_TURN, FACE_VELOCITY }
    public enum PixelSnap { NONE, SOFT, FULL }
    public enum PerformanceMode { AUTO, HIGH, MEDIUM, LOW }

    /** Particle-particle contact policy. AUTO uses material solidity/mass/restitution. */
    public enum ParticleContactMode {
        AUTO, BOUNCE, KILL_SELF, KILL_OTHER, KILL_BOTH, MERGE, IGNORE
    }

    /**
     * Selects which simulation layers a particle may interact with when
     * cross-layer interaction is enabled. Same-layer interaction is always
     * allowed unless the particle itself disables particle collisions.
     */
    public static final class InteractionLayers {
        private enum Mode { SAME, ALL, RANGE, SET }
        private final Mode mode;
        private final int min;
        private final int max;
        private final Set<Integer> exact;

        private InteractionLayers(Mode mode, int min, int max, Set<Integer> exact) {
            this.mode = mode;
            this.min = min;
            this.max = max;
            this.exact = exact == null ? Set.of() : Set.copyOf(exact);
        }

        public static InteractionLayers sameLayer() { return new InteractionLayers(Mode.SAME, 0, 0, Set.of()); }
        public static InteractionLayers all() { return new InteractionLayers(Mode.ALL, Integer.MIN_VALUE, Integer.MAX_VALUE, Set.of()); }
        public static InteractionLayers range(int min, int max) { return new InteractionLayers(Mode.RANGE, Math.min(min, max), Math.max(min, max), Set.of()); }
        public static InteractionLayers exact(int... layers) {
            Set<Integer> set = new HashSet<>();
            if (layers != null) for (int layer : layers) set.add(layer);
            return new InteractionLayers(Mode.SET, 0, 0, set);
        }
        public boolean allows(int ownLayer, int otherLayer) {
            if (ownLayer == otherLayer) return true;
            return switch (mode) {
                case SAME -> false;
                case ALL -> true;
                case RANGE -> otherLayer >= min && otherLayer <= max;
                case SET -> exact.contains(otherLayer);
            };
        }
        public String describe() {
            return switch (mode) {
                case SAME -> "same";
                case ALL -> "all";
                case RANGE -> min + "-" + max;
                case SET -> exact.toString();
            };
        }
    }

    /**
     * Per-trigger overrides used by /sprite and other systems. Values are
     * temporary and never rewrite the JSON definition on disk.
     */
    public static final class SpawnOverrides {
        public static final SpawnOverrides EMPTY = new SpawnOverrides(Map.of(), 1.0F);
        private final Map<String, String> values;
        private final float defaultVisualScale;

        public SpawnOverrides(Map<String, String> values) { this(values, 1.0F); }
        private SpawnOverrides(Map<String, String> values, float defaultVisualScale) {
            Map<String, String> normalized = new LinkedHashMap<>();
            if (values != null) values.forEach((k, v) -> {
                if (k != null && v != null && !k.isBlank()) normalized.put(k.trim().toLowerCase(), v.trim());
            });
            this.values = Collections.unmodifiableMap(normalized);
            this.defaultVisualScale = Math.max(0.05F, defaultVisualScale);
        }
        public SpawnOverrides withDefaultVisualScale(float scale) { return new SpawnOverrides(values, scale); }
        public Map<String, String> values() { return values; }
        public String get(String key) { return values.get(key == null ? "" : key.toLowerCase()); }
        public boolean has(String key) { return get(key) != null; }
        public float floatValue(String key, float fallback) {
            try { String v = get(key); return v == null ? fallback : Float.parseFloat(v); } catch (Exception ignored) { return fallback; }
        }
        public int intValue(String key, int fallback) {
            try { String v = get(key); return v == null ? fallback : Integer.parseInt(v); } catch (Exception ignored) { return fallback; }
        }
        public boolean boolValue(String key, boolean fallback) {
            String v = get(key);
            if (v == null) return fallback;
            return switch (v.toLowerCase()) { case "1", "true", "yes", "on" -> true; case "0", "false", "no", "off" -> false; default -> fallback; };
        }
        private void apply(Particle p) {
            p.spriteScale *= floatValue("scale", floatValue("sprite_scale", defaultVisualScale));
            String shape = get("shape");
            if (shape != null) try { p.shape = Shape.valueOf(normalizeEnum(shape)); } catch (Exception ignored) { }
            String behavior = get("behavior");
            if (behavior != null) try { p.behavior = Behavior.valueOf(normalizeEnum(behavior)); } catch (Exception ignored) { }
            String visual = get("visual_family");
            if (visual != null) try { p.visualFamily = VisualFamily.valueOf(normalizeEnum(visual)); } catch (Exception ignored) { }
            String sprite = get("sprite");
            if (sprite != null) try { p.spriteSet = KoilVanillaParticleSprites.SpriteSet.valueOf(normalizeEnum(sprite)); } catch (Exception ignored) { }
            String material = get("material");
            if (material != null) {
                try {
                    p.material = KoilUiParticleMaterials.Material.valueOf(normalizeEnum(material));
                    applyRuntimeMaterial(p, p.material);
                } catch (Exception ignored) { }
            }
            String sizeBand = get("size_band");
            if (sizeBand != null) try { p.sizeBand = SizeBand.valueOf(normalizeEnum(sizeBand)); } catch (Exception ignored) { }
            String rotationPolicy = get("rotation_policy");
            if (rotationPolicy != null) try { p.rotationPolicy = RotationPolicy.valueOf(normalizeEnum(rotationPolicy)); } catch (Exception ignored) { }
            String pixelSnap = get("pixel_snap");
            if (pixelSnap != null) try { p.pixelSnap = PixelSnap.valueOf(normalizeEnum(pixelSnap)); } catch (Exception ignored) { }
            p.visualPixels = floatValue("visual_pixels", p.visualPixels);
            p.size = Math.max(0.25F, floatValue("size", p.size));
            p.lifetime = Math.max(0.05F, floatValue("lifetime", p.lifetime));
            p.fadeIn = Math.max(0.0F, floatValue("fade_in", p.fadeIn));
            p.fadeOut = Math.max(0.0F, floatValue("fade_out", p.fadeOut));
            p.rotation = floatValue("rotation", p.rotation);
            p.angularVelocity = floatValue("angular_velocity", p.angularVelocity);
            p.angularDrag = clamp(floatValue("angular_drag", p.angularDrag), 0.0F, 1.0F);
            p.vx = floatValue("vx", p.vx);
            p.vy = floatValue("vy", p.vy);
            p.gravity = floatValue("gravity", p.gravity);
            p.drag = clamp(floatValue("drag", p.drag), 0.0F, 1.0F);
            p.restitution = clamp(floatValue("restitution", p.restitution), 0.0F, 1.25F);
            p.surfaceFriction = clamp(floatValue("surface_friction", p.surfaceFriction), 0.0F, 1.0F);
            p.collisionScale = clamp(floatValue("collision_scale", p.collisionScale), 0.05F, 4.0F);
            p.alpha = clamp(floatValue("alpha", p.alpha), 0.0F, 1.0F);
            p.frontAlpha = clamp(floatValue("front_alpha", p.frontAlpha), 0.0F, 1.0F);
            p.collideButtons = boolValue("collide_buttons", p.collideButtons);
            p.collideScreen = boolValue("collide_screen", p.collideScreen);
            p.collideTarget = boolValue("collide_target", p.collideTarget);
            p.particleCollision = boolValue("particle_collision", p.particleCollision);
            p.bounceCeiling = boolValue("bounce_ceiling", p.bounceCeiling);
            p.protectTarget = boolValue("protect_target", p.protectTarget);
            p.stretchWithVelocity = boolValue("stretch_with_velocity", p.stretchWithVelocity);
            p.settleOnSurfaces = boolValue("settle_on_surfaces", p.settleOnSurfaces);
            p.animationSpeed = Math.max(0.01F, floatValue("animation_speed", p.animationSpeed));
            p.animationLoop = boolValue("animation_loop", p.animationLoop);
            p.animationInterpolate = boolValue("animation_interpolate", p.animationInterpolate);
            p.textureTint = boolValue("texture_tint", p.textureTint);
            p.textureAutoSize = boolValue("texture_auto_size", p.textureAutoSize);
            if (has("texture_width")) { p.textureWidth = Math.max(1, intValue("texture_width", p.textureWidth)); p.textureAutoSize = false; }
            if (has("texture_height")) { p.textureHeight = Math.max(1, intValue("texture_height", p.textureHeight)); p.textureAutoSize = false; }
            p.stickToButtonsSeconds = Math.max(0.0F, floatValue("stick_seconds", p.stickToButtonsSeconds));
            String merge = get("merge_group");
            if (merge != null) p.mergeGroup = merge.trim();
            p.mergeOnContact = boolValue("merge_on_contact", p.mergeOnContact);
            p.spawnCollisionInvulnerability = Math.max(SPAWN_COLLISION_INVULNERABILITY_SECONDS,
                    floatValue("spawn_invulnerability", p.spawnCollisionInvulnerability));
            p.sourceEruptionGrace = Math.max(p.sourceEruptionGrace, p.spawnCollisionInvulnerability);
            p.simulationLayerId = intValue("layer_id", intValue("simulation_layer", p.simulationLayerId));
            p.crossLayerInteractions = boolValue("cross_layer_interactions", p.crossLayerInteractions);
            p.mass = Math.max(0.001F, floatValue("mass", p.mass));
            p.solidity = clamp(floatValue("solidity", p.solidity), 0.0F, 1.0F);
            p.temperature = floatValue("temperature", p.temperature);
            p.charge = floatValue("charge", p.charge);
            p.conductivity = clamp(floatValue("conductivity", p.conductivity), 0.0F, 1.0F);
            p.flammability = clamp(floatValue("flammability", p.flammability), 0.0F, 1.0F);
            p.buoyancy = floatValue("buoyancy", p.buoyancy);
            p.anchorX = floatValue("anchor_x", p.anchorX);
            p.anchorY = floatValue("anchor_y", p.anchorY);
            p.orbitRadius = floatValue("orbit_radius", p.orbitRadius);
            p.force = floatValue("force", p.force);
            p.spring = floatValue("spring", p.spring);
            p.lift = floatValue("lift", p.lift);
            p.frequency = floatValue("frequency", p.frequency);
            String contact = get("contact_mode");
            if (contact != null) try { p.contactMode = ParticleContactMode.valueOf(contact.trim().toUpperCase()); } catch (Exception ignored) { }
            String layers = get("interaction_layers");
            if (layers != null) p.interactionLayers = parseInteractionLayers(layers);
            String layer = get("render_layer");
            if (layer != null) try { p.layer = Layer.valueOf(layer.trim().toUpperCase()); } catch (Exception ignored) { }
            String texture = get("texture");
            if (texture != null && !texture.isBlank()) {
                try { p.customTexture = texture.contains(":") ? new Identifier(texture) : new Identifier("minecraft", texture); } catch (Exception ignored) { }
            }
            String textureFrames = get("texture_frames");
            if (textureFrames != null && !textureFrames.isBlank()) {
                List<Identifier> frames = new ArrayList<>();
                for (String raw : textureFrames.split(",")) {
                    try {
                        String value = raw.trim();
                        if (!value.isBlank()) frames.add(value.contains(":") ? new Identifier(value) : new Identifier("minecraft", value));
                    } catch (Exception ignored) { }
                }
                if (!frames.isEmpty()) p.textureSequence = List.copyOf(frames);
            }
            KoilUiParticleSoundProfile runtimeSound = runtimeSoundProfile();
            if (runtimeSound != null) p.soundProfile = runtimeSound;
            String tint = get("color");
            if (tint != null) p.color = parseColor(tint, p.color);
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getKey().startsWith("data.")) p.data.put(entry.getKey().substring(5), entry.getValue());
                if (entry.getKey().startsWith("tag.") && boolValue(entry.getKey(), true)) p.interactionTags.add(entry.getKey().substring(4));
            }
        }
        private KoilUiParticleSoundProfile runtimeSoundProfile() {
            KoilUiParticleSoundProfile.Cue spawn = cue("spawn_sound", "spawn_volume", "spawn_pitch", "spawn_jitter", "spawn_cooldown_ms");
            KoilUiParticleSoundProfile.Cue bounce = cue("bounce_sound", "bounce_volume", "bounce_pitch", "bounce_jitter", "bounce_cooldown_ms");
            KoilUiParticleSoundProfile.Cue breakCue = cue("break_sound", "break_volume", "break_pitch", "break_jitter", "break_cooldown_ms");
            KoilUiParticleSoundProfile.Cue expire = cue("expire_sound", "expire_volume", "expire_pitch", "expire_jitter", "expire_cooldown_ms");
            if (spawn == null && bounce == null && breakCue == null && expire == null) return null;
            return KoilUiParticleSoundProfile.builder()
                    .spawn(spawn).bounce(bounce).breakCue(breakCue).expire(expire)
                    .minimumBounceSpeed(floatValue("minimum_bounce_sound_speed", 16.0F)).build();
        }
        private KoilUiParticleSoundProfile.Cue cue(String soundKey, String volumeKey, String pitchKey, String jitterKey, String cooldownKey) {
            String raw = get(soundKey);
            if (raw == null || raw.isBlank()) return null;
            try {
                Identifier id = raw.contains(":") ? new Identifier(raw) : new Identifier("minecraft", raw);
                return new KoilUiParticleSoundProfile.Cue(SoundEvent.of(id),
                        floatValue(volumeKey, 0.22F), floatValue(pitchKey, 1.0F), floatValue(jitterKey, 0.06F),
                        Math.max(0, intValue(cooldownKey, 70)));
            } catch (RuntimeException ignored) { return null; }
        }

        private static void applyRuntimeMaterial(Particle p, KoilUiParticleMaterials.Material material) {
            if (p == null || material == null) return;
            switch (material) {
                case DEFAULT -> { }
                case SMOKE -> { p.behavior = Behavior.RISING_SMOKE; p.gravity = -7.0F; p.drag = 0.955F; p.collideButtons = false; p.particleCollision = false; p.contactMode = ParticleContactMode.IGNORE; p.solidity = 0.0F; p.mass = 0.05F; p.buoyancy = 1.0F; }
                case BLOCK -> { p.behavior = Behavior.RICOCHET; p.gravity = 220.0F; p.drag = 0.995F; p.restitution = 0.66F; p.surfaceFriction = 0.90F; p.collideButtons = true; p.collideScreen = true; p.particleCollision = true; p.contactMode = ParticleContactMode.AUTO; p.solidity = 1.0F; p.mass = 2.4F; p.interactionTags.add("solid"); p.interactionTags.add("block"); }
                case SLIME -> { p.behavior = Behavior.WOBBLE_SLIME; p.gravity = 185.0F; p.drag = 0.994F; p.restitution = 0.91F; p.surfaceFriction = 0.96F; p.collideButtons = true; p.collideScreen = true; p.particleCollision = true; p.contactMode = ParticleContactMode.AUTO; p.solidity = 0.88F; p.mass = 1.15F; p.interactionTags.add("solid"); p.interactionTags.add("slime"); }
                case HONEY -> { p.behavior = Behavior.STICKY_HONEY; p.gravity = 72.0F; p.drag = 0.965F; p.restitution = 0.12F; p.surfaceFriction = 0.38F; p.collideButtons = true; p.particleCollision = true; p.contactMode = ParticleContactMode.AUTO; p.solidity = 0.32F; p.mass = 0.72F; p.stickToButtonsSeconds = Math.max(p.stickToButtonsSeconds, 0.36F); p.stretchWithVelocity = true; }
                case SOUL_FIRE -> { p.behavior = Behavior.RISE_AND_WANDER; p.gravity = -20.0F; p.drag = 0.965F; p.collideButtons = false; }
                case REDSTONE -> { p.behavior = Behavior.DRIFT; p.gravity = 8.0F; p.drag = 0.948F; p.collideButtons = true; }
                case ENCHANT -> { p.behavior = Behavior.SPIRAL; p.gravity = -5.0F; p.drag = 0.972F; p.collideButtons = false; }
                case WATER -> { p.behavior = Behavior.SPLASHING_LIQUID; p.gravity = 155.0F; p.drag = 0.986F; p.restitution = 0.22F; p.collideButtons = true; p.collideScreen = true; p.particleCollision = true; p.contactMode = ParticleContactMode.KILL_BOTH; p.solidity = 0.08F; p.mass = 0.38F; }
                case SNOW -> { p.behavior = Behavior.FLUTTER; p.gravity = 24.0F; p.drag = 0.968F; p.settleOnSurfaces = true; p.collideButtons = true; p.collideScreen = true; }
                case SAND -> { p.behavior = Behavior.BALLISTIC; p.gravity = 210.0F; p.drag = 0.992F; p.restitution = 0.16F; p.surfaceFriction = 0.65F; p.settleOnSurfaces = true; p.collideButtons = true; p.collideScreen = true; p.particleCollision = true; p.contactMode = ParticleContactMode.AUTO; p.solidity = 0.62F; p.mass = 0.55F; }
                case GOO -> { p.behavior = Behavior.WOBBLE_SLIME; p.gravity = 105.0F; p.drag = 0.978F; p.restitution = 0.34F; p.particleCollision = true; p.contactMode = ParticleContactMode.MERGE; p.mergeGroup = "goo"; p.mergeOnContact = true; p.collideButtons = true; p.collideScreen = true; p.solidity = 0.45F; p.mass = 0.8F; }
                case FIREWORK -> { p.behavior = Behavior.BURST_SHELL; p.gravity = 58.0F; p.drag = 0.982F; p.particleCollision = false; p.contactMode = ParticleContactMode.KILL_BOTH; p.solidity = 0.02F; p.mass = 0.08F; }
                case PORTAL -> { p.behavior = Behavior.DRIFT; p.gravity = -3.0F; p.drag = 0.972F; p.collideButtons = false; }
                case AMETHYST -> { p.behavior = Behavior.RICOCHET; p.gravity = 205.0F; p.drag = 0.994F; p.restitution = 0.62F; p.collideButtons = true; p.collideScreen = true; p.particleCollision = true; p.contactMode = ParticleContactMode.AUTO; p.solidity = 0.94F; p.mass = 1.65F; p.interactionTags.add("solid"); p.interactionTags.add("crystal"); }
            }
        }

        private static String normalizeEnum(String value) {
            return value == null ? "" : value.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        }
        private static InteractionLayers parseInteractionLayers(String text) {
            if (text == null || text.isBlank() || text.equalsIgnoreCase("same")) return InteractionLayers.sameLayer();
            if (text.equalsIgnoreCase("all") || text.equals("*")) return InteractionLayers.all();
            try {
                if (text.contains("-")) {
                    String[] p = text.split("-", 2);
                    return InteractionLayers.range(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()));
                }
                String[] parts = text.split(",");
                int[] values = new int[parts.length];
                for (int i = 0; i < parts.length; i++) values[i] = Integer.parseInt(parts[i].trim());
                return InteractionLayers.exact(values);
            } catch (Exception ignored) { return InteractionLayers.sameLayer(); }
        }
        private static int parseColor(String text, int fallback) {
            try {
                String value = text.trim();
                if (value.startsWith("#")) value = value.substring(1);
                if (value.startsWith("0x") || value.startsWith("0X")) value = value.substring(2);
                return Integer.parseUnsignedInt(value, 16) & 0x00FFFFFF;
            } catch (Exception ignored) { return fallback; }
        }
    }

    /** Pixel-art primitives designed to resemble vanilla particle silhouettes. */
    public enum Shape {
        PIXEL,
        DUST,
        BLOCK_SHARD,
        SPARK,
        REDSTONE,
        CRIT,
        GLYPH,
        ENCHANT,
        BUBBLE,
        DROP,
        SMOKE,
        CLOUD,
        FLAME,
        SOUL_FLAME,
        NOTE,
        HEART,
        PETAL,
        LEAF,
        SLIME,
        END_ROD,
        STREAK,
        RIBBON,
        STAR,
        SNOW,
        EXPERIENCE,
        SCULK,
        TOTEM
    }

    /**
     * A spawn point attached to the perimeter of the currently targeted widget.
     * The normal points away from the button and the tangent follows the edge.
     * Effects can use this to create real border explosions instead of birthing
     * particles in the middle of a solid collider.
     */
    public enum CollisionKind {
        TICK,
        BUTTON,
        TARGET,
        SCREEN,
        PARTICLE,
        EXPIRE
    }

    @FunctionalInterface
    public interface ParticleEventHandler {
        void handle(ParticleEventContext event);
    }

    /**
     * Callback view for stateful/compound particles. New particles emitted from
     * callbacks are deferred until the current physics iteration completes.
     */
    public final class ParticleEventContext {
        private final Particle particle;
        private final CollisionKind kind;
        private final float normalX;
        private final float normalY;
        private final float impactSpeed;
        private final float deltaSeconds;

        private ParticleEventContext(
                Particle particle,
                CollisionKind kind,
                float normalX,
                float normalY,
                float impactSpeed,
                float deltaSeconds
        ) {
            this.particle = particle;
            this.kind = kind;
            this.normalX = normalX;
            this.normalY = normalY;
            this.impactSpeed = impactSpeed;
            this.deltaSeconds = deltaSeconds;
        }

        public CollisionKind kind() { return kind; }
        public float x() { return particle.x; }
        public float y() { return particle.y; }
        public float vx() { return particle.vx; }
        public float vy() { return particle.vy; }
        public float age() { return particle.age; }
        public float lifetime() { return particle.lifetime; }
        public int bounceCount() { return particle.bounceCount; }
        public float normalX() { return normalX; }
        public float normalY() { return normalY; }
        public float impactSpeed() { return impactSpeed; }
        public float deltaSeconds() { return deltaSeconds; }
        public boolean isFrontLayer() { return particle.layer == Layer.FRONT; }
        public int simulationLayer() { return particle.simulationLayerId; }
        public float mass() { return particle.mass; }
        public float solidity() { return particle.solidity; }
        public float temperature() { return particle.temperature; }
        public float charge() { return particle.charge; }
        public float conductivity() { return particle.conductivity; }
        public float flammability() { return particle.flammability; }
        public float buoyancy() { return particle.buoyancy; }
        public String screenId() { return particle.screenId; }
        public long spawnSequence() { return particle.spawnSequence; }
        public boolean hasTag(String tag) { return tag != null && particle.interactionTags.contains(tag.toLowerCase()); }
        public String data(String key) { return key == null ? null : particle.data.get(key); }
        public void data(String key, Object value) { if (key != null && value != null) particle.data.put(key, String.valueOf(value)); }
        public void temperature(float value) { particle.temperature = value; }
        public void charge(float value) { particle.charge = value; }

        public void kill() { particle.removeRequested = true; }
        public ParticleBuilder particle(Shape shape) { return effectContext.particle(shape, particle.x, particle.y); }
        public ParticleBuilder particle(Shape shape, float x, float y) { return effectContext.particle(shape, x, y); }
        public void spawn(ParticleBuilder builder) { effectContext.spawn(builder); }
        public void pulse(float duration, int color, float scale, Layer layer) {
            effectContext.pulse(0.0F, duration, color, scale, layer);
        }
        public float random(float min, float max) { return effectContext.random(min, max); }
        public int randomInt(int bound) { return effectContext.randomInt(bound); }
        public boolean chance(float chance) { return effectContext.chance(chance); }
        public int pickColor(int... colors) { return effectContext.pickColor(colors); }
        public void playSound(SoundEvent sound, float volume, float pitch) { if (soundEnabled) KoilUiParticleSoundEngine.play(sound, volume, pitch); }
        public void playSound(KoilUiParticleSoundProfile.Cue cue) { if (soundEnabled) KoilUiParticleSoundEngine.play(cue); }
        public void playBlockHit(Block block) {
            if (block == null || !soundEnabled) return;
            KoilUiParticleSoundProfile profile = KoilUiParticleSoundProfile.forBouncingBlock(block);
            KoilUiParticleSoundEngine.play(profile.bounce());
        }
        public void playBlockBreak(Block block) {
            if (block == null || !soundEnabled) return;
            KoilUiParticleSoundProfile profile = KoilUiParticleSoundProfile.forBlock(block, false);
            KoilUiParticleSoundEngine.play(profile.breakCue());
        }
    }

    public record FaceSpawn(float x, float y, float normalX, float normalY, float tangentX, float tangentY) {
        public float velocityX(float outwardSpeed, float tangentSpeed) {
            return normalX * outwardSpeed + tangentX * tangentSpeed;
        }

        public float velocityY(float outwardSpeed, float tangentSpeed) {
            return normalY * outwardSpeed + tangentY * tangentSpeed;
        }
    }

    public record BorderSpawn(float x, float y, float normalX, float normalY, float tangentX, float tangentY) {
        public float velocityX(float outwardSpeed, float tangentSpeed) {
            return normalX * outwardSpeed + tangentX * tangentSpeed;
        }

        public float velocityY(float outwardSpeed, float tangentSpeed) {
            return normalY * outwardSpeed + tangentY * tangentSpeed;
        }
    }

    /**
     * Effect-facing API. It exposes target geometry, deterministic frame delta,
     * spawning, emission-rate accounting, pulse helpers, and random utilities,
     * while keeping mutable simulation internals inside the engine.
     */
    public final class EffectContext {
        private EffectContext() {
        }

        public float centerX() { return centerX; }
        public float centerY() { return centerY; }
        public int targetX() { return targetX; }
        public int targetY() { return targetY; }
        public int targetWidth() { return targetWidth; }
        public int targetHeight() { return targetHeight; }
        public int screenWidth() { return screenWidth; }
        public int screenHeight() { return screenHeight; }
        public int particleCount() { return particles.size(); }
        public int maxParticles() { return maxParticles; }
        public int buttonColliderCount() { return buttonColliders.size(); }
        public float qualityScale() { return adaptiveQuality; }
        public float densityScale() { return effectiveDensityScale(); }
        public String screenId() { return currentScreenId; }
        public long spawnSequence() { return spawnSequence; }
        public String override(String key) { return activeSpawnOverrides.get(key); }
        public boolean hasOverride(String key) { return activeSpawnOverrides.has(key); }
        public void playSound(SoundEvent sound, float volume, float pitch) { if (soundEnabled) KoilUiParticleSoundEngine.play(sound, volume, pitch); }
        public void playSound(KoilUiParticleSoundProfile.Cue cue) { if (soundEnabled) KoilUiParticleSoundEngine.play(cue); }
        public void windZone(float x, float y, float width, float height, float forceX, float forceY, float lifetime) {
            forceFields.add(ForceField.wind(x, y, width, height, forceX, forceY, lifetime));
        }
        public void vortexWell(float x, float y, float radius, float spin, float pull, float lifetime) {
            forceFields.add(ForceField.vortex(x, y, radius, spin, pull, lifetime));
        }

        /**
         * Uniform spawn across the visible face of the active button.
         * The normal points away from the button center.
         */
        public FaceSpawn faceSpawn(float inset) {
            float safeInset = Math.max(0.0F, inset);
            float left = targetX + safeInset;
            float right = targetX + targetWidth - safeInset;
            float top = targetY + safeInset;
            float bottom = targetY + targetHeight - safeInset;

            if (right <= left) {
                left = targetX;
                right = targetX + targetWidth;
            }
            if (bottom <= top) {
                top = targetY;
                bottom = targetY + targetHeight;
            }

            float x = random(left, right);
            float y = random(top, bottom);
            float dx = x - centerX;
            float dy = y - centerY;
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length < 0.01F) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                dx = (float) Math.cos(angle);
                dy = (float) Math.sin(angle);
                length = 1.0F;
            }
            float nx = dx / length;
            float ny = dy / length;
            return new FaceSpawn(x, y, nx, ny, -ny, nx);
        }

        public float random(float min, float max) {
            return min + random.nextFloat() * (max - min);
        }

        public int randomInt(int bound) {
            return random.nextInt(Math.max(1, bound));
        }

        public boolean chance(float probability) {
            return random.nextFloat() < clamp(probability, 0.0F, 1.0F);
        }

        public int pickColor(int... colors) {
            if (colors == null || colors.length == 0) {
                return 0xFFFFFF;
            }
            return colors[random.nextInt(colors.length)] & 0x00FFFFFF;
        }

        public float sideOffset(float minimum, float maximum) {
            float value = random(Math.min(minimum, maximum), Math.max(minimum, maximum));
            return random.nextBoolean() ? -value : value;
        }

        /**
         * Picks a random point on the current button border. Positive outset is
         * outside the widget, zero is exactly on the edge, and a small negative
         * outset intentionally begins just inside the edge for front-layer
         * particles that should visually erupt through the button border.
         */
        public BorderSpawn borderSpawn(float outset) {
            return borderSpawn(outset, outset);
        }

        /** Picks a random perimeter point with a randomized border outset. */
        public BorderSpawn borderSpawn(float minOutset, float maxOutset) {
            float outset = random(Math.min(minOutset, maxOutset), Math.max(minOutset, maxOutset));
            int edge = randomInt(4);
            float x;
            float y;
            float nx;
            float ny;
            float tx;
            float ty;

            switch (edge) {
                case 0 -> { // top
                    x = random(targetX, targetX + targetWidth);
                    y = targetY - outset;
                    nx = 0.0F; ny = -1.0F; tx = 1.0F; ty = 0.0F;
                }
                case 1 -> { // right
                    x = targetX + targetWidth + outset;
                    y = random(targetY, targetY + targetHeight);
                    nx = 1.0F; ny = 0.0F; tx = 0.0F; ty = 1.0F;
                }
                case 2 -> { // bottom
                    x = random(targetX, targetX + targetWidth);
                    y = targetY + targetHeight + outset;
                    nx = 0.0F; ny = 1.0F; tx = -1.0F; ty = 0.0F;
                }
                default -> { // left
                    x = targetX - outset;
                    y = random(targetY, targetY + targetHeight);
                    nx = -1.0F; ny = 0.0F; tx = 0.0F; ty = -1.0F;
                }
            }
            return new BorderSpawn(x, y, nx, ny, tx, ty);
        }

        /**
         * Convenience for a particle born on the target perimeter. It keeps all
         * normal button collision enabled; the engine only gives the source
         * button a temporary exit-only exemption while the particle clears it.
         */
        public ParticleBuilder particleFromBorder(Shape shape, float minOutset, float maxOutset) {
            BorderSpawn spawn = borderSpawn(minOutset, maxOutset);
            return new ParticleBuilder(shape, spawn.x(), spawn.y());
        }

        /**
         * Returns how many particles should be emitted this frame for a named
         * emitter channel. Separate channels have separate fractional carry.
         */
        public int emissionCount(String channel, float particlesPerSecond, float deltaSeconds) {
            if (particlesPerSecond <= 0.0F || deltaSeconds <= 0.0F || particles.size() >= maxParticles) {
                return 0;
            }
            String key = (currentEffect == null ? "effect" : currentEffect.id()) + ":" + channel;
            double accumulated = emissionAccumulators.getOrDefault(key, 0.0) + particlesPerSecond * effectiveDensityScale() * deltaSeconds;
            int count = (int) Math.floor(accumulated);
            emissionAccumulators.put(key, accumulated - count);
            return Math.min(count, Math.max(0, maxParticles - particles.size()));
        }

        public ParticleBuilder particle(Shape shape) {
            return new ParticleBuilder(shape, centerX, centerY);
        }

        public ParticleBuilder particle(Shape shape, float x, float y) {
            return new ParticleBuilder(shape, x, y);
        }

        public void spawn(ParticleBuilder builder) {
            if (builder == null) return;
            float density = effectiveDensityScale();
            if (density <= 0.0F || (density < 1.0F && random.nextFloat() > density)) return;
            int liveCount = particles.size() + pendingParticles.size();
            if (liveCount >= Math.max(24, Math.round(maxParticles * Math.min(1.0F, adaptiveQuality + 0.12F)))) return;

            Particle particle = builder.build();
            particle.particleId = ++particleSequence;
            prepareSourceButtonExit(particle);
            captureSpawnOverlapImmunity(particle);
            if (soundEnabled && particle.soundProfile != null && particle.soundProfile.spawn() != null) {
                KoilUiParticleSoundEngine.play(particle.soundProfile.spawn());
            }
            if (updatingParticles) {
                pendingParticles.add(particle);
            } else {
                addParticle(particle);
            }
        }

        public void pulse(float delay, float duration, int color, float scale) {
            pulse(delay, duration, color, scale, Layer.BACK);
        }

        public void pulse(float delay, float duration, int color, float scale, Layer layer) {
            Pulse pulse = new Pulse();
            pulse.delay = Math.max(0.0F, delay);
            pulse.duration = Math.max(0.05F, duration);
            pulse.color = color & 0x00FFFFFF;
            pulse.scale = Math.max(0.1F, scale);
            pulse.layer = layer == null ? Layer.BACK : layer;
            pulse.seed = random.nextFloat() * 1000.0F;
            pulses.add(pulse);
        }

        public void pulse(float delay, float duration, int color, float scale, float startRadius, float travel, int alpha, boolean doubleBorder, Layer layer) {
            Pulse pulse = new Pulse();
            pulse.delay = Math.max(0.0F, delay);
            pulse.duration = Math.max(0.05F, duration);
            pulse.color = color & 0x00FFFFFF;
            pulse.scale = Math.max(0.1F, scale);
            pulse.startRadius = Math.max(0.0F, startRadius);
            pulse.travel = Math.max(1.0F, travel);
            pulse.alpha = Math.max(1, Math.min(255, alpha));
            pulse.doubleBorder = doubleBorder;
            pulse.layer = layer == null ? Layer.BACK : layer;
            pulse.seed = random.nextFloat() * 1000.0F;
            pulses.add(pulse);
        }
    }

    /** Fluent particle definition used by both built-ins and third-party effects. */
    public final class ParticleBuilder {
        private final Shape shape;
        private final float x;
        private final float y;
        private int color = 0xFFFFFF;
        private float size = 1.0F;
        private float lifetime = 1.0F;
        private Layer layer = Layer.BACK;
        private float vx;
        private float vy;
        private float gravity;
        private float drag = 0.988F;
        private float restitution = 0.42F;
        private float surfaceFriction = 0.86F;
        private boolean collideTarget;
        private boolean collideButtons = true;
        private boolean sourceButtonExit = true;
        private float sourceEruptionGrace = 0.0F;
        private float sourceEruptionMax = SOURCE_ERUPTION_MAX_SECONDS;
        private boolean collideScreen;
        private boolean bounceCeiling;
        private float rotation;
        private float angularVelocity;
        private float angularDrag = 0.985F;
        private Behavior behavior = Behavior.BALLISTIC;
        private float anchorX;
        private float anchorY;
        private float orbitRadius = 24.0F;
        private float force;
        private float spring = 5.5F;
        private float lift;
        private float frequency = 5.0F;
        private float seed = random.nextFloat() * 100.0F;
        private float alpha = 1.0F;
        private float frontAlpha = 0.78F;
        private float fadeIn = 0.08F;
        private float fadeOut = 0.32F;
        private boolean protectTarget = true;
        private int variant;
        private VisualFamily visualFamily = VisualFamily.AUTO;
        private KoilVanillaParticleSprites.SpriteSet spriteSet;
        private Identifier customTexture;
        private List<Identifier> textureSequence;
        private boolean textureTint = true;
        private float spriteScale = 1.0F;
        private float visualPixels;
        private SizeBand sizeBand;
        private RotationPolicy rotationPolicy;
        private PixelSnap pixelSnap = PixelSnap.SOFT;
        /** 0 means read the real PNG/frame dimensions from the resource manager. */
        private int textureWidth;
        private int textureHeight;
        private boolean textureAutoSize = true;
        private float animationSpeed = 1.0F;
        private boolean animationLoop = true;
        private boolean animationInterpolate = true;
        private float collisionScale = 1.0F;
        private boolean stretchWithVelocity;
        private boolean settleOnSurfaces;
        private float stickToButtonsSeconds;
        private boolean particleCollision = true;
        private String mergeGroup;
        private boolean mergeOnContact;
        private float spawnCollisionInvulnerability = 0.0F;
        private int simulationLayerId;
        private boolean crossLayerInteractions;
        private InteractionLayers interactionLayers = InteractionLayers.sameLayer();
        private ParticleContactMode contactMode = ParticleContactMode.KILL_BOTH;
        private float mass = 1.0F;
        private float solidity = 0.0F;
        private float temperature;
        private float charge;
        private float conductivity;
        private float flammability;
        private float buoyancy;
        private final Set<String> interactionTags = new HashSet<>();
        private final Map<String, String> data = new LinkedHashMap<>();
        private KoilUiParticleMaterials.Material material = KoilUiParticleMaterials.Material.DEFAULT;
        private KoilUiParticleSoundProfile soundProfile;
        private ParticleEventHandler onTick;
        private ParticleEventHandler onBounce;
        private ParticleEventHandler onExpire;

        private ParticleBuilder(Shape shape, float x, float y) {
            this.shape = shape == null ? Shape.PIXEL : shape;
            this.x = x;
            this.y = y;
            this.anchorX = centerX;
            this.anchorY = centerY;
            this.rotation = random.nextFloat() * 360.0F;
            this.variant = random.nextInt(26);
        }

        public ParticleBuilder color(int rgb) { this.color = rgb & 0x00FFFFFF; return this; }
        public ParticleBuilder size(float size) { this.size = Math.max(0.5F, size); return this; }
        public ParticleBuilder lifetime(float lifetime) { this.lifetime = Math.max(0.05F, lifetime); return this; }
        public ParticleBuilder layer(Layer layer) { this.layer = layer == null ? Layer.BACK : layer; return this; }
        public ParticleBuilder velocity(float vx, float vy) { this.vx = vx; this.vy = vy; return this; }
        public ParticleBuilder gravity(float gravity) { this.gravity = gravity; return this; }
        public ParticleBuilder drag(float drag) { this.drag = clamp(drag, 0.0F, 1.0F); return this; }
        public ParticleBuilder restitution(float restitution) { this.restitution = clamp(restitution, 0.0F, 1.0F); return this; }
        public ParticleBuilder surfaceFriction(float friction) { this.surfaceFriction = clamp(friction, 0.0F, 1.0F); return this; }
        public ParticleBuilder collideTarget(boolean collide) { this.collideTarget = collide; return this; }
        /** All visible ButtonWidget instances are solid by default. */
        public ParticleBuilder collideButtons(boolean collide) { this.collideButtons = collide; return this; }
        /**
         * When true (default), a particle that is born overlapping the current
         * target button may leave that source collider once before normal self
         * collision arms. Disable only for particles that should depenetrate
         * immediately at birth. Other buttons are never exempted.
         */
        public ParticleBuilder sourceButtonExit(boolean enabled) { this.sourceButtonExit = enabled; return this; }
        /** Short alias useful to effect authors for border explosions. */
        public ParticleBuilder emergeFromTarget() { this.sourceButtonExit = true; return this; }
        /** Controls how long the source button is visually pass-through at birth. */
        /** @deprecated Birth immunity is overlap-driven; retained for source compatibility. */
        @Deprecated
        public ParticleBuilder eruptionGrace(float seconds) { return this; }
        /** Hard upper bound before a stuck source particle is safely ejected. */
        public ParticleBuilder eruptionMax(float seconds) { this.sourceEruptionMax = clamp(seconds, this.sourceEruptionGrace, 1.5F); return this; }
        public ParticleBuilder collideScreen(boolean collide) { this.collideScreen = collide; return this; }
        public ParticleBuilder bounceCeiling(boolean bounce) { this.bounceCeiling = bounce; return this; }
        public ParticleBuilder rotation(float degrees) { this.rotation = degrees; return this; }
        public ParticleBuilder angularVelocity(float degreesPerSecond) { this.angularVelocity = degreesPerSecond; return this; }
        public ParticleBuilder angularDrag(float drag) { this.angularDrag = clamp(drag, 0.0F, 1.0F); return this; }
        public ParticleBuilder behavior(Behavior behavior) { this.behavior = behavior == null ? Behavior.BALLISTIC : behavior; return this; }
        public ParticleBuilder anchor(float x, float y) { this.anchorX = x; this.anchorY = y; return this; }
        public ParticleBuilder orbitRadius(float radius) { this.orbitRadius = Math.max(1.0F, radius); return this; }
        public ParticleBuilder force(float force) { this.force = force; return this; }
        public ParticleBuilder spring(float spring) { this.spring = spring; return this; }
        public ParticleBuilder lift(float lift) { this.lift = lift; return this; }
        public ParticleBuilder frequency(float frequency) { this.frequency = frequency; return this; }
        public ParticleBuilder seed(float seed) { this.seed = seed; return this; }
        public ParticleBuilder alpha(float alpha) { this.alpha = clamp(alpha, 0.0F, 1.0F); return this; }
        public ParticleBuilder frontAlpha(float alpha) { this.frontAlpha = clamp(alpha, 0.0F, 1.0F); return this; }
        public ParticleBuilder fades(float fadeInFraction, float fadeOutFraction) { this.fadeIn = clamp(fadeInFraction, 0.001F, 1.0F); this.fadeOut = clamp(fadeOutFraction, 0.001F, 1.0F); return this; }
        public ParticleBuilder protectTarget(boolean protect) { this.protectTarget = protect; return this; }
        public ParticleBuilder variant(int variant) { this.variant = variant; return this; }
        /** Selects what a particle looks like independently from how it moves. */
        public ParticleBuilder visual(VisualFamily family) { this.visualFamily = family == null ? VisualFamily.AUTO : family; return this; }
        /** Uses one of Minecraft 1.20.1's own particle sprite families. */
        public ParticleBuilder sprite(KoilVanillaParticleSprites.SpriteSet spriteSet) { this.spriteSet = spriteSet; return this; }
        /** Allows addon authors to use any resource texture. Dimensions are auto-detected by default. */
        public ParticleBuilder texture(Identifier texture) { this.customTexture = texture; return this; }
        public ParticleBuilder textureSequence(List<Identifier> textures) {
            this.textureSequence = textures == null ? null : List.copyOf(textures);
            return this;
        }
        public ParticleBuilder textureTint(boolean tint) { this.textureTint = tint; return this; }
        public ParticleBuilder spriteScale(float scale) { this.spriteScale = Math.max(0.05F, scale); return this; }
        public ParticleBuilder visualPixels(float pixels) { this.visualPixels = Math.max(1.0F, pixels); return this; }
        public ParticleBuilder sizeBand(SizeBand band) { this.sizeBand = band; return this; }
        public ParticleBuilder rotationPolicy(RotationPolicy policy) { this.rotationPolicy = policy; return this; }
        public ParticleBuilder pixelSnap(PixelSnap snap) { this.pixelSnap = snap == null ? PixelSnap.SOFT : snap; return this; }
        /** Explicitly overrides the real texture/frame dimensions. Prefer auto sizing unless required. */
        public ParticleBuilder textureSize(int width, int height) { this.textureWidth = Math.max(1, width); this.textureHeight = Math.max(1, height); this.textureAutoSize = false; return this; }
        public ParticleBuilder textureAutoSize(boolean auto) { this.textureAutoSize = auto; return this; }
        public ParticleBuilder animationSpeed(float speed) { this.animationSpeed = Math.max(0.01F, speed); return this; }
        public ParticleBuilder animationLoop(boolean loop) { this.animationLoop = loop; return this; }
        public ParticleBuilder animationInterpolate(boolean interpolate) { this.animationInterpolate = interpolate; return this; }
        public ParticleBuilder collisionScale(float scale) { this.collisionScale = clamp(scale, 0.05F, 4.0F); return this; }
        public ParticleBuilder stretchWithVelocity(boolean stretch) { this.stretchWithVelocity = stretch; return this; }
        public ParticleBuilder settleOnSurfaces(boolean settle) { this.settleOnSurfaces = settle; return this; }
        public ParticleBuilder stickToButtons(float seconds) { this.stickToButtonsSeconds = Math.max(0.0F, seconds); return this; }
        public ParticleBuilder particleCollision(boolean collide) { this.particleCollision = collide; return this; }
        public ParticleBuilder mergeGroup(String group, boolean merge) { this.mergeGroup = group; this.mergeOnContact = merge; this.particleCollision = true; this.contactMode = merge ? ParticleContactMode.MERGE : this.contactMode; return this; }
        /** @deprecated Birth immunity is overlap-driven; retained for source compatibility. */
        @Deprecated
        public ParticleBuilder spawnCollisionInvulnerability(float seconds) { return this; }
        public ParticleBuilder simulationLayer(int layerId) { this.simulationLayerId = layerId; return this; }
        public ParticleBuilder crossLayerInteractions(boolean enabled) { this.crossLayerInteractions = enabled; return this; }
        public ParticleBuilder interactionLayers(InteractionLayers layers) { this.interactionLayers = layers == null ? InteractionLayers.sameLayer() : layers; return this; }
        public ParticleBuilder interactionLayerRange(int min, int max) { return interactionLayers(InteractionLayers.range(min, max)); }
        public ParticleBuilder allInteractionLayers() { return interactionLayers(InteractionLayers.all()); }
        public ParticleBuilder contactMode(ParticleContactMode mode) { this.contactMode = mode == null ? ParticleContactMode.KILL_BOTH : mode; this.particleCollision = mode != ParticleContactMode.IGNORE; return this; }
        public ParticleBuilder mass(float mass) { this.mass = Math.max(0.001F, mass); return this; }
        public ParticleBuilder solidity(float solidity) { this.solidity = clamp(solidity, 0.0F, 1.0F); return this; }
        public ParticleBuilder temperature(float value) { this.temperature = value; return this; }
        public ParticleBuilder charge(float value) { this.charge = value; return this; }
        public ParticleBuilder conductivity(float value) { this.conductivity = clamp(value, 0.0F, 1.0F); return this; }
        public ParticleBuilder flammability(float value) { this.flammability = clamp(value, 0.0F, 1.0F); return this; }
        public ParticleBuilder buoyancy(float value) { this.buoyancy = value; return this; }
        public ParticleBuilder tag(String tag) { if (tag != null && !tag.isBlank()) this.interactionTags.add(tag.trim().toLowerCase()); return this; }
        public ParticleBuilder data(String key, Object value) { if (key != null && !key.isBlank() && value != null) this.data.put(key.trim(), String.valueOf(value)); return this; }
        public ParticleBuilder material(KoilUiParticleMaterials.Material material) {
            this.material = material == null ? KoilUiParticleMaterials.Material.DEFAULT : material;
            KoilUiParticleMaterials.apply(this.material, this);
            return this;
        }
        public ParticleBuilder soundProfile(KoilUiParticleSoundProfile profile) { this.soundProfile = profile; return this; }
        public ParticleBuilder blockSounds(Block block) { this.soundProfile = KoilUiParticleSoundProfile.forBlock(block); return this; }
        public ParticleBuilder bouncingBlockSounds(Block block) { this.soundProfile = KoilUiParticleSoundProfile.forBouncingBlock(block); return this; }
        public ParticleBuilder onTick(ParticleEventHandler handler) { this.onTick = handler; return this; }
        public ParticleBuilder onBounce(ParticleEventHandler handler) { this.onBounce = handler; return this; }
        public ParticleBuilder onExpire(ParticleEventHandler handler) { this.onExpire = handler; return this; }

        private Particle build() {
            Particle particle = new Particle();
            particle.shape = shape;
            particle.x = x;
            particle.y = y;
            particle.color = color;
            particle.size = size;
            particle.lifetime = lifetime;
            particle.layer = layer;
            particle.vx = vx;
            particle.vy = vy;
            particle.gravity = gravity;
            particle.drag = drag;
            particle.restitution = restitution;
            particle.surfaceFriction = surfaceFriction;
            particle.collideTarget = collideTarget;
            particle.collideButtons = collideButtons;
            particle.sourceButtonExit = sourceButtonExit;
            particle.sourceEruptionGrace = sourceEruptionGrace;
            particle.sourceEruptionMax = Math.max(sourceEruptionGrace, sourceEruptionMax);
            particle.collideScreen = collideScreen;
            particle.bounceCeiling = bounceCeiling;
            particle.rotation = rotation;
            particle.angularVelocity = angularVelocity;
            particle.angularDrag = angularDrag;
            particle.behavior = behavior;
            particle.anchorX = anchorX;
            particle.anchorY = anchorY;
            particle.orbitRadius = orbitRadius;
            particle.force = force;
            particle.spring = spring;
            particle.lift = lift;
            particle.frequency = frequency;
            particle.seed = seed;
            particle.alpha = alpha;
            particle.frontAlpha = frontAlpha;
            particle.fadeIn = fadeIn;
            particle.fadeOut = fadeOut;
            particle.protectTarget = protectTarget;
            particle.variant = variant;
            particle.visualFamily = visualFamily;
            particle.spriteSet = spriteSet;
            particle.customTexture = customTexture;
            particle.textureSequence = textureSequence;
            particle.textureTint = textureTint;
            particle.spriteScale = spriteScale;
            particle.visualPixels = visualPixels;
            particle.sizeBand = sizeBand;
            particle.rotationPolicy = rotationPolicy;
            particle.pixelSnap = pixelSnap;
            particle.textureWidth = textureWidth;
            particle.textureHeight = textureHeight;
            particle.textureAutoSize = textureAutoSize;
            particle.animationSpeed = animationSpeed;
            particle.animationLoop = animationLoop;
            particle.animationInterpolate = animationInterpolate;
            particle.collisionScale = collisionScale;
            particle.stretchWithVelocity = stretchWithVelocity;
            particle.settleOnSurfaces = settleOnSurfaces;
            particle.stickToButtonsSeconds = stickToButtonsSeconds;
            particle.particleCollision = particleCollision;
            particle.mergeGroup = mergeGroup;
            particle.mergeOnContact = mergeOnContact;
            // Timed spawn immunity is deprecated. Collision immunity is captured
            // from actual overlaps at birth and ends when those overlaps clear.
            particle.spawnCollisionInvulnerability = 0.0F;
            particle.simulationLayerId = simulationLayerId;
            particle.crossLayerInteractions = crossLayerInteractions;
            particle.interactionLayers = interactionLayers == null ? InteractionLayers.sameLayer() : interactionLayers;
            particle.contactMode = contactMode == null ? ParticleContactMode.KILL_BOTH : contactMode;
            particle.mass = Math.max(0.001F, mass);
            particle.solidity = clamp(solidity, 0.0F, 1.0F);
            particle.temperature = temperature;
            particle.charge = charge;
            particle.conductivity = clamp(conductivity, 0.0F, 1.0F);
            particle.flammability = clamp(flammability, 0.0F, 1.0F);
            particle.buoyancy = buoyancy;
            particle.interactionTags.addAll(interactionTags);
            particle.data.putAll(data);
            particle.screenId = currentScreenId;
            particle.spawnSequence = KoilUiParticleEngine.this.spawnSequence;
            particle.material = material;
            particle.soundProfile = soundProfile;
            particle.onTick = onTick;
            particle.onBounce = onBounce;
            particle.onExpire = onExpire;
            particle.prevX = x;
            particle.prevY = y;
            activeSpawnOverrides.apply(particle);
            return particle;
        }
    }

    private static final class Particle {
        private Shape shape;
        private Layer layer;
        private Behavior behavior;
        private float x;
        private float y;
        private float prevX;
        private float prevY;
        private float vx;
        private float vy;
        private float gravity;
        private float drag;
        private float restitution;
        private float surfaceFriction;
        private float size;
        private float age;
        private float lifetime;
        private int color;
        private float rotation;
        private float angularVelocity;
        private float angularDrag;
        private boolean collideTarget;
        private boolean collideButtons;
        private boolean sourceButtonExit;
        private boolean ignoreSourceButtonUntilExit;
        private boolean sourceButtonCaptured;
        private float sourceEruptionGrace;
        private float sourceEruptionMax;
        private float sourceLeft;
        private float sourceTop;
        private float sourceRight;
        private float sourceBottom;
        private boolean collideScreen;
        private boolean bounceCeiling;
        private float anchorX;
        private float anchorY;
        private float orbitRadius;
        private float force;
        private float spring;
        private float lift;
        private float frequency;
        private float seed;
        private float alpha;
        private float frontAlpha;
        private float fadeIn;
        private float fadeOut;
        private boolean protectTarget;
        private int variant;
        private VisualFamily visualFamily = VisualFamily.AUTO;
        private KoilVanillaParticleSprites.SpriteSet spriteSet;
        private Identifier customTexture;
        private List<Identifier> textureSequence;
        private boolean textureTint;
        private float spriteScale;
        private float visualPixels;
        private SizeBand sizeBand;
        private RotationPolicy rotationPolicy;
        private PixelSnap pixelSnap;
        private int textureWidth;
        private int textureHeight;
        private boolean textureAutoSize = true;
        private float animationSpeed = 1.0F;
        private boolean animationLoop = true;
        private boolean animationInterpolate = true;
        private float collisionScale = 1.0F;
        private boolean stretchWithVelocity;
        private float dynamicStretchX = 1.0F;
        private float dynamicStretchY = 1.0F;
        private float visualGrowth = 1.0F;
        private float visualSquash = 1.0F;
        private boolean settleOnSurfaces;
        private boolean sleeping;
        private float stickToButtonsSeconds;
        private float stuckUntil;
        private boolean particleCollision;
        private String mergeGroup;
        private boolean mergeOnContact;
        private float spawnCollisionInvulnerability = 0.0F;
        private int simulationLayerId;
        private boolean crossLayerInteractions;
        private InteractionLayers interactionLayers = InteractionLayers.sameLayer();
        private ParticleContactMode contactMode = ParticleContactMode.KILL_BOTH;
        private float mass = 1.0F;
        private float solidity = 0.0F;
        private float temperature;
        private float charge;
        private float conductivity;
        private float flammability;
        private float buoyancy;
        private final Set<String> interactionTags = new HashSet<>();
        private final Map<String, String> data = new LinkedHashMap<>();
        private String screenId = "";
        private long spawnSequence;
        private long particleId;
        private boolean spawnTargetImmunity;
        private final List<SpawnElementImmunity> spawnElementImmunities = new ArrayList<>();
        private final Set<Long> spawnParticleImmunities = new HashSet<>();
        private KoilUiParticleMaterials.Material material;
        private KoilUiParticleSoundProfile soundProfile;
        private int bounceCount;
        private float lastBounceAge = -1000.0F;
        private boolean removeRequested;
        private ParticleEventHandler onTick;
        private ParticleEventHandler onBounce;
        private ParticleEventHandler onExpire;
    }


    private record SpawnElementImmunity(float left, float top, float right, float bottom) { }

    private enum ForceFieldType { WIND, VORTEX }

    private static final class ForceField {
        private ForceFieldType type;
        private float x, y, width, height, radius, forceX, forceY, spin, pull, age, lifetime;

        static ForceField wind(float x, float y, float width, float height, float forceX, float forceY, float lifetime) {
            ForceField field = new ForceField();
            field.type = ForceFieldType.WIND;
            field.x = x; field.y = y; field.width = Math.max(0.0F, width); field.height = Math.max(0.0F, height);
            field.forceX = forceX; field.forceY = forceY; field.lifetime = Math.max(0.0F, lifetime);
            return field;
        }

        static ForceField vortex(float x, float y, float radius, float spin, float pull, float lifetime) {
            ForceField field = new ForceField();
            field.type = ForceFieldType.VORTEX;
            field.x = x; field.y = y; field.radius = Math.max(1.0F, radius);
            field.spin = spin; field.pull = pull; field.lifetime = Math.max(0.0F, lifetime);
            return field;
        }
    }

    private static final class UiCollider {
        private final float left;
        private final float top;
        private final float right;
        private final float bottom;

        private UiCollider(float left, float top, float right, float bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private static final class SweptHit {
        private final float time;
        private final float normalX;
        private final float normalY;

        private SweptHit(float time, float normalX, float normalY) {
            this.time = time;
            this.normalX = normalX;
            this.normalY = normalY;
        }
    }

    private static final class Pulse {
        private float age;
        private float delay;
        private float duration;
        private int color;
        private float scale;
        private float startRadius = 7.0F;
        private float travel = 30.0F;
        private int alpha = 118;
        private boolean doubleBorder = true;
        private Layer layer = Layer.BACK;
        private float seed;
    }
}
