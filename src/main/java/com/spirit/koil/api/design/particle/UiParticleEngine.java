package com.spirit.koil.api.design.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.spirit.koil.api.design.sprite.SpriteEngine;
import com.spirit.koil.api.design.sprite.actor.Actor;
import com.spirit.koil.api.design.sprite.actor.FallingBlockActor;
import com.spirit.koil.api.design.sprite.actor.ItemActor;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneEvent;
import com.spirit.koil.api.design.sprite.debug.SceneDiagnostics;
import com.spirit.koil.api.design.sprite.render.SceneRenderer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.function.Consumer;

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
public final class UiParticleEngine {
    public static final int DEFAULT_MAX_PARTICLES = 420;
    private static final int RECENT_EFFECT_WINDOW = 18;
    private static final int RECENT_FAMILY_WINDOW = 6;
    private static final int MAX_MOUSE_BUTTONS = GLFW.GLFW_MOUSE_BUTTON_LAST + 1;
    private static final int MAX_KEYS = GLFW.GLFW_KEY_LAST + 1;
    private static final float MIN_INTERACTION_MASS = 0.08F;
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
    private final DetachedMinecraftRuntime detachedMinecraftRuntime = DetachedMinecraftRuntime.shared();
    private final SpriteEngine spriteEngine = new SpriteEngine();
    private final SceneRenderer sceneRenderer = new SceneRenderer();
    private final List<SceneEvent.PhysicsContact> scenePhysicsContacts = new ArrayList<>();
    private final List<SceneEvent.ItemStackChanged> sceneItemStackChanges = new ArrayList<>();
    private final List<SceneEvent.SoundRequested> sceneSoundRequests = new ArrayList<>();
    private final List<SceneEvent.SoundStopRequested> sceneSoundStopRequests = new ArrayList<>();
    private final List<SceneEvent.ParticleRequested> sceneParticleRequests = new ArrayList<>();
    private final VirtualBlockWorld virtualBlockWorld = new VirtualBlockWorld();
    private final VirtualEntityWorld virtualEntityWorld = new VirtualEntityWorld();
    private final VirtualServerContext virtualServerContext = new VirtualServerContext();
    private final VirtualGeometryWorld virtualGeometryWorld = new VirtualGeometryWorld();
    private final VirtualInteractionRouter virtualInteractionRouter = new VirtualInteractionRouter(virtualBlockWorld);
    private final List<String> effectPool = new ArrayList<>();
    private final List<String> recentEffectIds = new ArrayList<>();
    private final List<String> recentEffectFamilies = new ArrayList<>();
    private final Map<Identifier, Boolean> textureAvailability = new HashMap<>();
    private final List<ForceField> forceFields = new ArrayList<>();
    private final List<ScheduledEffectAction> scheduledEffectActions = new ArrayList<>();
    private final boolean[] pointerButtons = new boolean[MAX_MOUSE_BUTTONS];
    private final boolean[] keyStates = new boolean[MAX_KEYS];
    private final Set<DirectedPairKey> relationTouching = new HashSet<>();
    private final Set<DirectedPairKey> relationOverlapping = new HashSet<>();
    private final Set<DirectedPairKey> relationSensing = new HashSet<>();
    private final List<ParticleConstraint> particleConstraints = new ArrayList<>();
    private SpawnOverrides activeSpawnOverrides = SpawnOverrides.EMPTY;
    private float pointerX;
    private float pointerY;
    private float pointerDeltaX;
    private float pointerDeltaY;
    private float pointerVelocityX;
    private float pointerVelocityY;
    private boolean pointerInitialized;
    private long pointerSampleNanos = System.nanoTime();
    private long grabbedParticleId = -1L;
    private long selectedParticleId = -1L;
    private int grabbedMouseButton = -1;
    private float grabbedOffsetX;
    private float grabbedOffsetY;
    private boolean particleInteractionsEnabled = true;
    private boolean hoverInteractionsEnabled = true;
    private boolean clickInteractionsEnabled = true;
    private boolean dragInteractionsEnabled = true;
    private boolean swipeInteractionsEnabled = true;
    private boolean scrollInteractionsEnabled = true;
    private boolean keyboardInteractionsEnabled = true;
    private boolean relationInteractionsEnabled = true;
    private boolean constraintPhysicsEnabled = true;
    private long spawnSequence;
    private long particleSequence;
    private float currentEffectAgeSeconds;
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

    private UiParticleEffect currentEffect;
    private UiParticleEffect previousEffect;
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

    public UiParticleEngine() {
        virtualBlockWorld.setDetachedRuntime(detachedMinecraftRuntime);
        // Rev AB: KoilScene.KoilFluidSystem is authoritative. The old virtual
        // block-world fluid implementation is intentionally disabled, not kept
        // alive as a hidden fallback.
        virtualBlockWorld.setLegacyFluidSimulationEnabled(false);
        spriteEngine.setCellPixels(virtualBlockWorld.getCellPixels());
        spriteEngine.setProjection("xy");
        spriteEngine.scene().events().subscribe(event -> {
            if (event instanceof SceneEvent.PhysicsContact contact) scenePhysicsContacts.add(contact);
            else if (event instanceof SceneEvent.ItemStackChanged change) {
                mirrorSceneItemStackToProxy(change);
                sceneItemStackChanges.add(change);
            } else if (event instanceof SceneEvent.LegacyProxyWriteback writeback) {
                mirrorSceneBlockStateToProxy(writeback);
            } else if (event instanceof SceneEvent.SoundRequested sound) sceneSoundRequests.add(sound);
            else if (event instanceof SceneEvent.SoundStopRequested stop) sceneSoundStopRequests.add(stop);
            else if (event instanceof SceneEvent.ParticleRequested particle) sceneParticleRequests.add(particle);
        });
        UiParticlePackManager.loadBuiltinCatalogsOnce();
        UiParticlePackManager.loadDefaultDirectoryOnce();
        GameParticleRegistryBridge.registerAllAvailable();
        GameSpriteRegistryBridge.registerAllAvailable();
        UiParticleSettings.apply(this);
    }

    public void reset() {
        particles.clear();
        pendingParticles.clear();
        pulses.clear();
        buttonColliders.clear();
        emissionAccumulators.clear();
        forceFields.clear();
        scheduledEffectActions.clear();
        textureAvailability.clear();
        currentEffect = null;
        previousEffect = null;
        currentEffectAgeSeconds = 0.0F;
        recentEffectIds.clear();
        recentEffectFamilies.clear();
        grabbedParticleId = -1L;
        selectedParticleId = -1L;
        grabbedMouseButton = -1;
        relationTouching.clear();
        scenePhysicsContacts.clear();
        sceneItemStackChanges.clear();
        sceneSoundRequests.clear();
        sceneSoundStopRequests.clear();
        sceneParticleRequests.clear();
        relationOverlapping.clear();
        relationSensing.clear();
        particleConstraints.clear();
        virtualBlockWorld.clear();
        virtualEntityWorld.clear();
        virtualGeometryWorld.clear();
        virtualInteractionRouter.clear();
        spriteEngine.reset();
        pointerInitialized = false;
        for (int i = 0; i < pointerButtons.length; i++) pointerButtons[i] = false;
        for (int i = 0; i < keyStates.length; i++) keyStates[i] = false;
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
        scheduledEffectActions.clear();
        grabbedParticleId = -1L;
        selectedParticleId = -1L;
        grabbedMouseButton = -1;
        relationTouching.clear();
        relationOverlapping.clear();
        relationSensing.clear();
        particleConstraints.clear();
        virtualBlockWorld.clear();
        virtualEntityWorld.clear();
        virtualGeometryWorld.clear();
        virtualInteractionRouter.clear();
        spriteEngine.reset();
    }

    public void setVirtualBlockWorldEnabled(boolean enabled) {
        virtualBlockWorld.setEnabled(enabled);
        if (!enabled) {
            virtualEntityWorld.clear();
            virtualGeometryWorld.clear();
            virtualInteractionRouter.clear();
        }
    }
    public boolean isVirtualBlockWorldEnabled() { return virtualBlockWorld.isEnabled(); }
    public void setVirtualBlockCellPixels(float pixels) {
        virtualBlockWorld.setCellPixels(pixels);
        virtualGeometryWorld.setCellPixels(virtualBlockWorld.getCellPixels());
        spriteEngine.setCellPixels(virtualBlockWorld.getCellPixels());
    }
    public float getVirtualBlockCellPixels() { return virtualBlockWorld.getCellPixels(); }
    public long getVirtualBlockWorldTick() { return virtualBlockWorld.getWorldTick(); }
    public boolean requiresGameplayWorld() { return false; }
    public String getMinecraftRuntimeMode() { return "detached"; }
    public SpriteEngine getSpriteEngine() { return spriteEngine; }
    public SceneDiagnostics getSceneDiagnostics() { return spriteEngine.diagnostics(); }
    public DetachedMinecraftRuntime.Diagnostics getDetachedRuntimeDiagnostics() {
        return detachedMinecraftRuntime.diagnostics();
    }
    public void reloadDetachedMinecraftData() { detachedMinecraftRuntime.invalidate(); }
    public void setVirtualWorldProjection(String projection) {
        virtualGeometryWorld.setProjection(projection);
        spriteEngine.setProjection(projection);
    }
    public String getVirtualWorldProjection() { return virtualGeometryWorld.getProjection().name().toLowerCase(java.util.Locale.ROOT); }

