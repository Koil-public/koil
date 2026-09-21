package com.spirit.koil.api.design.particle;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWScrollCallbackI;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.Random;

/** Shared HUD/screen-space render overlay for command-triggered Koil sprites. */
public final class ScreenSpriteOverlay {
    private static final Queue<ScreenSpriteRequest> PENDING = new ArrayDeque<>();
    private static final UiParticleEngine ENGINE = new UiParticleEngine();
    private static final Random RANDOM = new Random();
    private static final SceneOverlayInteractionController SCENE_INPUT = new SceneOverlayInteractionController();
    private static boolean hudRegistered;
    private static long scrollHookWindow;
    private static GLFWScrollCallbackI previousScrollCallback;
    private static GLFWScrollCallbackI koilScrollCallback;
    private static boolean dispatchingScrollCallback;

    private ScreenSpriteOverlay() { }

    public static synchronized void registerHudRenderer() {
        if (hudRegistered) return;
        hudRegistered = true;
        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.getWindow() == null) return;
            ensureScrollInputHook(client);
            int width = client.getWindow().getScaledWidth();
            int height = client.getWindow().getScaledHeight();
            int mouseX = 0;
            int mouseY = 0;
            if (client.mouse != null && client.getWindow().getWidth() > 0 && client.getWindow().getHeight() > 0) {
                mouseX = (int) Math.round(client.mouse.getX() * width / (double) client.getWindow().getWidth());
                mouseY = (int) Math.round(client.mouse.getY() * height / (double) client.getWindow().getHeight());
            }
            render(context, width, height, mouseX, mouseY);
        });
    }

    /**
     * HUD /sprite needs wheel input even when no Screen is open. We preserve
     * Minecraft's existing GLFW scroll callback and chain Koil after it. Koil
     * screens can also call engine().pointerScroll(...) directly from their
     * mouseScrolled implementation when they want deterministic consumption.
     */
    private static synchronized void ensureScrollInputHook(MinecraftClient client) {
        if (client == null || client.getWindow() == null) return;
        long handle = client.getWindow().getHandle();
        if (handle == 0L) return;

        // Install once per GLFW window. Reinstalling this wrapper every HUD frame is
        // unsafe: glfwSetScrollCallback returns the callback that was installed at
        // that moment, and LWJGL may return a container which invokes this same Koil
        // wrapper. Saving that container as "previous" creates a callback cycle and
        // causes StackOverflowError on the next wheel event even with zero sprites.
        if (koilScrollCallback != null && handle == scrollHookWindow) return;

        scrollHookWindow = handle;
        previousScrollCallback = null;
        dispatchingScrollCallback = false;

        koilScrollCallback = (window, horizontal, vertical) -> {
            // A defensive re-entry guard also prevents a foreign callback chain from
            // routing back into Koil and recursively redispatching the same event.
            if (dispatchingScrollCallback) return;
            dispatchingScrollCallback = true;
            try {
                // Give a Koil sprite first refusal. If it consumes the wheel,
                // Minecraft must not also change the hotbar slot.
                boolean consumed = ENGINE.pointerScroll(horizontal, vertical);
                GLFWScrollCallbackI previous = previousScrollCallback;
                if (!consumed && previous != null) {
                    previous.invoke(window, horizontal, vertical);
                }
            } finally {
                dispatchingScrollCallback = false;
            }
        };

        // Capture Minecraft's/mods' callback exactly once when Koil installs its
        // wrapper. It remains the downstream callback for this window's lifetime.
        previousScrollCallback = GLFW.glfwSetScrollCallback(handle, koilScrollCallback);
    }

    public static synchronized boolean enqueue(ScreenSpriteRequest request) {
        GameParticleRegistryBridge.registerAllAvailable();
        GameSpriteRegistryBridge.registerAllAvailable();
        if (request == null || !request.valid()) return false;
        PENDING.offer(request);
        return true;
    }

    public static void render(DrawContext context, int width, int height, int mouseX, int mouseY) {
        GameParticleRegistryBridge.registerAllAvailable();
        GameSpriteRegistryBridge.registerAllAvailable();

        MinecraftClient client = MinecraftClient.getInstance();
        boolean playgroundOwnsFrame = client != null && client.currentScreen instanceof SpritePlaygroundScreen;
        if (!playgroundOwnsFrame) {
            // Establish the real HUD dimensions and advance existing particles once
            // before processing new one-shot requests. New sprites then render at
            // their exact birth positions for their first visible frame.
            ENGINE.beginFrame(width, height, 0, 0, 1, 1, false);
            SCENE_INPUT.update(client, ENGINE.getSpriteEngine(), mouseX, mouseY);
        }

        drainPending(width, height, mouseX, mouseY);
        if (playgroundOwnsFrame) return;

        ENGINE.renderBehind(context);
        ENGINE.renderForeground(context);
        SCENE_INPUT.renderSelection(context, ENGINE.getSpriteEngine());
    }

    /**
     * Drains command/network requests into the shared engine without advancing it.
     * The playground owns the frame while it is open, which avoids double-ticking
     * the same authoritative scene while still accepting live /sprite requests.
     */
    private static void drainPending(int width, int height, int mouseX, int mouseY) {
        ScreenSpriteRequest request;
        while ((request = poll()) != null) {
            int baseX = request.cursor() ? mouseX : Math.min(width - 1, request.x());
            int baseY = request.cursor() ? mouseY : Math.min(height - 1, request.y());
            Map<String, String> values = request.overrideMap();
            UiParticleEngine.SpawnOverrides overrides = new UiParticleEngine.SpawnOverrides(values);

            // Every requested copy is additive. A small independent target jitter
            // prevents count>1 from producing perfectly coincident simulations.
            float spread = parseFloat(values.get("spawn_spread"), Math.max(2.0F, 3.5F * request.scale()));
            float defaultButtonSize = Math.max(8.0F, Math.min(96.0F, 20.0F * request.scale()));
            float targetWidth = parseFloat(values.get("target_width"),
                    parseFloat(values.get("button_width"),
                            parseFloat(values.get("button_size"), defaultButtonSize)));
            float targetHeight = parseFloat(values.get("target_height"),
                    parseFloat(values.get("button_height"),
                            parseFloat(values.get("button_size"), defaultButtonSize)));
            for (int index = 0; index < request.count(); index++) {
                float jitterX = request.count() <= 1 ? 0.0F : (RANDOM.nextFloat() * 2.0F - 1.0F) * spread;
                float jitterY = request.count() <= 1 ? 0.0F : (RANDOM.nextFloat() * 2.0F - 1.0F) * spread;
                float left = baseX + jitterX - targetWidth * 0.5F;
                float top = baseY + jitterY - targetHeight * 0.5F;
                ENGINE.triggerAt(request.id(), left, top, targetWidth, targetHeight,
                        request.scale(), overrides, false);
            }
        }
    }

    public static UiParticleEngine engine() { return ENGINE; }

    static void resetSceneInput() { SCENE_INPUT.reset(); }

    private static synchronized ScreenSpriteRequest poll() { return PENDING.poll(); }

    private static float parseFloat(String value, float fallback) {
        try { return value == null ? fallback : Float.parseFloat(value); }
        catch (RuntimeException ignored) { return fallback; }
    }
}
