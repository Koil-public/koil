package com.spirit.koil.api.design.particle;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.Random;

/** Shared HUD/screen-space render overlay for command-triggered Koil sprites. */
public final class KoilScreenSpriteOverlay {
    private static final Queue<KoilScreenSpriteRequest> PENDING = new ArrayDeque<>();
    private static final KoilUiParticleEngine ENGINE = new KoilUiParticleEngine();
    private static final Random RANDOM = new Random();
    private static boolean hudRegistered;

    private KoilScreenSpriteOverlay() { }

    public static synchronized void registerHudRenderer() {
        if (hudRegistered) return;
        hudRegistered = true;
        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.getWindow() == null) return;
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

    public static synchronized boolean enqueue(KoilScreenSpriteRequest request) {
        KoilGameParticleRegistryBridge.registerAllAvailable();
        if (request == null || !request.valid()) return false;
        PENDING.offer(request);
        return true;
    }

    public static void render(DrawContext context, int width, int height, int mouseX, int mouseY) {
        KoilGameParticleRegistryBridge.registerAllAvailable();
        // Establish the real HUD dimensions and advance existing particles once
        // before processing new one-shot requests. New sprites then render at
        // their exact birth positions for their first visible frame.
        ENGINE.beginFrame(width, height, 0, 0, 1, 1, false);

        KoilScreenSpriteRequest request;
        while ((request = poll()) != null) {
            int baseX = request.cursor() ? mouseX : Math.min(width - 1, request.x());
            int baseY = request.cursor() ? mouseY : Math.min(height - 1, request.y());
            Map<String, String> values = request.overrideMap();
            KoilUiParticleEngine.SpawnOverrides overrides = new KoilUiParticleEngine.SpawnOverrides(values);

            // Every requested copy is additive. A small independent target jitter
            // prevents count>1 from producing perfectly coincident simulations.
            float spread = parseFloat(values.get("spawn_spread"), Math.max(2.0F, 3.5F * request.scale()));
            for (int index = 0; index < request.count(); index++) {
                float jitterX = request.count() <= 1 ? 0.0F : (RANDOM.nextFloat() * 2.0F - 1.0F) * spread;
                float jitterY = request.count() <= 1 ? 0.0F : (RANDOM.nextFloat() * 2.0F - 1.0F) * spread;
                ENGINE.triggerAt(request.id(), baseX + jitterX, baseY + jitterY, 1.0F, 1.0F,
                        request.scale(), overrides, false);
            }
        }

        ENGINE.renderBehind(context);
        ENGINE.renderForeground(context);
    }

    public static KoilUiParticleEngine engine() { return ENGINE; }

    private static synchronized KoilScreenSpriteRequest poll() { return PENDING.poll(); }

    private static float parseFloat(String value, float fallback) {
        try { return value == null ? fallback : Float.parseFloat(value); }
        catch (RuntimeException ignored) { return fallback; }
    }
}