    public void setDebugEnabled(boolean enabled) { this.debugEnabled = enabled; }
    public boolean isDebugEnabled() { return debugEnabled; }
    public void setSoundEnabled(boolean enabled) { this.soundEnabled = enabled; UiParticleSoundEngine.setEnabled(enabled); }
    public boolean isSoundEnabled() { return soundEnabled; }
    public void setSoundVolume(float volume) { this.soundVolume = clamp(volume, 0.0F, 1.5F); UiParticleSoundEngine.setVolumeScale(this.soundVolume); }
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
        UiParticleTextureMetadata.clearCache();
        UiBlockFaceTextureResolver.clearCache();
        UiItemTextureResolver.clearCache();
        GameParticleRegistryBridge.invalidateResourceCache();
        GameSpriteRegistryBridge.invalidateResourceCache();
        GameParticleRegistryBridge.registerAllAvailable();
        GameSpriteRegistryBridge.registerAllAvailable();
    }

    public void setMaxParticles(int maxParticles) {
        this.maxParticles = Math.max(32, maxParticles);
        trimToLimit();
    }

    public int getMaxParticles() {
        return maxParticles;
    }

    /** Master switch for pointer-to-particle interaction. */
    public void setParticleInteractionsEnabled(boolean enabled) {
        this.particleInteractionsEnabled = enabled;
        if (!enabled) {
            releaseGrabbedParticle(-1, false);
            deselectParticle(false);
        }
    }
    public boolean isParticleInteractionsEnabled() { return particleInteractionsEnabled; }
    public void setHoverInteractionsEnabled(boolean enabled) { this.hoverInteractionsEnabled = enabled; }
    public boolean isHoverInteractionsEnabled() { return hoverInteractionsEnabled; }
    public void setClickInteractionsEnabled(boolean enabled) { this.clickInteractionsEnabled = enabled; }
    public boolean isClickInteractionsEnabled() { return clickInteractionsEnabled; }
    public void setDragInteractionsEnabled(boolean enabled) {
        this.dragInteractionsEnabled = enabled;
        if (!enabled) releaseGrabbedParticle(-1, false);
    }
    public boolean isDragInteractionsEnabled() { return dragInteractionsEnabled; }
    public void setSwipeInteractionsEnabled(boolean enabled) { this.swipeInteractionsEnabled = enabled; }
    public boolean isSwipeInteractionsEnabled() { return swipeInteractionsEnabled; }
    public void setScrollInteractionsEnabled(boolean enabled) { this.scrollInteractionsEnabled = enabled; }
    public boolean isScrollInteractionsEnabled() { return scrollInteractionsEnabled; }
    public void setKeyboardInteractionsEnabled(boolean enabled) { this.keyboardInteractionsEnabled = enabled; }
    public boolean isKeyboardInteractionsEnabled() { return keyboardInteractionsEnabled; }
    public void setRelationInteractionsEnabled(boolean enabled) { this.relationInteractionsEnabled = enabled; }
    public boolean isRelationInteractionsEnabled() { return relationInteractionsEnabled; }
    public void setConstraintPhysicsEnabled(boolean enabled) { this.constraintPhysicsEnabled = enabled; }
    public boolean isConstraintPhysicsEnabled() { return constraintPhysicsEnabled; }
    public long selectedParticleId() { return selectedParticleId; }
    public long grabbedParticleId() { return grabbedParticleId; }

    /**
     * Manual input hook for screens that already receive mouse coordinates.
     * beginFrame also polls Minecraft's current mouse state, so callers only need
     * this when they want deterministic/custom input routing.
     */
    public void pointerMoved(float x, float y) { samplePointer(x, y); }

    /** Manual mouse-button hook. Button values follow GLFW: 0 left, 1 right, 2 middle, 3+ extra buttons. */
    public boolean pointerButton(int button, boolean pressed) {
        if (button < 0 || button >= pointerButtons.length) return false;
        if (pointerButtons[button] == pressed) return false;
        pointerButtons[button] = pressed;
        return handlePointerButtonTransition(button, pressed);
    }

    /**
     * Mouse-wheel hook. Scene blocks rotate by changing their authoritative
     * Minecraft BlockState; ordinary FX/actor particles keep the authored visual
     * rotation path. This is intentionally state-first so stairs, doors, gates,
     * pistons and other directional blocks update rendering and collision together.
     */
    public boolean pointerScroll(double horizontal, double vertical) {
        if (!particleInteractionsEnabled || !scrollInteractionsEnabled) return false;
        Particle target = findParticleById(grabbedParticleId);
        if (target == null) target = findParticleById(selectedParticleId);
        // HUD input can arrive between mouse polling frames. Falling back to the
        // hovered topmost sprite makes wheel manipulation robust without rotating
        // unrelated objects elsewhere on the screen.
        if (target == null) target = findTopInteractiveParticle(pointerX, pointerY);
        // Static scene-block proxies can have legacy authored interaction flags
        // disabled, but orientation is an engine-level authoring affordance. Allow
        // the wheel to target the top scene block under the cursor regardless of
        // the old particle's interactive flag.
        if (target == null) target = findTopSceneBlockParticle(pointerX, pointerY);

        float amount = (float) (Math.abs(vertical) >= Math.abs(horizontal) ? vertical : horizontal);
        if (Math.abs(amount) < 0.0001F) return false;

        // Grid blocks are never visually rotated as particles. Wheel input mutates
        // the real scene BlockState instead, including stair top/bottom variants and
        // six-way-facing blocks. Prefer a legacy proxy mapping when one exists, then
        // fall back to direct scene-grid hit testing for derived/native-only cells.
        SceneCellPos sceneCell = null;
        if (isVirtualGridBlockCandidate(target) && !spriteEngine.isLegacyFluidProxy(target.particleId)) {
            sceneCell = spriteEngine.legacyBlockCell(target.particleId);
        }
        if (sceneCell == null) sceneCell = spriteEngine.sceneBlockAtScreen(pointerX, pointerY);
        if (sceneCell != null) {
            int count = Math.max(1, Math.min(8, Math.round(Math.abs(amount))));
            int steps = amount > 0.0F ? count : -count;
            if (spriteEngine.cycleBlockOrientation(sceneCell, steps)) {
                // Multipart child cells can be scene-native and therefore have no
                // Particle proxy of their own. Resolve the authoritative lower/root
                // proxy so the next compatibility sync cannot overwrite the state
                // change we just made (notably when scrolling the upper half of a door).
                long ownerId = spriteEngine.legacyBlockSourceId(sceneCell);
                Particle stateProxy = isVirtualGridBlockCandidate(target) ? target
                        : (ownerId >= 0L ? findParticleById(ownerId) : null);
                SceneCellPos stateCell = ownerId >= 0L ? spriteEngine.legacyBlockCell(ownerId) : sceneCell;
                BlockState state = spriteEngine.blockState(stateCell == null ? sceneCell : stateCell);
                if (isVirtualGridBlockCandidate(stateProxy) && state != null && !state.isAir()) {
                    stateProxy.blockIcon = state.getBlock();
                    stateProxy.data.put("__koil_block_state", virtualBlockStateSignature(state));
                    stateProxy.rotation = 0.0F;
                    stateProxy.angularVelocity = 0.0F;
                    stateProxy.sleeping = false;
                    stateProxy.stuckUntil = 0.0F;
                    if (stateProxy.hoverResetAge) refreshInteractiveAge(stateProxy);
                    fireInteraction(stateProxy, ParticleInteractionType.SCROLL, -1, -1,
                            (float) horizontal, (float) vertical, null);
                }
                // Orientation is an engine authoring action. Consume the wheel so
                // the same step does not also scroll the player's hotbar/UI.
                return true;
            }
            // A non-orientable scene block under the cursor should not fall through
            // into visual particle rotation.
            if (isVirtualGridBlockCandidate(target)) return false;
        }

        if (target != null && isVirtualRegistryItem(target)) {
            float direction = target.scrollInvert ? -1.0F : 1.0F;
            float degreesPerStep = target.scrollDegreesPerStep > 0.0F ? target.scrollDegreesPerStep : 15.0F;
            float degrees = amount * degreesPerStep * direction;
            if (spriteEngine.rotateItem(target.particleId, degrees)) {
                Actor actor = spriteEngine.actor(target.particleId);
                if (actor != null) {
                    target.rotation = actor.rotation();
                    target.angularVelocity = 0.0F;
                }
                if (target.hoverResetAge) refreshInteractiveAge(target);
                fireInteraction(target, ParticleInteractionType.SCROLL, -1, -1,
                        (float) horizontal, (float) vertical, null);
                return true;
            }
        }

        if (target == null) return false;
        if (!target.interactive || !target.scrollRotateInteraction) return false;
        if (target.scrollRequireGrabbed && !target.dragging) return false;
        float direction = target.scrollInvert ? -1.0F : 1.0F;
        float degrees = amount * target.scrollDegreesPerStep * direction;
        if (target.scrollInertial) {
            float inertia = rotationalInertia(target);
            float impulse = degrees * target.scrollAngularImpulse / Math.max(0.02F, inertia);
            target.angularVelocity = clamp(target.angularVelocity + impulse, -target.scrollMaxAngularVelocity, target.scrollMaxAngularVelocity);
        } else {
            target.rotation += degrees;
            if (target.scrollSnapDegrees > 0.0F) {
                target.rotation = Math.round(target.rotation / target.scrollSnapDegrees) * target.scrollSnapDegrees;
            }
        }
        target.sleeping = false;
        target.stuckUntil = 0.0F;
        if (target.hoverResetAge) refreshInteractiveAge(target);
        fireInteraction(target, ParticleInteractionType.SCROLL, -1, -1, (float) horizontal, (float) vertical, null);
        return target.consumePointerInput;
    }

    /** Manual keyboard hook. GLFW key codes are used directly. */
    public boolean keyInput(int key, boolean pressed) {
        if (key < 0 || key >= keyStates.length) return false;
        if (keyStates[key] == pressed) return false;
        keyStates[key] = pressed;
        return handleKeyTransition(key, pressed);
    }

    /**
     * Restricts random selection to the supplied registered ids. Passing no ids
     * restores the full registry pool, including third-party registered effects.
     */
    public void setEffectPool(String... ids) {
        effectPool.clear();
        recentEffectIds.clear();
        recentEffectFamilies.clear();
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            if (id == null) {
                continue;
            }
            String normalized = UiParticleRegistry.canonicalId(id.trim().toLowerCase());
            if (!normalized.isEmpty() && !effectPool.contains(normalized)) {
                effectPool.add(normalized);
            }
        }
    }

    private void rememberRandomSelection(UiParticleEffect effect) {
        if (effect == null) return;
        String id = effect.id() == null ? "" : effect.id().trim().toLowerCase();
        if (!id.isBlank()) {
            recentEffectIds.remove(id);
            recentEffectIds.add(id);
            while (recentEffectIds.size() > RECENT_EFFECT_WINDOW) recentEffectIds.remove(0);
        }
        String family = UiParticleRegistry.familyKey(effect);
        if (family != null && !family.isBlank()) {
            recentEffectFamilies.remove(family);
            recentEffectFamilies.add(family);
            while (recentEffectFamilies.size() > RECENT_FAMILY_WINDOW) recentEffectFamilies.remove(0);
        }
    }

    public String getCurrentEffectId() {
        return currentEffect == null ? "" : currentEffect.id();
    }

    /** Force a registered effect for the next/current activation. */
    public boolean selectEffect(String id, boolean restart) {
        UiParticleEffect effect = UiParticleRegistry.get(id);
        if (effect == null) {
            return false;
        }
        previousEffect = currentEffect;
        currentEffect = effect;
        emissionAccumulators.clear();
        if (restart) {
            currentEffectAgeSeconds = 0.0F;
            clearParticles();
            UiParticleSoundProfile.Cue beginCue = currentEffect.beginSound();
            if (soundEnabled && beginCue != null) UiParticleSoundEngine.play(beginCue);
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
        if (id == null || id.isBlank() || !UiParticleRegistry.contains(id)) return false;

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
            UiParticleEffect effect = UiParticleRegistry.get(id);
            if (effect == null) return false;
            previousEffect = currentEffect;
            currentEffect = effect;
            currentEffectAgeSeconds = 0.0F;
            emissionAccumulators.clear();
            if (clearExisting) clearParticles();
            UiParticleSoundProfile.Cue beginCue = currentEffect.beginSound();
            if (soundEnabled && beginCue != null) UiParticleSoundEngine.play(beginCue);
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
        UiParticleWidgetProfileRegistry.apply(this, widgetProfileId);
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
        UiParticlePackManager.loadDefaultDirectoryOnce();

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
        pollMinecraftPointerInput();

        float dt = frameDeltaSeconds();
        updatePointerInteractionFrame(dt);

        // Rev E migration order: input first, then mirror legacy render/input proxies
        // into the authoritative scene, advance fixed-step scene physics, and finally
        // copy scene transforms back to the visual proxies. This makes ItemActor and
        // FallingBlockActor motion scene-owned instead of Particle-owned.
        spriteEngine.setViewport(this.screenWidth, this.screenHeight);
        syncSpriteSceneCompatibility();
        spriteEngine.advanceFrame(dt);
        processSceneGameplayEvents();
        applySpriteScenePhysicsToProxies();
        processScenePhysicsContacts();

        updateAdaptiveQuality(dt);
        updateForceFields(dt);
        updateScheduledEffectActions(dt);

        if (active && !wasActive) {
            UiParticleEffect next = UiParticleRegistry.randomDiverse(
                    random, previousEffect, effectPool, recentEffectIds, recentEffectFamilies);
            if (next != null) {
                currentEffect = next;
                currentEffectAgeSeconds = 0.0F;
                previousEffect = next;
                rememberRandomSelection(next);
                emissionAccumulators.clear();
                forceFields.clear();
                UiParticleSoundProfile.Cue beginCue = currentEffect.beginSound();
                if (soundEnabled && beginCue != null) UiParticleSoundEngine.play(beginCue);
                currentEffect.onBegin(effectContext);
            }
        }

        if (active && currentEffect != null) {
            currentEffectAgeSeconds += Math.max(0.0F, dt);
            currentEffect.onTick(effectContext, dt);
        } else if (!active) {
            currentEffectAgeSeconds = 0.0F;
            emissionAccumulators.clear();
        }

        refreshVirtualGeometrySnapshotForPhysics();
        updateParticles(dt);
        updatePulses(dt);
        wasActive = active;
    }

    private void updateScheduledEffectActions(float dt) {
        if (scheduledEffectActions.isEmpty()) return;
        List<ScheduledEffectAction> ready = new ArrayList<>();
        Iterator<ScheduledEffectAction> iterator = scheduledEffectActions.iterator();
        while (iterator.hasNext()) {
            ScheduledEffectAction action = iterator.next();
            action.remainingSeconds -= Math.max(0.0F, dt);
            if (action.remainingSeconds <= 0.0F) {
                iterator.remove();
                ready.add(action);
            }
        }
        for (ScheduledEffectAction action : ready) {
            SpawnOverrides previousOverrides = activeSpawnOverrides;
            activeSpawnOverrides = action.spawnOverrides;
            try {
                effectContext.withTarget(action.targetX, action.targetY, action.targetWidth, action.targetHeight,
                        action.action);
            } catch (RuntimeException ignored) {
                // A scheduled addon/JSON action must not break the render loop.
            } finally {
                activeSpawnOverrides = previousOverrides;
            }
        }
    }

    /** Draws an editor-only translucent block preview without mutating the scene. */
    public boolean renderBlockGhost(DrawContext context, SceneCellPos pos, BlockState state, float alpha) {
        return sceneRenderer.renderBlockGhost(context, spriteEngine.scene(), pos, state, alpha);
    }

    public void renderBehind(DrawContext context) {
        renderPulses(context, Layer.BACK);
        sceneRenderer.renderWorld(context, spriteEngine.scene());
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
        if (soundEnabled && particle.soundProfile != null
                && impactSpeed >= particle.soundProfile.minimumBounceSpeed()) {
            UiParticleSoundProfile.Cue collisionCue = particle.soundProfile.bounce();
            // Minecraft blocks expose a dedicated fall sound. Use it for a real
            // floor landing, while side/ceiling/sprite contacts retain the hit sound.
            if ((kind == CollisionKind.SCREEN || kind == CollisionKind.WORLD) && normalY < -0.5F && particle.soundProfile.fall() != null) {
                collisionCue = particle.soundProfile.fall();
            }
            if (collisionCue != null) {
                float impactVolume = clamp(impactSpeed / 120.0F, 0.32F, 1.0F);
                UiParticleSoundEngine.play(collisionCue, impactVolume, 1.0F);
            }
        }
        applyCollisionSpin(particle, normalX, normalY, impactSpeed);
        applyMaterialCollisionResponse(particle, kind, normalX, normalY, impactSpeed);
        fireParticleEvent(particle, particle.onBounce, kind, normalX, normalY, impactSpeed, 0.0F);
        ParticleInteractionType interactionType = switch (kind) {
            case SCREEN -> ParticleInteractionType.SCREEN_HIT;
            case BUTTON -> ParticleInteractionType.BUTTON_HIT;
            case TARGET -> ParticleInteractionType.TARGET_HIT;
            case PARTICLE -> ParticleInteractionType.SPRITE_HIT;
            default -> null;
        };
        if (interactionType != null && particle.interactive) {
            particle.lastInteractionNormalX = normalX;
            particle.lastInteractionNormalY = normalY;
            particle.lastInteractionImpactSpeed = impactSpeed;
            fireInteraction(particle, interactionType, -1);
        }
    }

    private void pollMinecraftPointerInput() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.mouse == null || client.getWindow() == null) return;
        if (client.getWindow().getWidth() <= 0 || client.getWindow().getHeight() <= 0) return;
        float x = (float) (client.mouse.getX() * screenWidth / (double) client.getWindow().getWidth());
        float y = (float) (client.mouse.getY() * screenHeight / (double) client.getWindow().getHeight());
        samplePointer(x, y);
        long handle = client.getWindow().getHandle();
        for (int button = 0; button < pointerButtons.length; button++) {
            boolean pressed = GLFW.glfwGetMouseButton(handle, button) == GLFW.GLFW_PRESS;
            if (pressed != pointerButtons[button]) {
                pointerButtons[button] = pressed;
                handlePointerButtonTransition(button, pressed);
            }
        }
        if (keyboardInteractionsEnabled) {
            Particle selected = findParticleById(selectedParticleId);
            if (selected != null && selected.keyboardInteraction) {
                for (int key : selected.keyboardKeys) {
                    if (key < 0 || key >= keyStates.length) continue;
                    boolean pressed = GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
                    if (pressed != keyStates[key]) {
                        keyStates[key] = pressed;
                        handleKeyTransition(key, pressed);
                    }
                }
            }
        }
    }

    private void samplePointer(float x, float y) {
        long now = System.nanoTime();
        if (!pointerInitialized) {
            pointerX = x;
            pointerY = y;
            pointerDeltaX = 0.0F;
            pointerDeltaY = 0.0F;
            pointerVelocityX = 0.0F;
            pointerVelocityY = 0.0F;
            pointerInitialized = true;
            pointerSampleNanos = now;
            return;
        }
        float dt = Math.max(0.001F, Math.min(0.10F, (now - pointerSampleNanos) / 1_000_000_000.0F));
        pointerDeltaX = x - pointerX;
        pointerDeltaY = y - pointerY;
        float rawVelocityX = pointerDeltaX / dt;
        float rawVelocityY = pointerDeltaY / dt;
        // Pointer samples can be extremely bursty on HUD/GLFW polling. A small
        // low-pass filter prevents one irregular frame from behaving like a
        // huge physical impulse while preserving intentional fast swipes.
        pointerVelocityX = pointerVelocityX * 0.80F + rawVelocityX * 0.20F;
        pointerVelocityY = pointerVelocityY * 0.80F + rawVelocityY * 0.20F;
        float pointerSpeed = (float) Math.sqrt(pointerVelocityX * pointerVelocityX + pointerVelocityY * pointerVelocityY);
        if (pointerSpeed > 2600.0F) {
            float clampScale = 2600.0F / pointerSpeed;
            pointerVelocityX *= clampScale;
            pointerVelocityY *= clampScale;
        }
        pointerX = x;
        pointerY = y;
        pointerSampleNanos = now;
    }

    private void updatePointerInteractionFrame(float dt) {
        if (!particleInteractionsEnabled || !pointerInitialized) return;
        Particle grabbed = findParticleById(grabbedParticleId);
        if (grabbedParticleId >= 0L && grabbed == null) {
            grabbedParticleId = -1L;
            grabbedMouseButton = -1;
        }
        if (selectedParticleId >= 0L && findParticleById(selectedParticleId) == null) {
            selectedParticleId = -1L;
        }

        List<Particle> snapshot = new ArrayList<>(particles);
        for (Particle particle : snapshot) {
            if (particle == null || particle.removeRequested || !particle.interactive) continue;
            boolean hovered = pointerHits(particle, pointerX, pointerY);
            if (hovered && particle.hoverResetAge) refreshInteractiveAge(particle);
            if ((particle.particleId == selectedParticleId || particle.dragging) && particle.selectionResetAge) {
                refreshInteractiveAge(particle);
            }
            if (hoverInteractionsEnabled && particle.hoverInteraction) {
                if (hovered && !particle.pointerHovered) fireInteraction(particle, ParticleInteractionType.HOVER_ENTER, -1);
                if (!hovered && particle.pointerHovered) fireInteraction(particle, ParticleInteractionType.HOVER_EXIT, -1);
                particle.pointerHovered = hovered;
                if (hovered) fireInteraction(particle, ParticleInteractionType.HOVER, -1);
            } else {
                particle.pointerHovered = hovered;
            }
        }

        // A swipe is one pointer gesture against one topmost swept body. This is
        // intentionally not evaluated independently for every particle beneath
        // the cursor, which prevents a fast mouse move from launching an entire
        // pile of sprites at once.
        if (swipeInteractionsEnabled) {
            float speed = (float) Math.sqrt(pointerVelocityX * pointerVelocityX + pointerVelocityY * pointerVelocityY);
            if (speed > 0.0F) {
                float startX = pointerX - pointerDeltaX;
                float startY = pointerY - pointerDeltaY;
                Particle swiped = findTopSwipeParticle(startX, startY, pointerX, pointerY);
                if (swiped != null && speed >= swiped.swipeMinSpeed) {
                    long now = System.nanoTime();
                    if (now - swiped.lastSwipeNanos >= swiped.swipeCooldownMs * 1_000_000L) {
                        applyPointerSwipeImpulse(swiped, startX, startY, pointerX, pointerY);
                        if (swiped.hoverResetAge) refreshInteractiveAge(swiped);
                        swiped.lastSwipeNanos = now;
                        fireInteraction(swiped, ParticleInteractionType.SWIPE, -1);
                    }
                }
            }
        }

        grabbed = findParticleById(grabbedParticleId);
        if (grabbed != null && grabbed.dragging) {
            if (grabbedMouseButton >= 0 && grabbedMouseButton < pointerButtons.length && pointerButtons[grabbedMouseButton]) {
                grabbed.prevX = grabbed.x;
                grabbed.prevY = grabbed.y;
                grabbed.x = pointerX + grabbedOffsetX;
                grabbed.y = pointerY + grabbedOffsetY;
                grabbed.sleeping = false;
                grabbed.stuckUntil = 0.0F;
                if (grabbed.hoverResetAge) refreshInteractiveAge(grabbed);
                fireInteraction(grabbed, ParticleInteractionType.DRAG, grabbedMouseButton);
            } else {
                releaseGrabbedParticle(grabbedMouseButton, true);
            }
        }

        if (keyboardInteractionsEnabled) {
            Particle selected = findParticleById(selectedParticleId);
            if (selected != null && selected.keyboardInteraction) {
                for (int key : selected.keyboardKeys) {
                    if (key >= 0 && key < keyStates.length && keyStates[key]) {
                        if (selected.selectionResetAge) refreshInteractiveAge(selected);
                        fireInteraction(selected, ParticleInteractionType.KEY_HELD, -1, key, 0.0F, 0.0F, null);
                    }
                }
            }
        }
    }

    private boolean handlePointerButtonTransition(int button, boolean pressed) {
        if (!particleInteractionsEnabled || !pointerInitialized) return false;
        if (pressed) {
            Particle hit = findTopInteractiveParticle(pointerX, pointerY);
            if (hit == null) {
                // Selected scene items can aim/use into empty screen space. Immediate
                // throwables launch on press; charged families remember this press
                // until the corresponding release below.
                if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    Particle selected = findParticleById(selectedParticleId);
                    if (isVirtualRegistryItem(selected)) {
                        boolean handled = spriteEngine.beginItemUse(selected.particleId, pointerX, pointerY);
                        if (!handled) {
                            SceneCellPos targetCell = spriteEngine.scene().projection().screenToCellAtDepth(
                                    pointerX, pointerY, selected.simulationLayerId);
                            handled = spriteEngine.useItemAtCell(selected.particleId, targetCell);
                        }
                        if (handled) {
                            if (selected.selectionResetAge) refreshInteractiveAge(selected);
                            return selected.consumePointerInput;
                        }
                    }
                }
                deselectParticle(true);
                return false;
            }
            // Route explicit scene gameplay before selection changes. This lets a
            // previously-selected item be used on the clicked block instead of the
            // block replacing that selection before ITEM_USE_ON_BLOCK is resolved.
            if (clickInteractionsEnabled && hit.clickInteraction) {
                queueVirtualPointerIntent(hit, button);
                if (hit.hoverResetAge) refreshInteractiveAge(hit);
                fireInteraction(hit, ParticleInteractionType.PRESS, button);
            }
            if (hit.selectable) selectParticle(hit);
            if (dragInteractionsEnabled && hit.dragInteraction && (hit.dragMouseButton < 0 || hit.dragMouseButton == button)) {
                releaseGrabbedParticle(-1, false);
                grabbedParticleId = hit.particleId;
                grabbedMouseButton = button;
                grabbedOffsetX = hit.x - pointerX;
                grabbedOffsetY = hit.y - pointerY;
                hit.dragging = true;
                hit.sleeping = false;
                hit.stuckUntil = 0.0F;
                if (hit.hoverResetAge) refreshInteractiveAge(hit);
                fireInteraction(hit, ParticleInteractionType.DRAG_START, button);
            }
            return hit.consumePointerInput;
        }
        if (grabbedParticleId >= 0L && grabbedMouseButton == button) {
            Particle grabbed = findParticleById(grabbedParticleId);
            boolean consumed = grabbed != null && grabbed.consumePointerInput;
            releaseGrabbedParticle(button, true);
            return consumed;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            Particle selected = findParticleById(selectedParticleId);
            if (isVirtualRegistryItem(selected)
                    && spriteEngine.releaseItemUse(selected.particleId, pointerX, pointerY)) {
                if (selected.selectionResetAge) refreshInteractiveAge(selected);
                return selected.consumePointerInput;
            }
        }
        Particle hit = findTopInteractiveParticle(pointerX, pointerY);
        if (hit != null && clickInteractionsEnabled && hit.clickInteraction) {
            if (hit.hoverResetAge) refreshInteractiveAge(hit);
            fireInteraction(hit, ParticleInteractionType.RELEASE, button);
            return hit.consumePointerInput;
        }
        return false;
    }

    private void queueVirtualPointerIntent(Particle particle, int button) {
        if (particle == null || particle.removeRequested || button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;

        if (isVirtualGridBlockCandidate(particle)) {
            SceneCellPos cell = spriteEngine.legacyBlockCell(particle.particleId);
            if (cell == null) cell = spriteEngine.sceneBlockAtScreen(particle.x, particle.y);

            // A selected item gets first chance at explicit right-click use. Charged/
            // projectile items begin their own use action even when the cursor is over
            // a block; tools and block-targeted items then fall through to USE_ON_BLOCK.
            // Merely touching/falling onto the block never enters this path.
            Particle selected = findParticleById(selectedParticleId);
            if (selected != null && selected != particle && isVirtualRegistryItem(selected)) {
                if (spriteEngine.beginItemUse(selected.particleId, pointerX, pointerY)) return;
                if (cell != null && spriteEngine.useItemOnBlock(selected.particleId, cell)) return;
            }

            // Otherwise this is an explicit bare/block use such as lever, gate,
            // chest lid, button, repeater setting, etc.
            if (cell != null && spriteEngine.useBlock(cell, pointerX, pointerY)) return;

            // Compatibility only for block families not owned by the scene. Once
            // migrated, an invalid action must not fall through to legacy authority.
            if (isVirtualGridBlock(particle) && (cell == null || !spriteEngine.ownsBlockGameplay(cell))) {
                particle.data.put("__koil_pending_block_use", "true");
            }
            return;
        }

        if (isVirtualRegistryItem(particle)) {
            if (spriteEngine.beginItemUse(particle.particleId, pointerX, pointerY)) return;
            // A migrated item family may legitimately do nothing for this target.
            // That is not permission to execute the obsolete gameplay path.
            if (!spriteEngine.ownsItemGameplay(particle.particleId)) {
                particle.data.put("__koil_pending_item_use", "true");
            }
        }
    }

    private boolean isVirtualRegistryItem(Particle particle) {
        if (particle == null || particle.itemIcon == null) return false;
        return particle.interactionTags.contains("registry_item")
                || "item".equalsIgnoreCase(particle.data.get("native_capability"));
    }

    private boolean handleKeyTransition(int key, boolean pressed) {
        if (!particleInteractionsEnabled || !keyboardInteractionsEnabled) return false;
        Particle selected = findParticleById(selectedParticleId);
        if (selected == null || !selected.interactive || !selected.keyboardInteraction || !selected.keyboardKeys.contains(key)) return false;
        if (selected.selectionResetAge) refreshInteractiveAge(selected);
        fireInteraction(selected, pressed ? ParticleInteractionType.KEY_PRESS : ParticleInteractionType.KEY_RELEASE,
                -1, key, 0.0F, 0.0F, null);
        return selected.consumePointerInput;
    }

    private void selectParticle(Particle particle) {
        if (particle == null || particle.removeRequested || !particle.selectable) return;
        if (selectedParticleId == particle.particleId) return;
        Particle previous = findParticleById(selectedParticleId);
        selectedParticleId = particle.particleId;
        for (int i = 0; i < keyStates.length; i++) keyStates[i] = false;
        if (previous != null) {
            previous.selected = false;
            fireInteraction(previous, ParticleInteractionType.DESELECT, -1);
        }
        particle.selected = true;
        if (particle.selectionResetAge) refreshInteractiveAge(particle);
        fireInteraction(particle, ParticleInteractionType.SELECT, -1);
    }

    private void deselectParticle(boolean fireEvent) {
        Particle selected = findParticleById(selectedParticleId);
        if (selected != null) {
            selected.selected = false;
            if (fireEvent) fireInteraction(selected, ParticleInteractionType.DESELECT, -1);
        }
        selectedParticleId = -1L;
        for (int i = 0; i < keyStates.length; i++) keyStates[i] = false;
    }

    private Particle findTopSwipeParticle(float startX, float startY, float endX, float endY) {
        for (int layerPass = 0; layerPass < 2; layerPass++) {
            Layer wanted = layerPass == 0 ? Layer.FRONT : Layer.BACK;
            for (int i = particles.size() - 1; i >= 0; i--) {
                Particle particle = particles.get(i);
                if (particle.layer != wanted || !particle.interactive || !particle.swipeInteraction
                        || particle.dragging || particle.removeRequested) continue;
                if (segmentHitsParticle(particle, startX, startY, endX, endY)) return particle;
            }
        }
        return null;
    }

    private boolean segmentHitsParticle(Particle particle, float x0, float y0, float x1, float y1) {
        ParticleBounds bounds = particleVisualBounds(particle, true);
        float halfW = Math.max(4.0F, bounds.halfWidth() * particle.interactionHitboxScale);
        float halfH = Math.max(4.0F, bounds.halfHeight() * particle.interactionHitboxScale);
        float minX = particle.x - halfW, maxX = particle.x + halfW;
        float minY = particle.y - halfH, maxY = particle.y + halfH;
        float dx = x1 - x0, dy = y1 - y0;
        float tMin = 0.0F, tMax = 1.0F;
        if (Math.abs(dx) < 0.00001F) {
            if (x0 < minX || x0 > maxX) return false;
        } else {
            float inv = 1.0F / dx;
            float t1 = (minX - x0) * inv, t2 = (maxX - x0) * inv;
            if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1); tMax = Math.min(tMax, t2);
            if (tMin > tMax) return false;
        }
        if (Math.abs(dy) < 0.00001F) {
            return y0 >= minY && y0 <= maxY;
        }
        float inv = 1.0F / dy;
        float t1 = (minY - y0) * inv, t2 = (maxY - y0) * inv;
        if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
        tMin = Math.max(tMin, t1); tMax = Math.min(tMax, t2);
        return tMin <= tMax;
    }

    private void applyPointerSwipeImpulse(Particle particle, float startX, float startY, float endX, float endY) {
        float speed = (float) Math.sqrt(pointerVelocityX * pointerVelocityX + pointerVelocityY * pointerVelocityY);
        float excessSpeed = Math.max(0.0F, speed - particle.swipeMinSpeed);
        if (excessSpeed <= 0.0F || speed <= 0.0001F) return;

        float mass = Math.max(MIN_INTERACTION_MASS, particle.mass);
        float massFactor = particle.swipeMassAware
                ? 1.0F / (float) Math.pow(mass, clamp(particle.swipeMassExponent, 0.0F, 2.0F))
                : 1.0F;
        // Never amplify a light object above the authored strength. Mass may only
        // reduce transfer here. This makes swipe interaction deliberate instead
        // of turning piles of light sprites into mouse-cursor pinballs.
        massFactor = clamp(massFactor, 0.035F, 1.0F);

        float pressSuppression = anyPointerButtonPressed() && !particle.dragging ? 0.010F : 1.0F;
        float impulseMagnitude = Math.min(particle.swipeMaxImpulse,
                excessSpeed * particle.swipeStrength * massFactor * pressSuppression);
        // Ease in immediately above the threshold so an ordinary fast cursor pass
        // produces almost no motion. Intentional hard flicks ramp up smoothly.
        float ramp = clamp(excessSpeed / 900.0F, 0.0F, 1.0F);
        impulseMagnitude *= ramp * ramp;
        if (impulseMagnitude < 0.55F) return;

        float nx = pointerVelocityX / speed;
        float ny = pointerVelocityY / speed;
        float impulseX = nx * impulseMagnitude;
        float impulseY = ny * impulseMagnitude;
        particle.vx += impulseX;
        particle.vy += impulseY;

        if (particle.swipeTorqueStrength > 0.0F) {
            // Torque uses the point on the swept mouse path nearest the body's center.
            // Grazing an edge spins the body; passing through its center mostly
            // translates it. The same mass attenuation applies to rotation.
            float pathX = endX - startX;
            float pathY = endY - startY;
            float pathLengthSq = pathX * pathX + pathY * pathY;
            float t = pathLengthSq <= 0.00001F ? 1.0F
                    : ((particle.x - startX) * pathX + (particle.y - startY) * pathY) / pathLengthSq;
            t = clamp(t, 0.0F, 1.0F);
            float hitX = startX + pathX * t;
            float hitY = startY + pathY * t;
            float rx = hitX - particle.x;
            float ry = hitY - particle.y;
            float torque = rx * impulseY - ry * impulseX;
            float angularDelta = (float) Math.toDegrees(torque / Math.max(0.02F, rotationalInertia(particle)))
                    * particle.swipeTorqueStrength * 0.010F;
            particle.angularVelocity = clamp(particle.angularVelocity + angularDelta,
                    -particle.swipeMaxAngularImpulse, particle.swipeMaxAngularImpulse);
        }
        particle.sleeping = false;
        particle.stuckUntil = 0.0F;
    }

    private boolean anyPointerButtonPressed() {
        for (boolean pressed : pointerButtons) if (pressed) return true;
        return false;
    }

    private void refreshInteractiveAge(Particle particle) {
        if (particle == null) return;
        float fullyVisibleAge = Math.max(0.05F, particle.lifetime * Math.max(0.02F, particle.fadeIn));
        float safeAge = Math.min(Math.max(0.05F, particle.lifetime * 0.25F), fullyVisibleAge);
        if (particle.age > safeAge) particle.age = safeAge;
    }

    private void releaseGrabbedParticle(int button, boolean fireEvents) {
        Particle grabbed = findParticleById(grabbedParticleId);
        if (grabbed != null) {
            if (fireEvents && isVirtualRegistryItem(grabbed)
                    && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                boolean handled = spriteEngine.releaseItemUse(grabbed.particleId, pointerX, pointerY);
                if (!handled) {
                    SceneCellPos targetBlock = spriteEngine.sceneBlockAtScreen(pointerX, pointerY);
                    if (targetBlock != null) {
                        handled = spriteEngine.useItemOnBlock(grabbed.particleId, targetBlock);
                    } else {
                        SceneCellPos targetCell = spriteEngine.scene().projection().screenToCellAtDepth(
                                pointerX, pointerY, grabbed.simulationLayerId);
                        handled = spriteEngine.useItemAtCell(grabbed.particleId, targetCell);
                    }
                }
                // Compatibility only for genuinely unmigrated item families.
                if (!handled && !spriteEngine.ownsItemGameplay(grabbed.particleId)) {
                    grabbed.data.put("__koil_pending_item_use", "true");
                }
            }
            grabbed.dragging = false;
            if ("true".equalsIgnoreCase(grabbed.data.get("__koil_kinematic"))) {
                grabbed.data.put("__koil_origin_x", String.valueOf(grabbed.x));
                grabbed.data.put("__koil_origin_y", String.valueOf(grabbed.y));
            }
            float mass = Math.max(MIN_INTERACTION_MASS, grabbed.mass);
            float transfer = grabbed.dragMassAware
                    ? 1.0F / (float) Math.pow(mass, clamp(grabbed.dragMassExponent, 0.0F, 2.0F))
                    : 1.0F;
            transfer = clamp(transfer, 0.08F, 2.0F);
            float releaseX = pointerVelocityX * grabbed.dragReleaseVelocityScale * transfer;
            float releaseY = pointerVelocityY * grabbed.dragReleaseVelocityScale * transfer;
            float releaseSpeed = (float) Math.sqrt(releaseX * releaseX + releaseY * releaseY);
            float maxRelease = 180.0F / (float) Math.pow(Math.max(1.0F, mass), 0.34F);
            if (releaseSpeed > maxRelease && releaseSpeed > 0.0001F) {
                float releaseScale = maxRelease / releaseSpeed;
                releaseX *= releaseScale;
                releaseY *= releaseScale;
            }
            grabbed.vx = releaseX;
            grabbed.vy = releaseY;
            if (fireEvents) {
                fireInteraction(grabbed, ParticleInteractionType.RELEASE, button);
                fireInteraction(grabbed, ParticleInteractionType.DRAG_END, button);
            }
        }
        grabbedParticleId = -1L;
        grabbedMouseButton = -1;
    }

    private Particle findParticleById(long particleId) {
        if (particleId < 0L) return null;
        for (Particle particle : particles) if (particle.particleId == particleId) return particle;
        for (Particle particle : pendingParticles) if (particle.particleId == particleId) return particle;
        return null;
    }

    private Particle findTopInteractiveParticle(float x, float y) {
        for (int layerPass = 0; layerPass < 2; layerPass++) {
            Layer wanted = layerPass == 0 ? Layer.FRONT : Layer.BACK;
            for (int i = particles.size() - 1; i >= 0; i--) {
                Particle particle = particles.get(i);
                if (particle.layer == wanted && particle.interactive && !particle.removeRequested && pointerHits(particle, x, y)) return particle;
            }
        }
        return null;
    }

    private Particle findTopSceneBlockParticle(float x, float y) {
        for (int layerPass = 0; layerPass < 2; layerPass++) {
            Layer wanted = layerPass == 0 ? Layer.FRONT : Layer.BACK;
            for (int i = particles.size() - 1; i >= 0; i--) {
                Particle particle = particles.get(i);
                if (particle.layer == wanted && !particle.removeRequested
                        && isVirtualGridBlockCandidate(particle) && pointerHits(particle, x, y)) return particle;
            }
        }
        return null;
    }

    private boolean pointerHits(Particle particle, float x, float y) {
        if (particle == null) return false;
        ParticleBounds bounds = particleVisualBounds(particle, true);
        float halfW = Math.max(4.0F, bounds.halfWidth() * particle.interactionHitboxScale);
        float halfH = Math.max(4.0F, bounds.halfHeight() * particle.interactionHitboxScale);
        return Math.abs(x - particle.x) <= halfW && Math.abs(y - particle.y) <= halfH;
    }

    private void fireInteraction(Particle particle, ParticleInteractionType type, int button) {
        fireInteraction(particle, type, button, -1, 0.0F, 0.0F, null);
    }

    private void fireInteraction(Particle particle, ParticleInteractionType type, int button, int key,
                                 float scrollX, float scrollY, Particle relatedParticle) {
        if (particle == null || particle.onInteract == null || particle.removeRequested) return;
        try {
            particle.onInteract.handle(new ParticleInteractionContext(particle, type, button, key, scrollX, scrollY, relatedParticle));
        } catch (RuntimeException ignored) {
            // Third-party or JSON interaction actions must never break the render/input loop.
        }
    }

    private float rotationalInertia(Particle particle) {
        if (particle == null) return 1.0F;
        ParticleBounds bounds = particleVisualBounds(particle, true);
        float width = Math.max(1.0F, bounds.halfWidth() * 2.0F);
        float height = Math.max(1.0F, bounds.halfHeight() * 2.0F);
        return Math.max(0.02F, Math.max(MIN_INTERACTION_MASS, particle.mass) * (width * width + height * height) / 12.0F);
    }

    private void applyCollisionSpin(Particle particle, float normalX, float normalY, float impactSpeed) {
        if (particle == null || impactSpeed <= 0.0F) return;
        float tangentVelocity = particle.vx * -normalY + particle.vy * normalX;
        if (Math.abs(tangentVelocity) < 0.5F) return;
        ParticleBounds bounds = particleVisualBounds(particle, true);
        float radius = Math.max(2.0F, Math.max(bounds.halfWidth(), bounds.halfHeight()));
        float spin = tangentVelocity / radius * 57.29578F * 0.32F;
        particle.angularVelocity = clamp(particle.angularVelocity + spin, -1080.0F, 1080.0F);
    }

    private float particleSignal(Particle particle, String name) {
        if (particle == null || name == null || name.isBlank()) return 0.0F;
        return particle.signals.getOrDefault(name.trim().toLowerCase(), 0.0F);
    }

    private void setParticleSignal(Particle particle, String name, float value) {
        if (particle == null || name == null || name.isBlank()) return;
        String key = name.trim().toLowerCase();
        float clamped = clamp(value, -1_000_000.0F, 1_000_000.0F);
        float previous = particle.signals.getOrDefault(key, 0.0F);
        if (Math.abs(previous - clamped) < 0.0001F) return;
        particle.signals.put(key, clamped);
        particle.lastSignalName = key;
        particle.lastSignalValue = clamped;
        if (particle.interactive) fireInteraction(particle, ParticleInteractionType.SIGNAL_CHANGED, -1);
    }

    private void applyForceToParticle(Particle particle, float fx, float fy) {
        if (particle == null || particle.removeRequested) return;
        float invMass = 1.0F / Math.max(MIN_INTERACTION_MASS, particle.mass);
        particle.vx += fx * invMass;
        particle.vy += fy * invMass;
        particle.sleeping = false;
        particle.stuckUntil = 0.0F;
    }

    private void addOrUpdateConstraint(Particle a, Particle b, float restLength, float stiffness,
                                       float damping, float breakDistance, float maxForce) {
        if (a == null || b == null || a == b) return;
        long low = Math.min(a.particleId, b.particleId);
        long high = Math.max(a.particleId, b.particleId);
        for (ParticleConstraint constraint : particleConstraints) {
            if (constraint.lowId == low && constraint.highId == high) {
                constraint.restLength = Math.max(0.0F, restLength);
                constraint.stiffness = Math.max(0.0F, stiffness);
                constraint.damping = Math.max(0.0F, damping);
                constraint.breakDistance = Math.max(constraint.restLength, breakDistance);
                constraint.maxForce = Math.max(1.0F, maxForce);
                return;
            }
        }
        particleConstraints.add(new ParticleConstraint(low, high, Math.max(0.0F, restLength),
                Math.max(0.0F, stiffness), Math.max(0.0F, damping),
                Math.max(Math.max(0.0F, restLength), breakDistance), Math.max(1.0F, maxForce)));
    }

    private void removeConstraint(long a, long b) {
        long low = Math.min(a, b), high = Math.max(a, b);
        particleConstraints.removeIf(c -> c.lowId == low && c.highId == high);
    }

    private void updateParticleConstraints(float dt) {
        if (!constraintPhysicsEnabled || particleConstraints.isEmpty() || dt <= 0.0F) return;
        Iterator<ParticleConstraint> iterator = particleConstraints.iterator();
        while (iterator.hasNext()) {
            ParticleConstraint c = iterator.next();
            Particle a = findParticleById(c.lowId);
            Particle b = findParticleById(c.highId);
            if (a == null || b == null || a.removeRequested || b.removeRequested) {
                iterator.remove();
                continue;
            }
            float dx = b.x - a.x, dy = b.y - a.y;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance < 0.001F) distance = 0.001F;
            if (c.breakDistance > 0.0F && distance > c.breakDistance) {
                iterator.remove();
                continue;
            }
            float nx = dx / distance, ny = dy / distance;
            float stretch = distance - c.restLength;
            float relative = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
            float force = clamp(stretch * c.stiffness + relative * c.damping, -c.maxForce, c.maxForce);
            float impulse = force * dt;
            float invA = 1.0F / Math.max(MIN_INTERACTION_MASS, a.mass);
            float invB = 1.0F / Math.max(MIN_INTERACTION_MASS, b.mass);
            a.vx += nx * impulse * invA;
            a.vy += ny * impulse * invA;
            b.vx -= nx * impulse * invB;
            b.vy -= ny * impulse * invB;
            a.sleeping = false;
            b.sleeping = false;
        }
    }

    private boolean relationLayerAllowed(Particle self, Particle other) {
        if (self.simulationLayerId == other.simulationLayerId) return true;
        return !self.relationSameLayerOnly && self.relationCrossLayer;
    }

    private boolean boundsOverlap(Particle a, Particle b, float scaleA, float scaleB) {
        ParticleBounds ba = particleVisualBounds(a, true);
        ParticleBounds bb = particleVisualBounds(b, true);
        float aw = Math.max(1.0F, ba.halfWidth() * scaleA);
        float ah = Math.max(1.0F, ba.halfHeight() * scaleA);
        float bw = Math.max(1.0F, bb.halfWidth() * scaleB);
        float bh = Math.max(1.0F, bb.halfHeight() * scaleB);
        return Math.abs(a.x - b.x) <= aw + bw && Math.abs(a.y - b.y) <= ah + bh;
    }

    private void fireRelation(Particle self, Particle other, ParticleRelationType type, float distance) {
        if (self == null || other == null || self.removeRequested || self.onRelation == null) return;
        try {
            self.onRelation.handle(new ParticleRelationContext(self, other, type, distance));
        } catch (RuntimeException ignored) {
            // Relationship rules are data-driven and must not break the simulation loop.
        }
    }

    private void updateRelationshipInteractions() {
        if (!relationInteractionsEnabled) return;
        List<Particle> relational = new ArrayList<>();
        List<Particle> allCandidates = new ArrayList<>();
        for (Particle p : particles) {
            if (p.removeRequested) continue;
            allCandidates.add(p);
            if (p.relationEnabled) relational.add(p);
        }
        Set<DirectedPairKey> newTouch = new HashSet<>();
        Set<DirectedPairKey> newOverlap = new HashSet<>();
        Set<DirectedPairKey> newSensor = new HashSet<>();

        for (Particle self : relational) {
            for (Particle other : allCandidates) {
                if (self == other || !relationLayerAllowed(self, other)) continue;
                float dx = other.x - self.x, dy = other.y - self.y;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                DirectedPairKey key = new DirectedPairKey(self.particleId, other.particleId);

                boolean touching = boundsOverlap(self, other, 1.0F, 1.0F);
                if (touching) {
                    newTouch.add(key);
                    if (!relationTouching.contains(key)) fireRelation(self, other, ParticleRelationType.TOUCH_ENTER, distance);
                    fireRelation(self, other, ParticleRelationType.TOUCH, distance);
                }

                boolean overlapping = boundsOverlap(self, other, Math.max(0.1F, self.relationOverlapScale), 1.0F);
                if (overlapping) {
                    newOverlap.add(key);
                    if (!relationOverlapping.contains(key)) fireRelation(self, other, ParticleRelationType.OVERLAP_ENTER, distance);
                    fireRelation(self, other, ParticleRelationType.OVERLAP, distance);
                }

                boolean sensing = self.relationSensorRadius > 0.0F && distance <= self.relationSensorRadius;
                if (sensing) {
                    newSensor.add(key);
                    if (!relationSensing.contains(key)) fireRelation(self, other, ParticleRelationType.SENSOR_ENTER, distance);
                    fireRelation(self, other, ParticleRelationType.SENSOR, distance);
                }
            }
        }

        for (DirectedPairKey key : new HashSet<>(relationTouching)) {
            if (!newTouch.contains(key)) {
                Particle self = findParticleById(key.selfId());
                Particle other = findParticleById(key.otherId());
                if (self != null && other != null) fireRelation(self, other, ParticleRelationType.TOUCH_EXIT,
                        distanceBetween(self, other));
            }
        }
        for (DirectedPairKey key : new HashSet<>(relationOverlapping)) {
            if (!newOverlap.contains(key)) {
                Particle self = findParticleById(key.selfId());
                Particle other = findParticleById(key.otherId());
                if (self != null && other != null) fireRelation(self, other, ParticleRelationType.OVERLAP_EXIT,
                        distanceBetween(self, other));
            }
        }
        for (DirectedPairKey key : new HashSet<>(relationSensing)) {
            if (!newSensor.contains(key)) {
                Particle self = findParticleById(key.selfId());
                Particle other = findParticleById(key.otherId());
                if (self != null && other != null) fireRelation(self, other, ParticleRelationType.SENSOR_EXIT,
                        distanceBetween(self, other));
            }
        }
        relationTouching.clear(); relationTouching.addAll(newTouch);
        relationOverlapping.clear(); relationOverlapping.addAll(newOverlap);
        relationSensing.clear(); relationSensing.addAll(newSensor);
    }

    private float distanceBetween(Particle a, Particle b) {
        float dx = b.x - a.x, dy = b.y - a.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Builds the block-only world geometry snapshot used by this frame's actor sweeps. */
    private void refreshVirtualGeometrySnapshotForPhysics() {
        if (!virtualBlockWorld.isEnabled() || particles.isEmpty()) {
            virtualGeometryWorld.clear();
            return;
        }
        virtualGeometryWorld.setCellPixels(virtualBlockWorld.getCellPixels());
        virtualGeometryWorld.rebuild(collectVirtualWorldNodes());
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
                        UiParticleSoundEngine.play(particle.soundProfile.expire());
                    }
                    if (particle.particleId == grabbedParticleId) {
                        grabbedParticleId = -1L;
                        grabbedMouseButton = -1;
                    }
                    if (particle.particleId == selectedParticleId) selectedParticleId = -1L;
                    iterator.remove();
                    continue;
                }

                if (isScenePhysicsActorProxy(particle)) {
                    // Scene-native actor physics already advanced this frame. The
                    // Particle exists only for rendering, input, sound callbacks and
                    // compatibility relations while the migration is in progress.
                    particle.sleeping = false;
                    fireParticleEvent(particle, particle.onTick, CollisionKind.TICK, 0.0F, 0.0F, 0.0F, dt);
                    continue;
                }

                if (isVirtualGridBlock(particle)) {
                    particle.rotation = 0.0F;
                    particle.angularVelocity = 0.0F;
                    particle.gravity = 0.0F;
                    particle.vx = 0.0F;
                    particle.vy = 0.0F;
                    particle.sleeping = false;
                    if (particle.dragging) continue;
                    // Registered block translation is owned by VirtualBlockWorld.
                    // Do not run free-body force, collision or angular integration.
                    fireParticleEvent(particle, particle.onTick, CollisionKind.TICK, 0.0F, 0.0F, 0.0F, dt);
                    continue;
                }

                if (particle.dragging) {
                    particle.vx = pointerVelocityX * particle.dragReleaseVelocityScale;
                    particle.vy = pointerVelocityY * particle.dragReleaseVelocityScale;
                    resolveParticleAgainstVirtualGeometry(particle);
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

                // World geometry resolves before screen/button collision so a fast
                // item crossing both a block and the screen floor hits the block at
                // the earliest swept contact instead of bouncing from the UI edge first.
                resolveParticleAgainstVirtualGeometry(particle);

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
        updateVirtualBlockWorld(dt);
        updateParticleConstraints(dt);
        updateParticleInteractions();
        correctVirtualWorldGeometryAfterParticlePhysics();
        updateRelationshipInteractions();
        // Capture legacy gameplay state changes (BlockState/ItemStack) without
        // surrendering actor transforms, then restore scene-owned render positions.
        syncSpriteSceneCompatibility();
        applySpriteScenePhysicsToProxies();
    }

    /**
     * Rev E compatibility bridge. The new KoilSpriteEngine becomes the stable
     * scene model while legacy Particle objects temporarily remain render/input
     * proxies. New systems must consume the scene runtime, not particle.data.
     */
    private void syncSpriteSceneCompatibility() {
        spriteEngine.beginLegacySync();
        for (Particle particle : particles) {
            if (particle == null || particle.removeRequested) continue;
            if (isVirtualGridBlockCandidate(particle) && particle.blockIcon != null) {
                boolean actorOwned = spriteEngine.actor(particle.particleId) instanceof FallingBlockActor;
                particle.data.put("__koil_scene_proxy", "true");
                particle.data.put("__koil_scene_authority", actorOwned ? "actor_world" : "block_grid");
                boolean falling = actorOwned || (particle.blockIcon instanceof FallingBlock
                        && "fall".equalsIgnoreCase(particle.data.getOrDefault("__koil_vw_grid_motion", "")));
                spriteEngine.syncLegacyBlock(
                        particle.particleId, particle.blockIcon, resolveSpriteBlockState(particle),
                        particle.x, particle.y, particle.simulationLayerId, falling, particle.vx, particle.vy,
                        particle.spawnOverrides == null ? Map.of() : particle.spawnOverrides.values());
                boolean fluidProxy = spriteEngine.isLegacyFluidProxy(particle.particleId);
                particle.data.put("__koil_scene_fluid_proxy", String.valueOf(fluidProxy));
                if (fluidProxy) particle.data.put("__koil_scene_authority", "fluid_grid");
                continue;
            }
            if (particle.itemIcon != null && (particle.interactionTags.contains("registry_item")
                    || "item".equalsIgnoreCase(particle.data.get("native_capability")))) {
                particle.data.put("__koil_scene_proxy", "true");
                particle.data.put("__koil_scene_authority", "actor_world");
                spriteEngine.syncLegacyItem(
                        particle.particleId, particleItemStack(particle), particle.x, particle.y,
                        particle.simulationLayerId, particle.vx, particle.vy, particle.rotation, particle.angularVelocity,
                        particle.dragging);
            }
        }
        spriteEngine.endLegacySync();
    }

    /** Copies authoritative scene transforms back into the temporary legacy visual proxies. */
    private void applySpriteScenePhysicsToProxies() {
        float alpha = spriteEngine.scene().clock().renderAlpha();
        for (Particle particle : particles) {
            if (particle == null || particle.removeRequested) continue;
            Actor actor = spriteEngine.actor(particle.particleId);
            if (actor != null) {
                particle.data.put("__koil_scene_proxy", "true");
                particle.data.put("__koil_scene_authority", "actor_world");
                boolean kinematic = actor.body().kinematic();
                particle.prevX = actor.previousX();
                particle.prevY = actor.previousY();
                particle.x = kinematic ? actor.x() : actor.interpolatedX(alpha);
                particle.y = kinematic ? actor.y() : actor.interpolatedY(alpha);
                particle.vx = actor.velocityX();
                particle.vy = actor.velocityY();
                particle.rotation = kinematic ? actor.rotation() : actor.interpolatedRotation(alpha);
                particle.angularVelocity = actor.angularVelocity();
                particle.simulationLayerId = actor.depth();
                if (actor instanceof ItemActor itemActor) {
                    writeParticleItemStack(particle, itemActor.stack());
                    particle.data.put("__koil_item_using", String.valueOf(itemActor.usingItem()));
                    particle.data.put("__koil_item_use_progress", String.valueOf(itemActor.useProgress()));
                } else if (actor instanceof FallingBlockActor fallingActor) {
                    particle.blockIcon = fallingActor.blockState().getBlock();
                    particle.data.put("__koil_block_state", virtualBlockStateSignature(fallingActor.blockState()));
                    particle.data.put("__koil_scene_falling", "true");
                    particle.data.put("__koil_vw_grid_motion", "fall");
                }
                continue;
            }

            if (particle.blockIcon != null && isVirtualGridBlockCandidate(particle)) {
                SceneCellPos cell = spriteEngine.legacyBlockCell(particle.particleId);
                if (cell != null) {
                    BlockState state = spriteEngine.scene().blocks().getBlockState(cell);
                    boolean fluidProxy = spriteEngine.isLegacyFluidProxy(particle.particleId);
                    particle.data.put("__koil_scene_proxy", "true");
                    particle.data.put("__koil_scene_fluid_proxy", String.valueOf(fluidProxy));
                    particle.data.put("__koil_scene_authority", fluidProxy ? "fluid_grid" : "block_grid");
                    particle.data.remove("__koil_scene_falling");
                    particle.data.put("__koil_vw_grid_motion", "");
                    particle.prevX = particle.x;
                    particle.prevY = particle.y;
                    particle.x = spriteEngine.scene().projection().cellCenterScreenX(cell);
                    particle.y = spriteEngine.scene().projection().cellCenterScreenY(cell);
                    particle.vx = 0.0F;
                    particle.vy = 0.0F;
                    particle.rotation = 0.0F;
                    particle.angularVelocity = 0.0F;
                    particle.simulationLayerId = spriteEngine.scene().projection().depthCoordinate(cell);

                    // A scene block is exactly one cell wide/tall. Registry block
                    // proxies used to retain the old 15 px particle footprint,
                    // which produced the visible 1 px seams between adjacent cells.
                    particle.visualPixels = spriteEngine.scene().projection().cellPixels();
                    particle.spriteScale = 1.0F;
                    particle.pixelSnap = PixelSnap.FULL;

                    if (!fluidProxy && state != null && !state.isAir()) {
                        particle.blockIcon = state.getBlock();
                        particle.data.put("__koil_block_state", virtualBlockStateSignature(state));
                    }
                }
            }
        }
    }

    private boolean isScenePhysicsActorProxy(Particle particle) {
        return particle != null && spriteEngine.actor(particle.particleId) != null;
    }

    /** Candidate check that ignores the actor-world authority guard used by isVirtualGridBlock. */
    private boolean isVirtualGridBlockCandidate(Particle particle) {
        if (particle == null || particle.blockIcon == null) return false;
        return particle.interactionTags.contains("registry_block")
                || particle.interactionTags.contains("grid_locked_block")
                || "block".equalsIgnoreCase(particle.data.get("native_capability"));
    }

    private void mirrorSceneItemStackToProxy(SceneEvent.ItemStackChanged change) {
        if (change == null) return;
        Particle proxy = findParticleById(change.actorId());
        if (proxy == null) return;
        ItemStack stack = change.stack();
        if (stack == null || stack.isEmpty()) {
            proxy.removeRequested = true;
        } else {
            writeParticleItemStack(proxy, stack);
        }
    }

    private void mirrorSceneBlockStateToProxy(SceneEvent.LegacyProxyWriteback writeback) {
        if (writeback == null || writeback.sourceId() < 0L) return;
        Particle proxy = findParticleById(writeback.sourceId());
        if (proxy == null) return;
        if (writeback.removeProxy() || writeback.state() == null || writeback.state().isAir()) {
            proxy.removeRequested = true;
            return;
        }
        proxy.blockIcon = writeback.state().getBlock();
        proxy.data.put("__koil_block_state", virtualBlockStateSignature(writeback.state()));
        proxy.data.put("__koil_scene_authority", "block_grid");
    }

    private void processSceneGameplayEvents() {
        if (!sceneSoundRequests.isEmpty()) {
            List<SceneEvent.SoundRequested> sounds = new ArrayList<>(sceneSoundRequests);
            sceneSoundRequests.clear();
            for (SceneEvent.SoundRequested request : sounds) {
                if (request == null || request.sound() == null || !soundEnabled) continue;
                UiParticleSoundEngine.play(request.sound(),
                        Math.max(0.0F, request.volume()), Math.max(0.05F, request.pitch()));
            }
        }

        if (!sceneSoundStopRequests.isEmpty()) {
            List<SceneEvent.SoundStopRequested> stops = new ArrayList<>(sceneSoundStopRequests);
            sceneSoundStopRequests.clear();
            for (SceneEvent.SoundStopRequested request : stops) {
                if (request == null || request.sound() == null) continue;
                UiParticleSoundEngine.stop(request.sound());
            }
        }

        if (!sceneParticleRequests.isEmpty()) {
            List<SceneEvent.ParticleRequested> requests = new ArrayList<>(sceneParticleRequests);
            sceneParticleRequests.clear();
            for (SceneEvent.ParticleRequested request : requests) spawnSceneParticleRequest(request);
        }

        if (!sceneItemStackChanges.isEmpty()) {
            List<SceneEvent.ItemStackChanged> changes = new ArrayList<>(sceneItemStackChanges);
            sceneItemStackChanges.clear();
            for (SceneEvent.ItemStackChanged change : changes) {
                if (change == null) continue;
                Particle proxy = findParticleById(change.actorId());
                if (proxy == null) continue;
                ItemStack stack = change.stack();
                if (stack == null || stack.isEmpty()) {
                    // Scene gameplay consumed the final item. Kill the compatibility
                    // proxy before the next sync so it cannot resurrect its actor.
                    proxy.removeRequested = true;
                    continue;
                }
                writeParticleItemStack(proxy, stack);
            }
        }
    }

    private void spawnSceneParticleRequest(SceneEvent.ParticleRequested request) {
        if (request == null || request.particleType() == null || request.count() <= 0) return;
        GameParticleRegistryBridge.registerAllAvailable();
        String effectId = request.particleType().getNamespace().toLowerCase(java.util.Locale.ROOT) + "."
                + request.particleType().getPath().toLowerCase(java.util.Locale.ROOT).replace('/', '.');
        UiParticleEffect effect = UiParticleRegistry.get(effectId);
        if (effect == null) return;

        int oldTargetX = targetX;
        int oldTargetY = targetY;
        int oldTargetWidth = targetWidth;
        int oldTargetHeight = targetHeight;
        float oldCenterX = centerX;
        float oldCenterY = centerY;
        SpawnOverrides oldOverrides = activeSpawnOverrides;
        try {
            float width = Math.max(1.0F, request.spreadX());
            float height = Math.max(1.0F, request.spreadY());
            targetX = Math.round(request.x() - width * 0.5F);
            targetY = Math.round(request.y() - height * 0.5F);
            targetWidth = Math.max(1, Math.round(width));
            targetHeight = Math.max(1, Math.round(height));
            centerX = request.x();
            centerY = request.y();
            Map<String, String> overrides = new LinkedHashMap<>();
            overrides.put("simulation_layer", String.valueOf(request.depth()));
            overrides.put("interactive", "false");
            overrides.put("hover_interaction", "false");
            overrides.put("click_interaction", "false");
            overrides.put("drag_interaction", "false");
            overrides.put("swipe_interaction", "false");
            overrides.put("keyboard_interaction", "false");
            overrides.put("relation_interaction", "false");
            overrides.put("selectable", "false");
            overrides.put("collide_buttons", "false");
            overrides.put("particle_collision", "false");
            Identifier particleTexture = null;
            if (request.particleItem() != null && !request.particleItem().isEmpty()) {
                particleTexture = UiItemTextureResolver.resolve(request.particleItem(), 0.0F, false);
            } else if (request.particleBlock() != null && !request.particleBlock().isAir()) {
                particleTexture = UiBlockFaceTextureResolver.resolve(request.particleBlock().getBlock(),
                        virtualBlockStateSignature(request.particleBlock()));
            }
            if (particleTexture != null) overrides.put("texture", particleTexture.toString());
            activeSpawnOverrides = new SpawnOverrides(overrides).withDefaultVisualScale(request.visualScale());
            for (int i = 0; i < Math.min(64, request.count()); i++) {
                spawnSequence++;
                effect.onBegin(effectContext);
            }
        } finally {
            activeSpawnOverrides = oldOverrides;
            targetX = oldTargetX;
            targetY = oldTargetY;
            targetWidth = oldTargetWidth;
            targetHeight = oldTargetHeight;
            centerX = oldCenterX;
            centerY = oldCenterY;
        }
    }

    private static float parseFloatOr(String value, float fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Float.parseFloat(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private void processScenePhysicsContacts() {
        if (scenePhysicsContacts.isEmpty()) return;
        List<SceneEvent.PhysicsContact> contacts = new ArrayList<>(scenePhysicsContacts);
        scenePhysicsContacts.clear();
        for (SceneEvent.PhysicsContact contact : contacts) {
            Particle particle = findParticleById(contact.actorId());
            if (particle == null || particle.removeRequested) continue;
            CollisionKind kind = contact.otherActorId() != null
                    ? CollisionKind.PARTICLE
                    : contact.blockCell() != null ? CollisionKind.WORLD : CollisionKind.SCREEN;
            notifyBounce(particle, kind, contact.normalX(), contact.normalY(), contact.impactSpeed());
        }
    }

    private void updateVirtualBlockWorld(float dt) {
        if (!virtualBlockWorld.isEnabled()) {
            virtualGeometryWorld.clear();
            virtualInteractionRouter.clear();
            return;
        }
        virtualBlockWorld.ensureCoverageReport();
        if (particles.isEmpty()) {
            virtualGeometryWorld.clear();
            virtualInteractionRouter.clear();
            return;
        }

        List<VirtualBlockWorld.SpriteNode> nodes = collectVirtualWorldNodes();
        if (nodes.isEmpty()) {
            virtualGeometryWorld.clear();
            virtualInteractionRouter.clear();
            return;
        }

        virtualEntityWorld.setCellPixels(virtualBlockWorld.getCellPixels());
        virtualEntityWorld.setServerContext(virtualServerContext);
        virtualBlockWorld.setServerContext(virtualServerContext);
        virtualBlockWorld.setEntityWorld(virtualEntityWorld);
        virtualGeometryWorld.setCellPixels(virtualBlockWorld.getCellPixels());

        // Geometry is resolved during each actor's motion step before UI-edge
        // collision. Rebuild here to capture newly spawned/snapped blocks before
        // the entity and block-world services consume the final actor positions.
        virtualGeometryWorld.rebuild(nodes);

        // 2. Entity-world indexing now observes the same positions that geometry
        // produced instead of a separate cell-boundary interpretation.
        List<VirtualEntityWorld.EntityNode> entityNodes = new ArrayList<>();
        for (VirtualBlockWorld.SpriteNode node : nodes) {
            if (node instanceof VirtualEntityWorld.EntityNode entityNode && node.entityNode()) entityNodes.add(entityNode);
        }
        virtualEntityWorld.tick(entityNodes, this::spawnVirtualWorldEntity, dt);

        // 3. Minecraft block mechanics own grid movement, fluids, redstone, hoppers,
        // block contacts, machines, damage, and other virtual-world services.
        virtualBlockWorld.tick(nodes, new VirtualBlockWorld.Host() {
            @Override
            public void spawnItem(ItemStack stack, float x, float y, int simulationLayer, float vx, float vy) {
                spawnVirtualWorldItem(stack, x, y, simulationLayer, vx, vy);
            }

            @Override
            public void spawnBlock(Block block, BlockState state, float x, float y, int simulationLayer) {
                spawnVirtualWorldBlock(block, state, x, y, simulationLayer);
            }

            @Override
            public void spawnEntity(String entityTypeId, float x, float y, int simulationLayer, Map<String, String> data) {
                spawnVirtualWorldEntity(entityTypeId, x, y, simulationLayer, data);
            }
        }, dt);

        // 4. Grid transactions can move or transform blocks. Rebuild immediately,
        // correct any actors a moving solid displaced, then route gameplay intent.
        virtualGeometryWorld.rebuild(nodes);
        for (VirtualBlockWorld.SpriteNode node : nodes) {
            if (!node.blockNode()) virtualGeometryWorld.depenetrate(node);
        }
        virtualInteractionRouter.update(nodes, virtualGeometryWorld);

        // Explicit use can change door/trapdoor/piston/etc BlockState geometry in the
        // same frame. Publish that new shape before later particle collision stages.
        virtualGeometryWorld.rebuild(nodes);
        for (VirtualBlockWorld.SpriteNode node : nodes) {
            if (!node.blockNode()) virtualGeometryWorld.depenetrate(node);
        }
    }

    private List<VirtualBlockWorld.SpriteNode> collectVirtualWorldNodes() {
        List<VirtualBlockWorld.SpriteNode> nodes = new ArrayList<>();
        for (Particle particle : particles) {
            VirtualBlockWorld.SpriteNode node = virtualWorldNode(particle);
            if (node != null) nodes.add(node);
        }
        return nodes;
    }

    private VirtualBlockWorld.SpriteNode virtualWorldNode(Particle particle) {
        if (particle == null || particle.removeRequested) return null;
        // Fluid proxies seed KoilFluidGrid and are deliberately invisible to the
        // legacy VirtualBlockWorld. This prevents a second water/lava runtime from
        // ticking the same source behind the scene-native fluid system.
        if (Boolean.parseBoolean(particle.data.getOrDefault("__koil_scene_fluid_proxy", "false"))) return null;
        if (spriteEngine.actor(particle.particleId) instanceof FallingBlockActor) return null;
        boolean blockNode = particle.blockIcon != null && (particle.interactionTags.contains("registry_block")
                || "block".equalsIgnoreCase(particle.data.get("native_capability")));
        boolean itemNode = particle.itemIcon != null && (particle.interactionTags.contains("registry_item")
                || "item".equalsIgnoreCase(particle.data.get("native_capability")));
        boolean gameplayContact = particleDataBoolean(particle, "world_gameplay_contact")
                || particleDataBoolean(particle, "__koil_world_gameplay_contact");
        boolean entityNode = itemNode || gameplayContact || particle.interactionTags.contains("virtual_entity")
                || particle.interactionTags.stream().anyMatch(tag -> tag.startsWith("entity:"));
        boolean worldCollision = itemNode || entityNode
                || particleDataBoolean(particle, "world_collision")
                || particleDataBoolean(particle, "__koil_world_collision_enabled");
        return blockNode || itemNode || entityNode || worldCollision
                ? new VirtualSpriteNode(particle, blockNode, itemNode, entityNode)
                : null;
    }

    private void resolveParticleAgainstVirtualGeometry(Particle particle) {
        if (!virtualBlockWorld.isEnabled() || particle == null || particle.removeRequested) return;
        VirtualBlockWorld.SpriteNode node = virtualWorldNode(particle);
        if (node != null && !node.blockNode()) virtualGeometryWorld.resolveMotion(node);
    }

    private void correctVirtualWorldGeometryAfterParticlePhysics() {
        if (!virtualBlockWorld.isEnabled() || particles.isEmpty()) return;
        for (VirtualBlockWorld.SpriteNode node : collectVirtualWorldNodes()) {
            if (!node.blockNode()) virtualGeometryWorld.depenetrate(node);
        }
    }

    private static boolean particleDataBoolean(Particle particle, String key) {
        if (particle == null || key == null) return false;
        String value = particle.data.get(key);
        return value != null && ("true".equalsIgnoreCase(value) || "1".equals(value)
                || "yes".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value));
    }

    private void spawnVirtualWorldItem(ItemStack stack, float x, float y, int simulationLayer, float vx, float vy) {
        if (stack == null || stack.isEmpty() || stack.getItem() == null) return;
        Item item = stack.getItem();
        Identifier registryId = Registries.ITEM.getId(item);
        if (registryId == null) return;
        ParticleBuilder builder = GameSpriteRegistryBridge.configureItemBuilder(
                new ParticleBuilder(Shape.PIXEL, x, y), registryId, item)
                .velocity(vx, vy)
                .simulationLayer(simulationLayer)
                .data("world_collision", true)
                .data("world_gameplay_contact", true)
                .data("__koil_stack_count", stack.getCount());
        try {
            NbtCompound nbt = new NbtCompound();
            stack.writeNbt(nbt);
            builder.data("__koil_stack_nbt", nbt.toString());
        } catch (RuntimeException ignored) { }
        effectContext.spawn(builder);
    }

    private void spawnVirtualWorldBlock(Block block, BlockState state, float x, float y, int simulationLayer) {
        if (block == null || block == net.minecraft.block.Blocks.AIR) return;
        Identifier registryId = Registries.BLOCK.getId(block);
        if (registryId == null) return;
        String signature = virtualBlockStateSignature(state == null ? block.getDefaultState() : state);
        ParticleBuilder builder = GameSpriteRegistryBridge.configureBlockBuilder(
                new ParticleBuilder(Shape.BLOCK_SHARD, x, y), registryId, block)
                .simulationLayer(simulationLayer)
                .rotation(0.0F)
                .angularVelocity(0.0F)
                .gravity(0.0F)
                .drag(1.0F)
                .restitution(0.0F)
                .particleCollision(false)
                .data("__koil_block_state", signature)
                .data("__koil_vw_locked", true)
                .data("__koil_vw_spawned", true);
        effectContext.spawn(builder);
    }

    private void spawnVirtualWorldEntity(String entityTypeId, float x, float y, int simulationLayer, Map<String, String> data) {
        if (entityTypeId == null || entityTypeId.isBlank()) return;
        String normalized = entityTypeId.trim().toLowerCase(java.util.Locale.ROOT);
        ParticleBuilder builder = new ParticleBuilder(Shape.PIXEL, x, y)
                .visualPixels(14.0F)
                .lifetime(300.0F)
                .layer(Layer.FRONT)
                .rotationPolicy(RotationPolicy.LOCKED)
                .rotation(0.0F)
                .angularVelocity(0.0F)
                .gravity(120.0F)
                .drag(0.985F)
                .restitution(0.0F)
                .particleCollision(true)
                .simulationLayer(simulationLayer)
                .interactive(true)
                .dragInteraction(true, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0.04F)
                .tag("virtual_entity")
                .tag("entity:" + normalized)
                .relationIdentity("entity." + normalized.replace(':', '.'), "virtual_entity")
                .data("__koil_vw_entity_type", normalized)
                .data("native_capability", "entity");
        if (data != null) {
            for (Map.Entry<String, String> entry : data.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) builder.data(entry.getKey(), entry.getValue());
            }
        }
        effectContext.spawn(builder);
    }

    private static String virtualBlockStateSignature(BlockState state) {
        if (state == null) return "";
        List<String> values = new ArrayList<>();
        for (Map.Entry<net.minecraft.state.property.Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
            values.add(entry.getKey().getName().toLowerCase(java.util.Locale.ROOT) + "="
                    + String.valueOf(entry.getValue()).toLowerCase(java.util.Locale.ROOT));
        }
        values.sort(String::compareTo);
        return String.join(",", values);
    }

    private static int parseParticleInt(String value, int fallback) {
        try { return value == null || value.isBlank() ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private ItemStack particleItemStack(Particle particle) {
        if (particle == null || particle.itemIcon == null) return ItemStack.EMPTY;
        String snbt = particle.data.get("__koil_stack_nbt");
        if (snbt != null && !snbt.isBlank()) {
            try {
                ItemStack parsed = ItemStack.fromNbt(StringNbtReader.parse(snbt));
                if (!parsed.isEmpty()) {
                    int count = Math.max(1, parseParticleInt(particle.data.get("__koil_stack_count"), parsed.getCount()));
                    parsed.setCount(count);
                    return parsed;
                }
            } catch (Exception ignored) { }
        }
        return new ItemStack(particle.itemIcon, Math.max(1, parseParticleInt(particle.data.get("__koil_stack_count"), 1)));
    }

    private void writeParticleItemStack(Particle particle, ItemStack stack) {
        if (particle == null || stack == null || stack.isEmpty()) return;
        particle.itemIcon = stack.getItem();
        particle.data.put("__koil_stack_count", String.valueOf(stack.getCount()));
        try { particle.data.put("__koil_stack_nbt", stack.writeNbt(new NbtCompound()).toString()); }
        catch (RuntimeException ignored) { }
    }

    private void consumeParticleItemStack(Particle particle, int amount) {
        if (particle == null || amount <= 0) return;
        ItemStack stack = particleItemStack(particle);
        if (stack.isEmpty()) return;
        stack.decrement(Math.min(amount, stack.getCount()));
        if (stack.isEmpty()) particle.removeRequested = true;
        else writeParticleItemStack(particle, stack);
    }

    private final class VirtualSpriteNode implements VirtualBlockWorld.SpriteNode, VirtualEntityWorld.EntityNode {
        private final Particle particle;
        private final boolean blockNode;
        private final boolean itemNode;
        private final boolean entityNode;

        private VirtualSpriteNode(Particle particle, boolean blockNode, boolean itemNode, boolean entityNode) {
            this.particle = particle;
            this.blockNode = blockNode;
            this.itemNode = itemNode;
            this.entityNode = entityNode;
        }

        @Override public long id() { return particle.particleId; }
        @Override public boolean blockNode() { return blockNode; }
        @Override public boolean itemNode() { return itemNode; }
        @Override public boolean entityNode() { return entityNode; }
        @Override public Block block() { return particle.blockIcon; }
        @Override public void block(Block block) { if (block != null) rebindParticleBlock(particle, block); }
        @Override public Item item() { return particle.itemIcon; }
        @Override public void item(Item item) { if (item != null) rebindParticleItem(particle, item); }
        @Override public float x() { return particle.x; }
        @Override public float y() { return particle.y; }
        @Override public float previousX() { return particle.prevX; }
        @Override public float previousY() { return particle.prevY; }
        @Override public float halfWidth() { return particleVisualBounds(particle, true).halfWidth(); }
        @Override public float halfHeight() { return particleVisualBounds(particle, true).halfHeight(); }
        @Override public float velocityX() { return particle.vx; }
        @Override public float velocityY() { return particle.vy; }
        @Override public float restitution() { return particle.restitution; }
        @Override public float surfaceFriction() { return particle.surfaceFriction; }
        @Override public void position(float x, float y) {
            particle.x = x;
            particle.y = y;
        }
        @Override public float rotation() { return particle.rotation; }
        @Override public void rotation(float degrees) { particle.rotation = degrees; }
        @Override public float angularVelocity() { return particle.angularVelocity; }
        @Override public void angularVelocity(float degreesPerSecond) { particle.angularVelocity = degreesPerSecond; }
        @Override public int simulationLayer() { return particle.simulationLayerId; }
        @Override public void simulationLayer(int simulationLayer) { particle.simulationLayerId = simulationLayer; }
        @Override public boolean dragging() { return particle.dragging; }
        @Override public boolean removed() { return particle.removeRequested; }
        @Override public void remove() { particle.removeRequested = true; }
        @Override public boolean hasTag(String tag) { return tag != null && particle.interactionTags.contains(tag.toLowerCase()); }
        @Override public String data(String key) { return key == null ? null : particle.data.get(key); }
        @Override public void data(String key, Object value) {
            if (key == null) return;
            if (value == null) particle.data.remove(key);
            else particle.data.put(key, String.valueOf(value));
        }
        @Override public float signal(String name) { return particleSignal(particle, name); }
        @Override public void signal(String name, float value) { setParticleSignal(particle, name, value); }
        @Override public String blockState() { return particle.data.getOrDefault("__koil_block_state", ""); }
        @Override public void blockState(String state) {
            if (state == null || state.isBlank()) particle.data.remove("__koil_block_state");
            else particle.data.put("__koil_block_state", state);
        }
        @Override public void velocity(float vx, float vy) { particle.vx = vx; particle.vy = vy; }
        @Override public void gravity(float gravity) { particle.gravity = gravity; }
        @Override public void playSound(SoundEvent event, float volume, float pitch) {
            if (event != null && soundEnabled) UiParticleSoundEngine.play(event, volume, pitch);
        }
    }

    private boolean isVirtualGridBlock(Particle particle) {
        if (!isVirtualGridBlockCandidate(particle)) return false;
        return !"actor_world".equalsIgnoreCase(particle.data.get("__koil_scene_authority"));
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
        for (UiColliderRegistry.Bounds bounds : UiColliderRegistry.collect(screen)) {
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
                int px = Math.round(pulse.x + (float) Math.cos(angle) * (radius + jitter));
                int py = Math.round(pulse.y + (float) Math.sin(angle) * (radius + jitter));
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
            // Static block/fluid Particle objects are now input/lifetime compatibility
            // proxies only. KoilSceneRenderer owns their visuals from the authoritative
            // BlockState/FluidState. Drawing the old flat proxy on top duplicated blocks
            // and, critically, could freeze a representative texture over animated
            // fire/fluid atlas sprites. Falling-block actors are intentionally excluded
            // because their compatibility particle remains the current moving visual.
            if (isVirtualGridBlockCandidate(particle)
                    && spriteEngine.actor(particle.particleId) == null
                    && (spriteEngine.legacyBlockCell(particle.particleId) != null
                    || spriteEngine.isLegacyFluidProxy(particle.particleId))) {
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
        // Scene-owned static blocks and fluids are rendered directly from KoilScene.
        // Their legacy particle objects exist only as command/input migration proxies.
        String sceneAuthority = particle.data.getOrDefault("__koil_scene_authority", "");
        if (Boolean.parseBoolean(particle.data.getOrDefault("__koil_scene_fluid_proxy", "false"))
                || "block_grid".equalsIgnoreCase(sceneAuthority)) return;
        if (particle.itemIcon != null && renderItemFaceParticle(context, particle, alpha, normalizedAge)) {
            return;
        }
        if (particle.blockIcon != null && renderBlockFaceParticle(context, particle, alpha, normalizedAge)) {
            return;
        }
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

    /** Draws a registered item as a flat resource-pack-aware texture. */
    private boolean renderItemFaceParticle(DrawContext context, Particle particle, int alpha, float normalizedAge) {
        ItemStack renderedStack = particleItemStack(particle);
        boolean using = Boolean.parseBoolean(particle.data.getOrDefault("__koil_item_using", "false"));
        float useProgress = parseFloatOr(particle.data.get("__koil_item_use_progress"), 0.0F);
        Identifier texture = renderedStack.isEmpty()
                ? UiItemTextureResolver.resolve(particle.itemIcon)
                : UiItemTextureResolver.resolve(renderedStack, useProgress, using);
        if ((texture == null || !textureExists(texture)) && particle.itemIcon instanceof net.minecraft.item.BlockItem blockItem) {
            // Block-items often delegate their item model to a block model and do
            // not expose a dedicated textures/item/<id>.png. Fall back to Koil's
            // state-aware block-face resolver so every registered BlockItem still
            // has a meaningful flat sprite instead of a missing-texture cross.
            texture = UiBlockFaceTextureResolver.resolve(blockItem.getBlock(), "");
        }
        if (texture == null || !textureExists(texture)) return false;
        UiParticleTextureMetadata.TextureInfo info = UiParticleTextureMetadata.resolve(texture);
        UiParticleTextureMetadata.FrameView frame = info.frameAt(
                particle.age, particle.animationSpeed, particle.animationLoop, particle.variant);
        renderTextureParticle(context, new TextureSelection(texture, false, info, frame), particle, alpha);
        return true;
    }

    /**
     * Draws a registered block as a flat representative face texture. The
     * resolver follows active resource-pack blockstate/model texture chains,
     * so this intentionally avoids Minecraft's 3D inventory item renderer.
     */
    private boolean renderBlockFaceParticle(DrawContext context, Particle particle, int alpha, float normalizedAge) {
        String stateSignature = resolveSpriteBlockState(particle);
        Identifier blockId = net.minecraft.registry.Registries.BLOCK.getId(particle.blockIcon);
        String path = blockId == null ? "" : blockId.getPath().toLowerCase();
        if ((path.endsWith("piston") || path.endsWith("sticky_piston"))
                && renderPistonComposite(context, particle, alpha, blockId)) {
            return true;
        }

        Identifier texture = UiBlockFaceTextureResolver.resolve(particle.blockIcon, stateSignature);
        if ((texture == null || !textureExists(texture)) && particle.blockIcon.asItem() != net.minecraft.item.Items.AIR) {
            texture = UiItemTextureResolver.resolve(particle.blockIcon.asItem());
        }
        if (texture == null || !textureExists(texture)) return false;
        UiParticleTextureMetadata.TextureInfo info = UiParticleTextureMetadata.resolve(texture);
        UiParticleTextureMetadata.FrameView frame = info.frameAt(
                particle.age, particle.animationSpeed, particle.animationLoop, particle.variant);

        int originalColor = particle.color;
        boolean tint = false;
        int effectiveAlpha = alpha;
        if (path.contains("redstone_wire")) {
            float power = clamp(particleSignal(particle, "power"), 0.0F, 15.0F);
            float strength = power / 15.0F;
            int red = Math.round(80.0F + 175.0F * strength);
            int green = Math.round(8.0F + 32.0F * strength);
            int blue = Math.round(8.0F + 24.0F * strength);
            particle.color = (red << 16) | (green << 8) | blue;
            tint = true;
        }
        renderTextureParticle(context, new TextureSelection(texture, tint, info, frame), particle, effectiveAlpha);
        particle.color = originalColor;

        return true;
    }

    private boolean renderPistonComposite(DrawContext context, Particle particle, int alpha, Identifier blockId) {
        if (blockId == null) return false;
        boolean vanilla = "minecraft".equals(blockId.getNamespace());
        boolean sticky = blockId.getPath().endsWith("sticky_piston");
        if (!vanilla) return false;

        Identifier head = new Identifier("minecraft", "textures/block/" + (sticky ? "piston_top_sticky" : "piston_top") + ".png");
        Identifier side = new Identifier("minecraft", "textures/block/piston_side.png");
        Identifier inner = new Identifier("minecraft", "textures/block/piston_inner.png");
        Identifier bottom = new Identifier("minecraft", "textures/block/piston_bottom.png");
        if (!textureExists(head)) return false;

        float pixels = Math.max(8.0F, resolveVisualPixels(particle) * particle.visualGrowth);
        String state = particle.data.get("__koil_block_state");
        boolean extended = Boolean.parseBoolean(blockStateValue(state, "extended", "false"));
        String facing = blockStateValue(state, "facing", "east");
        boolean horizontalProfile = facing.equals("east") || facing.equals("west");
        boolean forwardPositive = facing.equals("east") || facing.equals("south") || facing.equals("up");

        Identifier baseTexture = textureExists(side) ? side : head;
        Identifier rearTexture = textureExists(bottom) ? bottom : baseTexture;
        if (horizontalProfile) {
            float direction = forwardPositive ? 1.0F : -1.0F;
            drawLocalTexture(context, baseTexture, -pixels * 0.5F, -pixels * 0.5F, pixels, pixels, alpha);
            float headSize = pixels * 0.72F;
            float headCenterX = direction * (extended ? pixels * 1.08F : pixels * 0.30F);
            float baseFrontX = direction * pixels * 0.42F;
            float rodStartX = baseFrontX;
            float rodEndX = headCenterX - direction * headSize * 0.42F;
            float rodLeft = Math.min(rodStartX, rodEndX);
            float rodWidth = Math.abs(rodEndX - rodStartX);
            if (extended && rodWidth > 0.5F) {
                float rodHeight = Math.max(2.0F, pixels * 0.22F);
                Identifier rodTexture = textureExists(inner) ? inner : baseTexture;
                drawLocalTexture(context, rodTexture, rodLeft, -rodHeight * 0.5F, rodWidth, rodHeight, alpha);
            }
            drawLocalTexture(context, head, headCenterX - headSize * 0.5F, -headSize * 0.5F,
                    headSize, headSize, alpha);
            return true;
        }

        // Front/back facings are shown as a square face with an optional center
        // rod and pushed head depth cue. This reads far better in a flat 2D sandbox
        // than forcing every orientation into the same sideways silhouette.
        drawLocalTexture(context, rearTexture, -pixels * 0.5F, -pixels * 0.5F, pixels, pixels, alpha);
        float headInset = extended ? pixels * 0.08F : pixels * 0.18F;
        float headSize = pixels - headInset * 2.0F;
        if (extended) {
            float rodWidth = Math.max(2.0F, pixels * 0.24F);
            float rodHeight = Math.max(2.0F, pixels * 0.24F);
            Identifier rodTexture = textureExists(inner) ? inner : baseTexture;
            drawLocalTexture(context, rodTexture, -rodWidth * 0.5F, -pixels * 0.18F, rodWidth, pixels * 0.36F, alpha);
        }
        drawLocalTexture(context, head, -headSize * 0.5F, -headSize * 0.5F, headSize, headSize, alpha);
        return true;
    }

    private void drawLocalTexture(DrawContext context, Identifier texture, float x, float y, float width, float height, int alpha) {
        if (texture == null || !textureExists(texture)) return;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha / 255.0F);
        context.drawTexture(texture, Math.round(x), Math.round(y), Math.max(1, Math.round(width)), Math.max(1, Math.round(height)),
                0.0F, 0.0F, 16, 16, 16, 16);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private String resolveSpriteBlockState(Particle particle) {
        String authored = particle.data.get("__koil_block_state");
        if (authored == null || authored.isBlank()) authored = particle.data.get("block_state");
        String state = authored == null ? "" : authored;
        // Rendering never fabricates gameplay state. Free physical rotation may
        // influence displayed facing while loose, but power/lit/enabled/extended
        // and machine state come only from KoilVirtualBlockWorld.
        if (blockStateHasKey(state, "facing")
                && !Boolean.parseBoolean(particle.data.getOrDefault("__koil_vw_function_aligned", "false"))) {
            state = appendBlockState(state, "facing", facingForSpriteRotation(particle.rotation));
        }
        return state;
    }

    private boolean blockStateHasKey(String state, String key) {
        if (state == null || key == null) return false;
        String needle = key.toLowerCase() + "=";
        for (String part : state.toLowerCase().split("[,;]")) if (part.trim().startsWith(needle)) return true;
        return false;
    }

    private String blockStateValue(String state, String key, String fallback) {
        if (state == null || state.isBlank() || key == null || key.isBlank()) return fallback;
        for (String part : state.split("[,;]")) {
            String trimmed = part.trim();
            int equals = trimmed.indexOf('=');
            if (equals <= 0) continue;
            if (trimmed.substring(0, equals).trim().equalsIgnoreCase(key)) {
                String value = trimmed.substring(equals + 1).trim();
                return value.isEmpty() ? fallback : value;
            }
        }
        return fallback;
    }

    private String facingForSpriteRotation(float rotation) {
        int quadrant = Math.floorMod(Math.round(rotation / 90.0F), 4);
        return switch (quadrant) { case 0 -> "east"; case 1 -> "south"; case 2 -> "west"; default -> "north"; };
    }

    private String appendBlockState(String existing, String key, boolean value) {
        return appendBlockState(existing, key, String.valueOf(value));
    }

    private String appendBlockState(String existing, String key, String value) {
        String base = existing == null ? "" : existing.trim();
        List<String> parts = new ArrayList<>();
        boolean replaced = false;
        if (!base.isBlank()) {
            for (String part : base.split("[,;]")) {
                String trimmed = part.trim();
                if (trimmed.isBlank()) continue;
                int equals = trimmed.indexOf('=');
                if (equals > 0 && trimmed.substring(0, equals).trim().equalsIgnoreCase(key)) {
                    parts.add(key + "=" + value);
                    replaced = true;
                } else {
                    parts.add(trimmed);
                }
            }
        }
        if (!replaced) parts.add(key + "=" + value);
        return String.join(",", parts);
    }

    private TextureSelection resolveTextureSelection(Particle particle, float normalizedAge) {
        if (particle.forcePrimitive && particle.customTexture == null
                && (particle.textureSequence == null || particle.textureSequence.isEmpty())) {
            return null;
        }
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
            VanillaParticleSprites.SpriteSet set = particle.spriteSet != null
                    ? particle.spriteSet
                    : (particle.visualFamily != null && particle.visualFamily != VisualFamily.AUTO
                    ? VanillaParticleSprites.forVisualFamily(particle.visualFamily)
                    : VanillaParticleSprites.forShape(particle.shape));
            if (set != null) {
                texture = set.texture(normalizedAge, particle.variant, particle.age * particle.animationSpeed);
                tintable = set.tintable() && particle.textureTint;
            }
        }
        if (texture == null) return null;

        UiParticleTextureMetadata.TextureInfo info = UiParticleTextureMetadata.resolve(texture);
        UiParticleTextureMetadata.FrameView frame = info.frameAt(
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

        UiParticleTextureMetadata.FrameView frame = selection.frame();
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
            UiParticleTextureMetadata.TextureInfo info,
            UiParticleTextureMetadata.FrameView frame
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
                    float tx = -ny, ty = nx;
                    float relativeTangent = (b.vx - a.vx) * tx + (b.vy - a.vy) * ty;
                    float radiusA = Math.max(2.0F, Math.max(boundsA.halfWidth(), boundsA.halfHeight()));
                    float radiusB = Math.max(2.0F, Math.max(boundsB.halfWidth(), boundsB.halfHeight()));
                    a.angularVelocity = clamp(a.angularVelocity - relativeTangent / radiusA * 11.0F * (weightShare(b.mass, a.mass)), -1080.0F, 1080.0F);
                    b.angularVelocity = clamp(b.angularVelocity + relativeTangent / radiusB * 11.0F * (weightShare(a.mass, b.mass)), -1080.0F, 1080.0F);
                    float impact = Math.abs(relativeNormal);
                    notifyBounce(a, CollisionKind.PARTICLE, -nx, -ny, impact);
                    notifyBounce(b, CollisionKind.PARTICLE, nx, ny, impact);
                }
            }
        }
        particles.removeIf(p -> p.removeRequested);
    }

    private float weightShare(float otherMass, float selfMass) {
        float a = Math.max(MIN_INTERACTION_MASS, otherMass);
        float b = Math.max(MIN_INTERACTION_MASS, selfMass);
        return clamp(a / (a + b), 0.05F, 0.95F);
    }

    private boolean canEnterParticleContact(Particle particle) {
        return particle != null
                && !particle.removeRequested
                && !isScenePhysicsActorProxy(particle)
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
        if (particle.material == UiParticleMaterials.Material.WATER && impactSpeed > 28.0F
                && particle.bounceCount <= 2 && particles.size() + pendingParticles.size() < maxParticles - 4) {
            for (int i = 0; i < 3; i++) {
                effectContext.spawn(effectContext.particle(Shape.BUBBLE, particle.x, particle.y)
                        .sprite(VanillaParticleSprites.SpriteSet.SPLASH)
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
        VanillaParticleSprites.SpriteSet set = particle.spriteSet != null
                ? particle.spriteSet
                : (particle.visualFamily != null && particle.visualFamily != VisualFamily.AUTO
                ? VanillaParticleSprites.forVisualFamily(particle.visualFamily)
                : VanillaParticleSprites.forShape(particle.shape));
        float base = set == null ? 6.0F : set.recommendedPixels();
        float authored = clamp(particle.size, 0.55F, 2.5F);
        return clamp(base * (0.72F + authored * 0.34F) * particle.spriteScale, 2.0F, 20.0F);
    }

    private RotationPolicy resolveRotationPolicy(Particle particle) {
        if (particle.rotationPolicy != null) return particle.rotationPolicy;
        VanillaParticleSprites.SpriteSet set = particle.spriteSet != null
                ? particle.spriteSet
                : (particle.visualFamily != null && particle.visualFamily != VisualFamily.AUTO
                ? VanillaParticleSprites.forVisualFamily(particle.visualFamily)
                : VanillaParticleSprites.forShape(particle.shape));
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
        MinecraftClient debugClient = MinecraftClient.getInstance();
        if (debugClient != null && debugClient.textRenderer != null) {
            SceneDiagnostics scene = spriteEngine.diagnostics();
            String sceneLine = "scene=" + scene.runtimeMode()
                    + " projection=" + scene.projection()
                    + " tick=" + scene.minecraftTick()
                    + " physics=" + scene.physicsStep()
                    + " blocks=" + scene.blocks()
                    + " fluids=" + scene.fluids()
                    + " sources=" + scene.fluidSources()
                    + " actors=" + scene.actors()
                    + " proxies=" + (scene.legacyBlockProxies() + scene.legacyFluidProxies() + scene.legacyActorProxies())
                    + " colliders=" + scene.staticColliders()
                    + " contacts=" + scene.physicsContacts()
                    + " sleeping=" + scene.sleepingActors()
                    + " falling=" + scene.fallingActors();
            context.drawText(debugClient.textRenderer, sceneLine, 4, 4, 0xFFFFFFFF, true);
        }
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
            if (sprite != null) try { p.spriteSet = VanillaParticleSprites.SpriteSet.valueOf(normalizeEnum(sprite)); } catch (Exception ignored) { }
            String material = get("material");
            if (material != null) {
                try {
                    p.material = UiParticleMaterials.Material.valueOf(normalizeEnum(material));
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
            p.interactive = boolValue("interactive", p.interactive);
            p.hoverInteraction = boolValue("hover_interaction", p.hoverInteraction);
            p.clickInteraction = boolValue("click_interaction", p.clickInteraction);
            p.consumePointerInput = boolValue("consume_pointer_input", p.consumePointerInput);
            p.hoverResetAge = boolValue("hover_reset_age", p.hoverResetAge);
            p.dragInteraction = boolValue("drag_interaction", p.dragInteraction);
            p.swipeInteraction = boolValue("swipe_interaction", p.swipeInteraction);
            p.interactionHitboxScale = clamp(floatValue("interaction_hitbox_scale", p.interactionHitboxScale), 0.5F, 4.0F);
            p.dragReleaseVelocityScale = clamp(floatValue("drag_release_velocity", p.dragReleaseVelocityScale), 0.0F, 3.0F);
            p.dragMouseButton = intValue("drag_mouse_button", p.dragMouseButton);
            p.swipeMinSpeed = Math.max(1.0F, floatValue("swipe_min_speed", p.swipeMinSpeed));
            p.swipeStrength = Math.max(0.0F, floatValue("swipe_strength", p.swipeStrength));
            p.swipeMaxImpulse = Math.max(1.0F, floatValue("swipe_max_impulse", p.swipeMaxImpulse));
            p.swipeCooldownMs = Math.max(0L, intValue("swipe_cooldown_ms", (int) Math.min(Integer.MAX_VALUE, p.swipeCooldownMs)));
            p.dragMassAware = boolValue("drag_mass_aware", p.dragMassAware);
            p.dragMassExponent = clamp(floatValue("drag_mass_exponent", p.dragMassExponent), 0.0F, 2.0F);
            p.swipeMassAware = boolValue("swipe_mass_aware", p.swipeMassAware);
            p.swipeMassExponent = clamp(floatValue("swipe_mass_exponent", p.swipeMassExponent), 0.0F, 2.0F);
            p.swipeTorqueStrength = Math.max(0.0F, floatValue("swipe_torque_strength", p.swipeTorqueStrength));
            p.swipeMaxAngularImpulse = Math.max(1.0F, floatValue("swipe_max_angular_impulse", p.swipeMaxAngularImpulse));
            p.selectable = boolValue("selectable", p.selectable);
            p.selectionResetAge = boolValue("selection_reset_age", p.selectionResetAge);
            p.scrollRotateInteraction = boolValue("scroll_rotate", boolValue("scroll_rotation", p.scrollRotateInteraction));
            p.scrollRequireGrabbed = boolValue("scroll_require_grabbed", p.scrollRequireGrabbed);
            p.scrollDegreesPerStep = floatValue("scroll_degrees_per_step", p.scrollDegreesPerStep);
            p.scrollInertial = boolValue("scroll_inertial", p.scrollInertial);
            p.scrollAngularImpulse = Math.max(0.0F, floatValue("scroll_angular_impulse", p.scrollAngularImpulse));
            p.scrollSnapDegrees = Math.max(0.0F, floatValue("scroll_snap_degrees", p.scrollSnapDegrees));
            p.scrollMaxAngularVelocity = Math.max(1.0F, floatValue("scroll_max_angular_velocity", p.scrollMaxAngularVelocity));
            p.keyboardInteraction = boolValue("keyboard_interaction", p.keyboardInteraction);
            p.relationEnabled = boolValue("relation_interaction", p.relationEnabled);
            p.relationSensorRadius = Math.max(0.0F, floatValue("relation_sensor_radius", p.relationSensorRadius));
            p.relationOverlapScale = Math.max(0.1F, floatValue("relation_overlap_scale", p.relationOverlapScale));
            p.relationSameLayerOnly = boolValue("relation_same_layer_only", p.relationSameLayerOnly);
            p.relationCrossLayer = boolValue("relation_cross_layer", p.relationCrossLayer);
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
            UiParticleSoundProfile runtimeSound = runtimeSoundProfile();
            if (runtimeSound != null) p.soundProfile = runtimeSound;
            String tint = get("color");
            if (tint != null) p.color = parseColor(tint, p.color);
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getKey().startsWith("data.")) p.data.put(entry.getKey().substring(5), entry.getValue());
                if (entry.getKey().startsWith("tag.") && boolValue(entry.getKey(), true)) p.interactionTags.add(entry.getKey().substring(4));
            }
        }
        private UiParticleSoundProfile runtimeSoundProfile() {
            UiParticleSoundProfile.Cue spawn = cue("spawn_sound", "spawn_volume", "spawn_pitch", "spawn_jitter", "spawn_cooldown_ms");
            UiParticleSoundProfile.Cue bounce = cue("bounce_sound", "bounce_volume", "bounce_pitch", "bounce_jitter", "bounce_cooldown_ms");
            UiParticleSoundProfile.Cue breakCue = cue("break_sound", "break_volume", "break_pitch", "break_jitter", "break_cooldown_ms");
            UiParticleSoundProfile.Cue expire = cue("expire_sound", "expire_volume", "expire_pitch", "expire_jitter", "expire_cooldown_ms");
            if (spawn == null && bounce == null && breakCue == null && expire == null) return null;
            return UiParticleSoundProfile.builder()
                    .spawn(spawn).bounce(bounce).breakCue(breakCue).expire(expire)
                    .minimumBounceSpeed(floatValue("minimum_bounce_sound_speed", 16.0F)).build();
        }
        private UiParticleSoundProfile.Cue cue(String soundKey, String volumeKey, String pitchKey, String jitterKey, String cooldownKey) {
            String raw = get(soundKey);
            if (raw == null || raw.isBlank()) return null;
            try {
                Identifier id = raw.contains(":") ? new Identifier(raw) : new Identifier("minecraft", raw);
                return new UiParticleSoundProfile.Cue(SoundEvent.of(id),
                        floatValue(volumeKey, 0.22F), floatValue(pitchKey, 1.0F), floatValue(jitterKey, 0.06F),
                        Math.max(0, intValue(cooldownKey, 70)));
            } catch (RuntimeException ignored) { return null; }
        }

        private static void applyRuntimeMaterial(Particle p, UiParticleMaterials.Material material) {
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
        WORLD,
        PARTICLE,
        EXPIRE
    }

    @FunctionalInterface
    public interface ParticleEventHandler {
        void handle(ParticleEventContext event);
    }

    public enum ParticleInteractionType {
        HOVER_ENTER,
        HOVER,
        HOVER_EXIT,
        PRESS,
        RELEASE,
        DRAG_START,
        DRAG,
        DRAG_END,
        SWIPE,
        SELECT,
        DESELECT,
        SCROLL,
        KEY_PRESS,
        KEY_RELEASE,
        KEY_HELD,
        SCREEN_HIT,
        BUTTON_HIT,
        TARGET_HIT,
        SPRITE_HIT,
        SIGNAL_CHANGED
    }

    public enum ParticleRelationType {
        TOUCH_ENTER,
        TOUCH,
        TOUCH_EXIT,
        OVERLAP_ENTER,
        OVERLAP,
        OVERLAP_EXIT,
        SENSOR_ENTER,
        SENSOR,
        SENSOR_EXIT
    }

    @FunctionalInterface
    public interface ParticleInteractionHandler {
        void handle(ParticleInteractionContext event);
    }

    @FunctionalInterface
    public interface ParticleRelationHandler {
        void handle(ParticleRelationContext event);
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
        /** Final per-particle visual scale after JSON and trigger overrides. */
        public float visualScale() { return Math.max(0.05F, particle.spriteScale); }
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
        public Block blockIcon() { return particle.blockIcon; }
        public Item itemIcon() { return particle.itemIcon; }
        public String data(String key) { return key == null ? null : particle.data.get(key); }
        public void data(String key, Object value) { if (key != null && value != null) particle.data.put(key, String.valueOf(value)); }
        public float signal(String name) { return particleSignal(particle, name); }
        public void signal(String name, float value) { setParticleSignal(particle, name, value); }
        public boolean dragging() { return particle.dragging; }
        public boolean selected() { return particle.selected; }
        public void temperature(float value) { particle.temperature = value; }
        public void charge(float value) { particle.charge = value; }
        public void position(float x, float y) { particle.x = x; particle.y = y; }
        public void rotation(float degrees) { particle.rotation = degrees; }
        public void velocity(float vx, float vy) { particle.vx = vx; particle.vy = vy; }
        public void addVelocity(float vx, float vy) { particle.vx += vx; particle.vy += vy; }
        public void gravity(float value) { particle.gravity = value; }
        public void drag(float value) { particle.drag = clamp(value, 0.0F, 1.0F); }
        public void restitution(float value) { particle.restitution = clamp(value, 0.0F, 1.25F); }
        public void behavior(Behavior value) { particle.behavior = value == null ? Behavior.BALLISTIC : value; }
        public void material(UiParticleMaterials.Material value) {
            particle.material = value == null ? UiParticleMaterials.Material.DEFAULT : value;
            SpawnOverrides.applyRuntimeMaterial(particle, particle.material);
        }
        public void windZone(float x, float y, float width, float height, float forceX, float forceY, float lifetime) {
            effectContext.windZone(x, y, width, height, forceX, forceY, lifetime);
        }
        public void vortexWell(float x, float y, float radius, float spin, float pull, float lifetime) {
            effectContext.vortexWell(x, y, radius, spin, pull, lifetime);
        }

        public void kill() { particle.removeRequested = true; }
        public ParticleBuilder particle(Shape shape) {
            return effectContext.particle(shape, particle.x, particle.y).spriteScale(particle.spriteScale);
        }
        public ParticleBuilder particle(Shape shape, float x, float y) {
            return effectContext.particle(shape, x, y).spriteScale(particle.spriteScale);
        }
        public void spawn(ParticleBuilder builder) { effectContext.spawn(builder); }
        public void pulse(float duration, int color, float scale, Layer layer) {
            effectContext.pulseAt(particle.x, particle.y, 0.0F, duration, color, scale, layer);
        }
        public float random(float min, float max) { return effectContext.random(min, max); }
        public int randomInt(int bound) { return effectContext.randomInt(bound); }
        public boolean chance(float chance) { return effectContext.chance(chance); }
        public int pickColor(int... colors) { return effectContext.pickColor(colors); }
        public void playSound(SoundEvent sound, float volume, float pitch) { if (soundEnabled) UiParticleSoundEngine.play(sound, volume, pitch); }
        public void playSound(UiParticleSoundProfile.Cue cue) { if (soundEnabled) UiParticleSoundEngine.play(cue); }
        public void playBlockHit(Block block) {
            if (block == null || !soundEnabled) return;
            UiParticleSoundProfile profile = UiParticleSoundProfile.forBouncingBlock(block);
            UiParticleSoundEngine.play(profile.bounce());
        }
        public void playBlockBreak(Block block) {
            if (block == null || !soundEnabled) return;
            UiParticleSoundProfile profile = UiParticleSoundProfile.forBlock(block, false);
            UiParticleSoundEngine.play(profile.breakCue());
        }
    }


    private void rebindParticleBlock(Particle particle, Block block) {
        if (particle == null || block == null) return;
        String previousTags = particle.data.get("__koil_profile_tags");
        if (previousTags != null && !previousTags.isBlank()) {
            for (String tag : previousTags.split("\\|")) if (!tag.isBlank()) particle.interactionTags.remove(tag.toLowerCase());
        }
        particle.interactionTags.removeIf(tag -> tag.startsWith("block:"));
        particle.blockIcon = block;
        particle.itemIcon = null;
        Identifier id = Registries.BLOCK.getId(block);
        GameSpriteBehavior.BlockProfile profile = GameSpriteBehavior.blockProfile(block);
        particle.material = profile.material();
        SpawnOverrides.applyRuntimeMaterial(particle, profile.material());
        particle.mass = Math.max(0.001F, profile.mass());
        particle.solidity = clamp(profile.solidity(), 0.0F, 1.0F);
        particle.gravity = 0.0F;
        particle.drag = 1.0F;
        particle.restitution = 0.0F;
        particle.surfaceFriction = profile.surfaceFriction();
        particle.soundProfile = GameSpriteSoundResolver.blockProfile(block);
        particle.interactionRole = profile.role();
        particle.relationSensorRadius = profile.sensorRadius();
        particle.interactionTags.add("registry_sprite");
        particle.interactionTags.add("registry_block");
        particle.interactionTags.add("block");
        particle.interactionTags.remove("scroll_rotatable");
        particle.interactionTags.remove("wheel_rotatable");
        particle.interactionTags.add("grid_locked_block");
        particle.interactionTags.add(profile.role().toLowerCase());
        for (String tag : profile.tags()) {
            if (tag == null) continue;
            String normalizedTag = tag.toLowerCase();
            if ("scroll_rotatable".equals(normalizedTag) || "wheel_rotatable".equals(normalizedTag)) continue;
            particle.interactionTags.add(normalizedTag);
        }
        particle.interactionTags.remove("scroll_rotatable");
        particle.interactionTags.remove("wheel_rotatable");
        if (id != null) {
            particle.interactionTags.add("block:" + id);
            particle.data.put("registry_id", id.toString());
        }
        particle.data.put("native_capability", "block");
        particle.data.put("__koil_vw_grid_locked", "true");
        particle.data.put("__koil_vw_locked", "true");
        particle.rotation = 0.0F;
        particle.angularVelocity = 0.0F;
        particle.rotationPolicy = RotationPolicy.LOCKED;
        particle.scrollRotateInteraction = false;
        particle.swipeInteraction = false;
        particle.keyboardInteraction = false;
        particle.particleCollision = false;
        particle.contactMode = ParticleContactMode.IGNORE;
        particle.data.put("__koil_profile_tags", String.join("|", profile.tags()));
        BlockState initialState = block.getDefaultState();
        // There is no player placement context in a detached UI scene. A vanilla
        // stair's registry default faces into the hidden Z axis, which makes its
        // step profile ambiguous in Koil's default XY side view. Use the visible
        // screen-right Minecraft direction as the detached placement default;
        // authored state and wheel orientation can still select every native state.
        if (block instanceof net.minecraft.block.StairsBlock
                && initialState.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING)) {
            net.minecraft.util.math.Direction visibleFacing = spriteEngine.scene().projection().screenRightDirection();
            if (visibleFacing.getAxis() != net.minecraft.util.math.Direction.Axis.Y) {
                initialState = initialState.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, visibleFacing);
            }
        }
        particle.data.put("__koil_block_state", virtualBlockStateSignature(initialState));
        particle.data.put("__koil_virtual_world_managed", "true");
        // A block replacement is a real capability rebind, not a texture swap.
        // Do not leak source power from the previous registered block identity.
        particle.signals.put("power", profile.initialPower());
    }


    /** Full item capability rebind used by virtual-world transformations such as
     * bucket fill/empty. This updates identity, tags, physics and sounds together
     * instead of only swapping the rendered item icon. */
    private void rebindParticleItem(Particle particle, Item item) {
        if (particle == null || item == null) return;
        String previousTags = particle.data.get("__koil_profile_tags");
        if (previousTags != null && !previousTags.isBlank()) {
            for (String tag : previousTags.split("\\|")) if (!tag.isBlank()) particle.interactionTags.remove(tag.toLowerCase());
        }
        particle.interactionTags.removeIf(tag -> tag.startsWith("block:") || tag.startsWith("item:"));
        particle.itemIcon = item;
        particle.blockIcon = null;
        Identifier id = Registries.ITEM.getId(item);
        GameSpriteBehavior.ItemProfile profile = GameSpriteBehavior.itemProfile(item);
        particle.material = UiParticleMaterials.Material.DEFAULT;
        SpawnOverrides.applyRuntimeMaterial(particle, particle.material);
        particle.mass = Math.max(0.001F, profile.mass());
        particle.solidity = clamp(profile.solidity(), 0.0F, 1.0F);
        particle.gravity = profile.gravity();
        particle.drag = profile.drag();
        particle.restitution = profile.restitution();
        particle.surfaceFriction = 0.92F;
        particle.soundProfile = GameSpriteBehavior.itemSoundProfile(item);
        particle.interactionRole = profile.role();
        particle.relationSensorRadius = profile.sensorRadius();
        particle.interactionTags.add("registry_sprite");
        particle.interactionTags.add("registry_item");
        particle.interactionTags.add("item");
        particle.interactionTags.add(profile.role().toLowerCase());
        for (String tag : profile.tags()) particle.interactionTags.add(tag.toLowerCase());
        if (id != null) {
            particle.interactionTags.add("item:" + id);
            particle.data.put("registry_id", id.toString());
        }
        particle.data.put("native_capability", "item");
        particle.data.put("__koil_profile_tags", String.join("|", profile.tags()));
        particle.data.remove("__koil_block_state");
        particle.data.put("__koil_virtual_world_managed", "true");
        particle.signals.remove("power");
    }

    /** Pointer interaction view exposed to JSON and addon-authored particles. */
    public final class ParticleInteractionContext {
        private final Particle particle;
        private final ParticleInteractionType type;
        private final int button;
        private final int key;
        private final float scrollX;
        private final float scrollY;
        private final Particle relatedParticle;

        private ParticleInteractionContext(Particle particle, ParticleInteractionType type, int button, int key,
                                           float scrollX, float scrollY, Particle relatedParticle) {
            this.particle = particle;
            this.type = type;
            this.button = button;
            this.key = key;
            this.scrollX = scrollX;
            this.scrollY = scrollY;
            this.relatedParticle = relatedParticle;
        }

        public ParticleInteractionType type() { return type; }
        public int button() { return button; }
        public int key() { return key; }
        public float scrollX() { return scrollX; }
        public float scrollY() { return scrollY; }
        public boolean selected() { return particle.selected; }
        public float mass() { return particle.mass; }
        public float rotation() { return particle.rotation; }
        public float angularVelocity() { return particle.angularVelocity; }
        public float impactSpeed() { return particle.lastInteractionImpactSpeed; }
        public float collisionNormalX() { return particle.lastInteractionNormalX; }
        public float collisionNormalY() { return particle.lastInteractionNormalY; }
        public String name() { return particle.interactionName; }
        public String role() { return particle.interactionRole; }
        public String relatedName() { return relatedParticle == null ? "" : relatedParticle.interactionName; }
        public String relatedRole() { return relatedParticle == null ? "" : relatedParticle.interactionRole; }
        public float x() { return particle.x; }
        public float y() { return particle.y; }
        public float vx() { return particle.vx; }
        public float vy() { return particle.vy; }
        public float age() { return particle.age; }
        public float lifetime() { return particle.lifetime; }
        public float visualScale() { return Math.max(0.05F, particle.spriteScale); }
        public float visualPixels() { return resolveVisualPixels(particle); }
        public Block blockIcon() { return particle.blockIcon; }
        public Item itemIcon() { return particle.itemIcon; }
        public float pointerX() { return UiParticleEngine.this.pointerX; }
        public float pointerY() { return UiParticleEngine.this.pointerY; }
        public float pointerDeltaX() { return UiParticleEngine.this.pointerDeltaX; }
        public float pointerDeltaY() { return UiParticleEngine.this.pointerDeltaY; }
        public float pointerVelocityX() { return UiParticleEngine.this.pointerVelocityX; }
        public float pointerVelocityY() { return UiParticleEngine.this.pointerVelocityY; }
        public float pointerSpeed() {
            return (float) Math.sqrt(pointerVelocityX() * pointerVelocityX() + pointerVelocityY() * pointerVelocityY());
        }
        public boolean dragging() { return particle.dragging; }
        public int sourceTargetX() { return particle.sourceTargetX; }
        public int sourceTargetY() { return particle.sourceTargetY; }
        public int sourceTargetWidth() { return particle.sourceTargetWidth; }
        public int sourceTargetHeight() { return particle.sourceTargetHeight; }
        public String data(String key) { return key == null ? null : particle.data.get(key); }
        public void data(String key, Object value) { if (key != null && value != null) particle.data.put(key, String.valueOf(value)); }
        public void resetAge() { particle.age = 0.0F; }
        public void age(float value) { particle.age = clamp(value, 0.0F, Math.max(0.0F, particle.lifetime - 0.001F)); }
        public void lifetime(float value) { particle.lifetime = Math.max(0.05F, value); }
        public void position(float x, float y) { particle.x = x; particle.y = y; }
        public void velocity(float vx, float vy) { particle.vx = vx; particle.vy = vy; }
        public void addVelocity(float vx, float vy) { particle.vx += vx; particle.vy += vy; }
        public void gravity(float value) { particle.gravity = value; }
        public void drag(float value) { particle.drag = clamp(value, 0.0F, 1.0F); }
        public void restitution(float value) { particle.restitution = clamp(value, 0.0F, 1.25F); }
        public void behavior(Behavior value) { particle.behavior = value == null ? Behavior.BALLISTIC : value; }
        public void material(UiParticleMaterials.Material value) {
            particle.material = value == null ? UiParticleMaterials.Material.DEFAULT : value;
            SpawnOverrides.applyRuntimeMaterial(particle, particle.material);
        }
        public void color(int rgb) { particle.color = rgb & 0x00FFFFFF; }
        public int color() { return particle.color; }
        public void alpha(float value) { particle.alpha = clamp(value, 0.0F, 1.0F); }
        public void visualPixels(float pixels) { particle.visualPixels = Math.max(1.0F, pixels); }
        public void spriteScale(float scale) { particle.spriteScale = Math.max(0.05F, scale); }
        public void blockIcon(Block block) { rebindParticleBlock(particle, block); }
        public void itemIcon(Item item) { if (item != null) rebindParticleItem(particle, item); }
        public void blockState(String state) {
            if (state == null || state.isBlank()) particle.data.remove("__koil_block_state");
            else particle.data.put("__koil_block_state", state.trim());
        }
        public String blockState() { return particle.data.getOrDefault("__koil_block_state", ""); }
        public void rotation(float degrees) { particle.rotation = degrees; }
        public void angularVelocity(float degreesPerSecond) { particle.angularVelocity = degreesPerSecond; }
        public void addAngularVelocity(float degreesPerSecond) { particle.angularVelocity += degreesPerSecond; particle.sleeping = false; }
        public void applyForce(float fx, float fy) {
            float invMass = 1.0F / Math.max(MIN_INTERACTION_MASS, particle.mass);
            particle.vx += fx * invMass;
            particle.vy += fy * invMass;
            particle.sleeping = false;
        }
        public float signal(String name) { return particleSignal(particle, name); }
        public void signal(String name, float value) { setParticleSignal(particle, name, value); }
        public void addSignal(String name, float delta) { setParticleSignal(particle, name, particleSignal(particle, name) + delta); }
        public String lastSignalName() { return particle.lastSignalName; }
        public float lastSignalValue() { return particle.lastSignalValue; }
        public boolean hasTag(String tag) { return tag != null && particle.interactionTags.contains(tag.trim().toLowerCase()); }
        public void draggable(boolean enabled) { particle.dragInteraction = enabled; }
        public void swipeEnabled(boolean enabled) { particle.swipeInteraction = enabled; }
        public void kill() { particle.removeRequested = true; }
        public boolean virtualWorldManaged() {
            return Boolean.parseBoolean(particle.data.getOrDefault("__koil_virtual_world_managed", "false"));
        }
        public long virtualWorldTick() { return virtualBlockWorld.getWorldTick(); }
        public void scheduleVirtualPowerRelease(int ticks) {
            virtualBlockWorld.schedulePowerRelease(particle.particleId, Math.max(1, ticks));
        }
        public boolean useVirtualBlock() {
            return virtualBlockWorld.useBlock(particle.particleId);
        }
        public boolean requestVirtualFunctionalAlignment(int ticks) {
            return virtualBlockWorld.requestFunctionalAlignment(particle.particleId, Math.max(1, ticks));
        }
        public int inventorySize() { return virtualBlockWorld.getInventorySize(particle.particleId); }
        public ItemStack inventoryStack(int slot) { return virtualBlockWorld.getInventoryStack(particle.particleId, slot); }
        public boolean ejectInventorySlot(int slot, int amount, float vx, float vy) {
            ItemStack extracted = virtualBlockWorld.extractFromInventory(particle.particleId, slot, amount);
            if (extracted.isEmpty()) return false;
            spawnVirtualWorldItem(extracted, particle.x, particle.y - Math.max(2.0F, resolveVisualPixels(particle) * 0.22F),
                    particle.simulationLayerId, vx, vy);
            return true;
        }
        public float random(float min, float max) { return effectContext.random(min, max); }
        public int randomInt(int bound) { return effectContext.randomInt(bound); }
        public boolean chance(float chance) { return effectContext.chance(chance); }
        public ParticleBuilder particle(Shape shape) {
            return effectContext.particle(shape, particle.x, particle.y).spriteScale(particle.spriteScale);
        }
        public ParticleBuilder particle(Shape shape, float x, float y) {
            return effectContext.particle(shape, x, y).spriteScale(particle.spriteScale);
        }
        public void spawn(ParticleBuilder builder) { effectContext.spawn(builder); }
        public void pulse(float duration, int color, float scale, Layer layer) {
            effectContext.pulseAt(particle.x, particle.y, 0.0F, duration, color, scale, layer);
        }
        public void playSound(SoundEvent sound, float volume, float pitch) {
            if (soundEnabled) UiParticleSoundEngine.play(sound, volume, pitch);
        }
        public void playSound(UiParticleSoundProfile.Cue cue) {
            if (soundEnabled) UiParticleSoundEngine.play(cue);
        }
        public void windZone(float x, float y, float width, float height, float forceX, float forceY, float lifetime) {
            effectContext.windZone(x, y, width, height, forceX, forceY, lifetime);
        }
        public void vortexWell(float x, float y, float radius, float spin, float pull, float lifetime) {
            effectContext.vortexWell(x, y, radius, spin, pull, lifetime);
        }
        public void withEffect(Consumer<EffectContext> action) {
            if (action == null) return;
            SpawnOverrides old = activeSpawnOverrides;
            activeSpawnOverrides = particle.spawnOverrides == null ? SpawnOverrides.EMPTY : particle.spawnOverrides;
            try {
                effectContext.withTarget(particle.sourceTargetX, particle.sourceTargetY,
                        particle.sourceTargetWidth, particle.sourceTargetHeight, action);
            } finally {
                activeSpawnOverrides = old;
            }
        }
    }

    /** Logical sprite-to-sprite interaction view. Physical collision is independent. */
    public final class ParticleRelationContext {
        private final Particle self;
        private final Particle other;
        private final ParticleRelationType type;
        private final float distance;

        private ParticleRelationContext(Particle self, Particle other, ParticleRelationType type, float distance) {
            this.self = self;
            this.other = other;
            this.type = type;
            this.distance = distance;
        }

        public ParticleRelationType type() { return type; }
        public float distance() { return distance; }
        public long selfId() { return self.particleId; }
        public long otherId() { return other == null ? -1L : other.particleId; }
        public String selfName() { return self.interactionName; }
        public String otherName() { return other == null ? "" : other.interactionName; }
        public String selfRole() { return self.interactionRole; }
        public String otherRole() { return other == null ? "" : other.interactionRole; }
        public int selfLayer() { return self.simulationLayerId; }
        public int otherLayer() { return other == null ? Integer.MIN_VALUE : other.simulationLayerId; }
        public boolean selfDragging() { return self.dragging; }
        public boolean otherDragging() { return other != null && other.dragging; }
        public boolean selfSelected() { return self.selected; }
        public boolean otherSelected() { return other != null && other.selected; }
        public float selfMass() { return self.mass; }
        public float otherMass() { return other == null ? 0.0F : other.mass; }
        public float selfX() { return self.x; }
        public float selfY() { return self.y; }
        public float otherX() { return other == null ? self.x : other.x; }
        public float otherY() { return other == null ? self.y : other.y; }
        public float selfVx() { return self.vx; }
        public float selfVy() { return self.vy; }
        public float selfRotation() { return self.rotation; }
        public float otherRotation() { return other == null ? 0.0F : other.rotation; }
        public float otherVx() { return other == null ? 0.0F : other.vx; }
        public float otherVy() { return other == null ? 0.0F : other.vy; }
        public float selfHalfWidth() { return particleVisualBounds(self, true).halfWidth(); }
        public float selfHalfHeight() { return particleVisualBounds(self, true).halfHeight(); }
        public float otherHalfWidth() { return other == null ? 0.0F : particleVisualBounds(other, true).halfWidth(); }
        public float otherHalfHeight() { return other == null ? 0.0F : particleVisualBounds(other, true).halfHeight(); }
        public float selfVisualRadius() {
            ParticleBounds b = particleVisualBounds(self, true);
            return Math.max(b.halfWidth(), b.halfHeight());
        }
        public float otherVisualRadius() {
            if (other == null) return 0.0F;
            ParticleBounds b = particleVisualBounds(other, true);
            return Math.max(b.halfWidth(), b.halfHeight());
        }
        public Block selfBlockIcon() { return self.blockIcon; }
        public Block otherBlockIcon() { return other == null ? null : other.blockIcon; }
        public Item selfItemIcon() { return self.itemIcon; }
        public Item otherItemIcon() { return other == null ? null : other.itemIcon; }
        public boolean selfHasTag(String tag) { return tag != null && self.interactionTags.contains(tag.trim().toLowerCase()); }
        public boolean otherHasTag(String tag) { return other != null && tag != null && other.interactionTags.contains(tag.trim().toLowerCase()); }
        public float selfSignal(String name) { return particleSignal(self, name); }
        public float otherSignal(String name) { return other == null ? 0.0F : particleSignal(other, name); }
        public void selfSignal(String name, float value) { setParticleSignal(self, name, value); }
        public void otherSignal(String name, float value) { if (other != null) setParticleSignal(other, name, value); }
        public String selfData(String key) { return key == null ? null : self.data.get(key); }
        public String otherData(String key) { return other == null || key == null ? null : other.data.get(key); }
        public void selfData(String key, Object value) { if (key != null && value != null) self.data.put(key, String.valueOf(value)); }
        public void otherData(String key, Object value) { if (other != null && key != null && value != null) other.data.put(key, String.valueOf(value)); }
        public float random(float min, float max) { return effectContext.random(min, max); }
        public boolean chance(float chance) { return effectContext.chance(chance); }
        public boolean cooldownReady(String key, long cooldownMs) {
            if (key == null || key.isBlank() || cooldownMs <= 0L) return true;
            long now = System.nanoTime();
            long previous = self.interactionCooldowns.getOrDefault(key, Long.MIN_VALUE / 2L);
            if (now - previous < cooldownMs * 1_000_000L) return false;
            self.interactionCooldowns.put(key, now);
            return true;
        }
        public void withSelf(Consumer<ParticleInteractionContext> action) {
            if (action != null) action.accept(new ParticleInteractionContext(self, ParticleInteractionType.SPRITE_HIT, -1, -1, 0.0F, 0.0F, other));
        }
        public void withOther(Consumer<ParticleInteractionContext> action) {
            if (action != null && other != null) action.accept(new ParticleInteractionContext(other, ParticleInteractionType.SPRITE_HIT, -1, -1, 0.0F, 0.0F, self));
        }
        public void applyForceToSelf(float fx, float fy) { applyForceToParticle(self, fx, fy); }
        public void applyForceToOther(float fx, float fy) { if (other != null) applyForceToParticle(other, fx, fy); }

        public boolean selfVirtualWorldManaged() {
            return Boolean.parseBoolean(self.data.getOrDefault("__koil_virtual_world_managed", "false"));
        }

        public boolean interactOtherItemWithVirtualBlock() {
            return other != null && virtualBlockWorld.interactItemWithBlock(self.particleId, other.particleId);
        }

        public boolean notifyVirtualProjectileHit() {
            return other != null && virtualBlockWorld.notifyProjectileHit(self.particleId, other.particleId);
        }

        /** Inserts from the other item sprite into the self block's persistent
         * virtual inventory and consumes only the amount that was accepted. */
        public int insertOtherItemIntoSelfInventory(int preferredSlot, int maxAmount) {
            if (other == null || other.itemIcon == null || maxAmount <= 0) return 0;
            ItemStack offered = particleItemStack(other);
            if (offered.isEmpty()) return 0;
            int inserted = virtualBlockWorld.insertIntoInventory(self.particleId, offered, preferredSlot, maxAmount);
            if (inserted > 0) consumeParticleItemStack(other, inserted);
            return inserted;
        }

        public int selfInventorySize() { return virtualBlockWorld.getInventorySize(self.particleId); }

        public ItemStack selfInventoryStack(int slot) { return virtualBlockWorld.getInventoryStack(self.particleId, slot); }

        public boolean ejectSelfInventorySlot(int slot, int amount, float vx, float vy) {
            ItemStack extracted = virtualBlockWorld.extractFromInventory(self.particleId, slot, amount);
            if (extracted.isEmpty()) return false;
            spawnVirtualWorldItem(extracted, self.x, self.y - Math.max(2.0F, selfHalfHeight() * 0.45F),
                    self.simulationLayerId, vx, vy);
            return true;
        }

        public void linkSpring(float restLength, float stiffness, float damping, float breakDistance, float maxForce) {
            if (other != null) addOrUpdateConstraint(self, other, restLength, stiffness, damping, breakDistance, maxForce);
        }
        public void unlink() { if (other != null) removeConstraint(self.particleId, other.particleId); }
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
        /** Seconds the current effect has continuously remained active on this target. */
        public float effectAgeSeconds() { return currentEffectAgeSeconds; }
        /** True only while the owning widget is currently active/hovered. */
        public boolean hoverActive() { return active; }
        public String override(String key) { return activeSpawnOverrides.get(key); }
        public boolean hasOverride(String key) { return activeSpawnOverrides.has(key); }
        /** Visual scale supplied by the current trigger, including /sprite scale. */
        public float visualScale() { return activeSpawnOverrides.defaultVisualScale; }
        public void playSound(SoundEvent sound, float volume, float pitch) { if (soundEnabled) UiParticleSoundEngine.play(sound, volume, pitch); }
        public void playSound(UiParticleSoundProfile.Cue cue) { if (soundEnabled) UiParticleSoundEngine.play(cue); }
        public void schedule(float delaySeconds, Consumer<EffectContext> action) {
            if (action == null) return;
            float delay = Math.max(0.0F, delaySeconds);
            if (delay <= 0.0001F) {
                action.accept(this);
                return;
            }
            scheduledEffectActions.add(new ScheduledEffectAction(
                    delay,
                    action,
                    activeSpawnOverrides,
                    targetX,
                    targetY,
                    targetWidth,
                    targetHeight
            ));
        }
        public void withTarget(float x, float y, float width, float height, Consumer<EffectContext> action) {
            if (action == null) return;
            int oldTargetX = targetX;
            int oldTargetY = targetY;
            int oldTargetWidth = targetWidth;
            int oldTargetHeight = targetHeight;
            float oldCenterX = centerX;
            float oldCenterY = centerY;
            targetX = Math.round(x);
            targetY = Math.round(y);
            targetWidth = Math.max(1, Math.round(width));
            targetHeight = Math.max(1, Math.round(height));
            centerX = targetX + targetWidth * 0.5F;
            centerY = targetY + targetHeight * 0.5F;
            try {
                action.accept(this);
            } finally {
                targetX = oldTargetX;
                targetY = oldTargetY;
                targetWidth = oldTargetWidth;
                targetHeight = oldTargetHeight;
                centerX = oldCenterX;
                centerY = oldCenterY;
            }
        }
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
                UiParticleSoundEngine.play(particle.soundProfile.spawn());
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
            pulseAt(centerX, centerY, delay, duration, color, scale, layer);
        }

        public void pulseAt(float x, float y, float delay, float duration, int color, float scale, Layer layer) {
            Pulse pulse = new Pulse();
            pulse.x = x;
            pulse.y = y;
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
            pulse.x = centerX;
            pulse.y = centerY;
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
        private VanillaParticleSprites.SpriteSet spriteSet;
        private boolean forcePrimitive;
        private Identifier customTexture;
        private Block blockIcon;
        private Item itemIcon;
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
        private UiParticleMaterials.Material material = UiParticleMaterials.Material.DEFAULT;
        private UiParticleSoundProfile soundProfile;
        private ParticleEventHandler onTick;
        private ParticleEventHandler onBounce;
        private ParticleEventHandler onExpire;
        private boolean interactive;
        private boolean hoverInteraction = true;
        private boolean clickInteraction = true;
        private boolean consumePointerInput;
        private boolean hoverResetAge = true;
        private boolean dragInteraction;
        private boolean swipeInteraction;
        private int dragMouseButton = 0;
        private float interactionHitboxScale = 1.30F;
        private float dragReleaseVelocityScale = 0.11F;
        private boolean dragMassAware = true;
        private float dragMassExponent = 1.05F;
        private float swipeMinSpeed = 1100.0F;
        private float swipeStrength = 0.026F;
        private float swipeMaxImpulse = 34.0F;
        private long swipeCooldownMs = 250L;
        private boolean swipeMassAware = true;
        private float swipeMassExponent = 1.15F;
        private float swipeTorqueStrength = 0.20F;
        private float swipeMaxAngularImpulse = 240.0F;
        private boolean selectable = true;
        private boolean selectionResetAge = true;
        private boolean scrollRotateInteraction;
        private boolean scrollRequireGrabbed = true;
        private boolean scrollInvert;
        private boolean scrollInertial;
        private float scrollDegreesPerStep = 15.0F;
        private float scrollAngularImpulse = 1.0F;
        private float scrollSnapDegrees;
        private float scrollMaxAngularVelocity = 900.0F;
        private boolean keyboardInteraction;
        private final Set<Integer> keyboardKeys = new HashSet<>();
        private boolean relationEnabled;
        private String interactionName = "";
        private String interactionRole = "";
        private float relationSensorRadius;
        private float relationOverlapScale = 1.0F;
        private boolean relationSameLayerOnly = true;
        private boolean relationCrossLayer;
        private final Map<String, Float> signals = new LinkedHashMap<>();
        private ParticleInteractionHandler onInteract;
        private ParticleRelationHandler onRelation;

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
        public ParticleBuilder sprite(VanillaParticleSprites.SpriteSet spriteSet) { this.spriteSet = spriteSet; return this; }
        /** Uses Koil's pixel-art shape renderer even when a vanilla sprite mapping exists. */
        public ParticleBuilder primitive(boolean primitive) { this.forcePrimitive = primitive; return this; }
        /** Allows addon authors to use any resource texture. Dimensions are auto-detected by default. */
        public ParticleBuilder texture(Identifier texture) { this.customTexture = texture; return this; }
        /** Renders the registered block as a flat representative face while retaining particle physics. */
        public ParticleBuilder blockIcon(Block block) { this.blockIcon = block; return this; }
        /** Renders the registered item as a flat item texture while retaining particle physics. */
        public ParticleBuilder itemIcon(Item item) { this.itemIcon = item; return this; }
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
        public ParticleBuilder material(UiParticleMaterials.Material material) {
            this.material = material == null ? UiParticleMaterials.Material.DEFAULT : material;
            UiParticleMaterials.apply(this.material, this);
            return this;
        }
        public ParticleBuilder soundProfile(UiParticleSoundProfile profile) { this.soundProfile = profile; return this; }
        public ParticleBuilder blockSounds(Block block) { this.soundProfile = UiParticleSoundProfile.forBlock(block); return this; }
        public ParticleBuilder bouncingBlockSounds(Block block) { this.soundProfile = UiParticleSoundProfile.forBouncingBlock(block); return this; }
        public ParticleBuilder onTick(ParticleEventHandler handler) { this.onTick = handler; return this; }
        /** Add a tick handler without replacing behavior already installed by another capability layer. */
        public ParticleBuilder addOnTick(ParticleEventHandler handler) {
            if (handler == null) return this;
            ParticleEventHandler previous = this.onTick;
            this.onTick = previous == null ? handler : event -> { previous.handle(event); handler.handle(event); };
            return this;
        }
        public ParticleBuilder onBounce(ParticleEventHandler handler) { this.onBounce = handler; return this; }
        public ParticleBuilder onExpire(ParticleEventHandler handler) { this.onExpire = handler; return this; }
        public ParticleBuilder interactive(boolean enabled) { this.interactive = enabled; return this; }
        public ParticleBuilder hoverInteraction(boolean enabled) { this.hoverInteraction = enabled; this.interactive |= enabled; return this; }
        public ParticleBuilder clickInteraction(boolean enabled) { this.clickInteraction = enabled; this.interactive |= enabled; return this; }
        public ParticleBuilder consumePointerInput(boolean enabled) { this.consumePointerInput = enabled; return this; }
        public ParticleBuilder hoverResetAge(boolean enabled) { this.hoverResetAge = enabled; this.interactive |= enabled; return this; }
        public ParticleBuilder dragInteraction(boolean enabled, int mouseButton, float releaseVelocityScale) {
            this.dragInteraction = enabled;
            this.dragMouseButton = mouseButton;
            this.dragReleaseVelocityScale = Math.max(0.0F, releaseVelocityScale);
            this.interactive |= enabled;
            return this;
        }
        public ParticleBuilder swipeInteraction(boolean enabled, float minSpeed, float strength, float maxImpulse, long cooldownMs) {
            this.swipeInteraction = enabled;
            this.swipeMinSpeed = Math.max(1.0F, minSpeed);
            this.swipeStrength = Math.max(0.0F, strength);
            this.swipeMaxImpulse = Math.max(1.0F, maxImpulse);
            this.swipeCooldownMs = Math.max(0L, cooldownMs);
            this.interactive |= enabled;
            return this;
        }
        public ParticleBuilder dragMassPhysics(boolean massAware, float exponent) {
            this.dragMassAware = massAware;
            this.dragMassExponent = clamp(exponent, 0.0F, 2.0F);
            return this;
        }
        public ParticleBuilder swipeMassPhysics(boolean massAware, float exponent, float torqueStrength, float maxAngularImpulse) {
            this.swipeMassAware = massAware;
            this.swipeMassExponent = clamp(exponent, 0.0F, 2.0F);
            this.swipeTorqueStrength = Math.max(0.0F, torqueStrength);
            this.swipeMaxAngularImpulse = Math.max(1.0F, maxAngularImpulse);
            return this;
        }
        public ParticleBuilder selectable(boolean enabled, boolean resetAgeWhileSelected) {
            this.selectable = enabled;
            this.selectionResetAge = resetAgeWhileSelected;
            this.interactive |= enabled;
            return this;
        }
        public ParticleBuilder scrollRotation(boolean enabled, boolean requireGrabbed, float degreesPerStep,
                                              boolean inertial, float angularImpulse, float snapDegrees,
                                              float maxAngularVelocity, boolean invert) {
            this.scrollRotateInteraction = enabled;
            this.scrollRequireGrabbed = requireGrabbed;
            this.scrollDegreesPerStep = degreesPerStep;
            this.scrollInertial = inertial;
            this.scrollAngularImpulse = Math.max(0.0F, angularImpulse);
            this.scrollSnapDegrees = Math.max(0.0F, snapDegrees);
            this.scrollMaxAngularVelocity = Math.max(1.0F, maxAngularVelocity);
            this.scrollInvert = invert;
            this.interactive |= enabled;
            return this;
        }
        public ParticleBuilder keyboardInteraction(boolean enabled, int... keys) {
            this.keyboardInteraction = enabled;
            if (keys != null) for (int key : keys) if (key >= 0 && key < MAX_KEYS) this.keyboardKeys.add(key);
            this.interactive |= enabled;
            return this;
        }
        public ParticleBuilder watchKey(int key) {
            if (key >= 0 && key < MAX_KEYS) this.keyboardKeys.add(key);
            this.keyboardInteraction = true;
            this.interactive = true;
            return this;
        }
        public ParticleBuilder relationIdentity(String name, String role) {
            this.interactionName = name == null ? "" : name.trim();
            this.interactionRole = role == null ? "" : role.trim().toLowerCase();
            return this;
        }
        public ParticleBuilder relationInteraction(boolean enabled, float sensorRadius, float overlapScale,
                                                   boolean sameLayerOnly, boolean crossLayer) {
            this.relationEnabled = enabled;
            this.relationSensorRadius = Math.max(0.0F, sensorRadius);
            this.relationOverlapScale = Math.max(0.1F, overlapScale);
            this.relationSameLayerOnly = sameLayerOnly;
            this.relationCrossLayer = crossLayer;
            this.interactive |= enabled;
            return this;
        }
        public ParticleBuilder signal(String name, float value) {
            if (name != null && !name.isBlank()) this.signals.put(name.trim().toLowerCase(), value);
            return this;
        }
        public ParticleBuilder interactionHitboxScale(float scale) { this.interactionHitboxScale = clamp(scale, 0.5F, 4.0F); return this; }
        public ParticleBuilder onInteract(ParticleInteractionHandler handler) { this.onInteract = handler; this.interactive |= handler != null; return this; }
        /** Add a pointer/keyboard interaction handler without replacing native block/item behavior. */
        public ParticleBuilder addOnInteract(ParticleInteractionHandler handler) {
            if (handler == null) return this;
            ParticleInteractionHandler previous = this.onInteract;
            this.onInteract = previous == null ? handler : event -> { previous.handle(event); handler.handle(event); };
            this.interactive = true;
            return this;
        }
        public ParticleBuilder onRelation(ParticleRelationHandler handler) { this.onRelation = handler; this.relationEnabled |= handler != null; this.interactive |= handler != null; return this; }
        /** Add a sprite-relation handler without replacing native block/item behavior. */
        public ParticleBuilder addOnRelation(ParticleRelationHandler handler) {
            if (handler == null) return this;
            ParticleRelationHandler previous = this.onRelation;
            this.onRelation = previous == null ? handler : event -> { previous.handle(event); handler.handle(event); };
            this.relationEnabled = true;
            this.interactive = true;
            return this;
        }

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
            particle.forcePrimitive = forcePrimitive;
            particle.customTexture = customTexture;
            particle.blockIcon = blockIcon;
            particle.itemIcon = itemIcon;
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
            particle.spawnSequence = UiParticleEngine.this.spawnSequence;
            particle.material = material;
            particle.soundProfile = soundProfile;
            particle.onTick = onTick;
            particle.onBounce = onBounce;
            particle.onExpire = onExpire;
            boolean tagScrollRotatable = interactionTags.contains("scroll_rotatable") || interactionTags.contains("wheel_rotatable");
            particle.interactive = interactive || tagScrollRotatable || relationEnabled || keyboardInteraction;
            particle.hoverInteraction = hoverInteraction;
            particle.clickInteraction = clickInteraction;
            particle.consumePointerInput = consumePointerInput;
            particle.hoverResetAge = hoverResetAge;
            particle.dragInteraction = dragInteraction;
            particle.swipeInteraction = swipeInteraction;
            particle.dragMouseButton = dragMouseButton;
            particle.interactionHitboxScale = interactionHitboxScale;
            particle.dragReleaseVelocityScale = dragReleaseVelocityScale;
            particle.dragMassAware = dragMassAware;
            particle.dragMassExponent = dragMassExponent;
            particle.swipeMinSpeed = swipeMinSpeed;
            particle.swipeStrength = swipeStrength;
            particle.swipeMaxImpulse = swipeMaxImpulse;
            particle.swipeCooldownMs = swipeCooldownMs;
            particle.swipeMassAware = swipeMassAware;
            particle.swipeMassExponent = swipeMassExponent;
            particle.swipeTorqueStrength = swipeTorqueStrength;
            particle.swipeMaxAngularImpulse = swipeMaxAngularImpulse;
            particle.selectable = selectable;
            particle.selectionResetAge = selectionResetAge;
            particle.scrollRotateInteraction = scrollRotateInteraction || tagScrollRotatable;
            particle.scrollRequireGrabbed = scrollRequireGrabbed;
            particle.scrollInvert = scrollInvert;
            particle.scrollInertial = scrollInertial;
            particle.scrollDegreesPerStep = scrollDegreesPerStep;
            particle.scrollAngularImpulse = scrollAngularImpulse;
            particle.scrollSnapDegrees = scrollSnapDegrees;
            particle.scrollMaxAngularVelocity = scrollMaxAngularVelocity;
            particle.keyboardInteraction = keyboardInteraction;
            particle.keyboardKeys.addAll(keyboardKeys);
            particle.relationEnabled = relationEnabled;
            particle.interactionName = interactionName;
            particle.interactionRole = interactionRole;
            particle.relationSensorRadius = relationSensorRadius;
            particle.relationOverlapScale = relationOverlapScale;
            particle.relationSameLayerOnly = relationSameLayerOnly;
            particle.relationCrossLayer = relationCrossLayer;
            particle.signals.putAll(signals);
            particle.onInteract = onInteract;
            particle.onRelation = onRelation;
            particle.sourceTargetX = targetX;
            particle.sourceTargetY = targetY;
            particle.sourceTargetWidth = targetWidth;
            particle.sourceTargetHeight = targetHeight;
            particle.spawnOverrides = activeSpawnOverrides;
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
        private VanillaParticleSprites.SpriteSet spriteSet;
        private boolean forcePrimitive;
        private Identifier customTexture;
        private Block blockIcon;
        private Item itemIcon;
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
        private UiParticleMaterials.Material material;
        private UiParticleSoundProfile soundProfile;
        private int bounceCount;
        private float lastBounceAge = -1000.0F;
        private boolean removeRequested;
        private ParticleEventHandler onTick;
        private ParticleEventHandler onBounce;
        private ParticleEventHandler onExpire;
        private boolean interactive;
        private boolean hoverInteraction = true;
        private boolean clickInteraction = true;
        private boolean consumePointerInput;
        private boolean hoverResetAge = true;
        private boolean dragInteraction;
        private boolean swipeInteraction;
        private int dragMouseButton;
        private float interactionHitboxScale = 1.30F;
        private float dragReleaseVelocityScale = 0.11F;
        private boolean dragMassAware = true;
        private float dragMassExponent = 1.05F;
        private float swipeMinSpeed = 1100.0F;
        private float swipeStrength = 0.026F;
        private float swipeMaxImpulse = 34.0F;
        private long swipeCooldownMs = 250L;
        private boolean swipeMassAware = true;
        private float swipeMassExponent = 1.15F;
        private float swipeTorqueStrength = 0.20F;
        private float swipeMaxAngularImpulse = 240.0F;
        private long lastSwipeNanos;
        private boolean pointerHovered;
        private boolean dragging;
        private boolean selectable = true;
        private boolean selected;
        private boolean selectionResetAge = true;
        private boolean scrollRotateInteraction;
        private boolean scrollRequireGrabbed = true;
        private boolean scrollInvert;
        private boolean scrollInertial;
        private float scrollDegreesPerStep = 15.0F;
        private float scrollAngularImpulse = 1.0F;
        private float scrollSnapDegrees;
        private float scrollMaxAngularVelocity = 900.0F;
        private boolean keyboardInteraction;
        private final Set<Integer> keyboardKeys = new HashSet<>();
        private boolean relationEnabled;
        private String interactionName = "";
        private String interactionRole = "";
        private float relationSensorRadius;
        private float relationOverlapScale = 1.0F;
        private boolean relationSameLayerOnly = true;
        private boolean relationCrossLayer;
        private final Map<String, Float> signals = new LinkedHashMap<>();
        private String lastSignalName = "";
        private float lastSignalValue;
        private final Map<String, Long> interactionCooldowns = new HashMap<>();
        private float lastInteractionNormalX;
        private float lastInteractionNormalY;
        private float lastInteractionImpactSpeed;
        private ParticleInteractionHandler onInteract;
        private ParticleRelationHandler onRelation;
        private int sourceTargetX;
        private int sourceTargetY;
        private int sourceTargetWidth = 1;
        private int sourceTargetHeight = 1;
        private SpawnOverrides spawnOverrides = SpawnOverrides.EMPTY;
    }


    private record DirectedPairKey(long selfId, long otherId) { }

    private static final class ParticleConstraint {
        private final long lowId;
        private final long highId;
        private float restLength;
        private float stiffness;
        private float damping;
        private float breakDistance;
        private float maxForce;

        private ParticleConstraint(long lowId, long highId, float restLength, float stiffness,
                                   float damping, float breakDistance, float maxForce) {
            this.lowId = lowId;
            this.highId = highId;
            this.restLength = restLength;
            this.stiffness = stiffness;
            this.damping = damping;
            this.breakDistance = breakDistance;
            this.maxForce = maxForce;
        }
    }

    private record SpawnElementImmunity(float left, float top, float right, float bottom) { }

    private enum ForceFieldType { WIND, VORTEX }

    private static final class ScheduledEffectAction {
        private float remainingSeconds;
        private final Consumer<EffectContext> action;
        private final SpawnOverrides spawnOverrides;
        private final float targetX;
        private final float targetY;
        private final float targetWidth;
        private final float targetHeight;

        private ScheduledEffectAction(float remainingSeconds, Consumer<EffectContext> action,
                                      SpawnOverrides spawnOverrides,
                                      float targetX, float targetY, float targetWidth, float targetHeight) {
            this.remainingSeconds = Math.max(0.0F, remainingSeconds);
            this.action = action;
            this.spawnOverrides = spawnOverrides == null ? SpawnOverrides.EMPTY : spawnOverrides;
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetWidth = Math.max(1.0F, targetWidth);
            this.targetHeight = Math.max(1.0F, targetHeight);
        }
    }

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
        private float x;
        private float y;
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
