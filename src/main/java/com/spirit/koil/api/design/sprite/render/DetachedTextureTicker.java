package com.spirit.koil.api.design.sprite.render;

import com.spirit.koil.api.design.sprite.world.Scene;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.screen.PlayerScreenHandler;

/**
 * Restores Minecraft's native stitched-block-atlas animation while Koil is
 * detached from a playable ClientWorld.
 *
 * <p>Minecraft 1.20.1 normally advances texture tick listeners from the client
 * tick path only while a world exists. Koil deliberately renders scenes on
 * title/options/menu screens, so fire, soul fire, campfire flames and any other
 * animated block-atlas sprite would otherwise remain on one frame.</p>
 *
 * <p>This adapter advances the actual Minecraft block atlas once per Koil 20 TPS
 * gameplay tick. It never selects animation frames itself and never rewrites UVs,
 * so vanilla/resource-pack/modded {@code .mcmeta} animation data stays fully
 * authoritative. When a real client world exists, vanilla owns atlas ticking and
 * this adapter becomes passive to avoid double-speed animation.</p>
 */
public final class DetachedTextureTicker {
    private Scene lastScene;
    private long lastSceneTick = Long.MIN_VALUE;

    public void tickIfDetached(Scene scene) {
        if (scene == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getBakedModelManager() == null) return;

        long tick = scene.clock().gameTick();

        // In-world MinecraftClient.tick() owns animated texture progression.
        // Synchronize our cursor so returning to a detached screen cannot replay
        // ticks already handled by vanilla.
        if (client.world != null) {
            lastScene = scene;
            lastSceneTick = tick;
            return;
        }

        // Establish a baseline for a new/reset scene. The atlas is global and may
        // already be at any animation frame, so no rewind or artificial reset is
        // appropriate here.
        if (scene != lastScene || tick < lastSceneTick || lastSceneTick == Long.MIN_VALUE) {
            lastScene = scene;
            lastSceneTick = tick;
            return;
        }

        long elapsedTicks = tick - lastSceneTick;
        if (elapsedTicks <= 0L) return;

        long completed = 0L;
        for (long i = 0L; i < elapsedTicks; i++) {
            try {
                SpriteAtlasTexture blockAtlas = client.getBakedModelManager()
                        .getAtlas(PlayerScreenHandler.BLOCK_ATLAS_TEXTURE);
                if (blockAtlas == null) break;
                blockAtlas.tick();
                completed++;
            } catch (RuntimeException ignored) {
                // Resource reloads can transiently swap atlas ownership. Stop this
                // frame and retry only the uncompleted time on a later render.
                break;
            }
        }
        lastSceneTick += completed;
    }

    public void reset() {
        lastScene = null;
        lastSceneTick = Long.MIN_VALUE;
    }
}
