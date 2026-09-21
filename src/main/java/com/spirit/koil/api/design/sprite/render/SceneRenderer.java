package com.spirit.koil.api.design.sprite.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.world.Scene;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.opengl.GL11;

/**
 * Ordered scene compositor for the detached Koil scene.
 *
 * <p>Hidden Minecraft depth layers are resolved on the CPU before native
 * Minecraft render layers are submitted. This is intentionally not a loop that
 * renders every Z slice into the same DrawContext. Rendering every slice was a
 * regression: dense generated scenes could flood the native GUI/world buffer
 * path and allowed hidden layers to compete with the editor UI.</p>
 *
 * <p>All layers remain logically visible. SceneDepthCompositor chooses the
 * exposed surface contributed by those layers, and only that surface is sent to
 * the normal block/fluid/actor renderers.</p>
 */
public final class SceneRenderer {
    private final BlockRenderer2D blocks = new BlockRenderer2D();
    private final FluidRenderer2D fluids = new FluidRenderer2D();
    private final ActorRenderer2D actors = new ActorRenderer2D();
    private final DetachedTextureTicker detachedTextureTicker = new DetachedTextureTicker();

    private Scene depthMaskScene;
    private long depthMaskBlockRevision = Long.MIN_VALUE;
    private long depthMaskFluidRevision = Long.MIN_VALUE;
    private SceneProjection.Mode depthMaskProjectionMode;
    private SceneDepthCompositor.DepthMask cachedDepthMask;

    public void renderWorld(DrawContext context, Scene scene) {
        if (context == null || scene == null) return;

        // Minecraft 1.20.1 skips TextureManager.tick() when no ClientWorld
        // exists. Koil restores that same native tick on its detached clock.
        detachedTextureTicker.tickIfDetached(scene);

        // CPU-select the exposed depth-ray surface first. Then render terminal
        // opaque terrain and foreground non-full/cutout/translucent geometry in
        // separate native passes. This is essential for a 2D depth composite:
        // grass, bushes, cactus, leaves, fences, panes and glass must be able to
        // sit in front while their transparent/model holes reveal solid terrain
        // from a deeper layer.
        SceneDepthCompositor.DepthMask depthMask = depthMask(scene);

        blocks.renderOpaqueTerrain(context, scene, depthMask);
        clearDepthOnly();

        // Fluids are part of the cutaway body but remain behind foreground
        // vegetation/partial models.
        fluids.render(context, scene, depthMask);
        clearDepthOnly();

        blocks.renderForegroundOverlays(context, scene, depthMask);
        actors.render(context, scene, depthMask);
    }

    private static void clearDepthOnly() {
        RenderSystem.clearDepth(1.0D);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, false);
    }

    private SceneDepthCompositor.DepthMask depthMask(Scene scene) {
        long blockRevision = scene.blocks().revision();
        long fluidRevision = scene.fluids().revision();
        SceneProjection.Mode projectionMode = scene.projection().mode();
        if (scene != depthMaskScene || cachedDepthMask == null
                || blockRevision != depthMaskBlockRevision
                || fluidRevision != depthMaskFluidRevision
                || projectionMode != depthMaskProjectionMode) {
            depthMaskScene = scene;
            depthMaskBlockRevision = blockRevision;
            depthMaskFluidRevision = fluidRevision;
            depthMaskProjectionMode = projectionMode;
            cachedDepthMask = SceneDepthCompositor.build(scene);
        }
        return cachedDepthMask;
    }

    /** Editor-only authoritative-state placement preview. */
    public boolean renderBlockGhost(DrawContext context, Scene scene, SceneCellPos pos, BlockState state, float alpha) {
        return blocks.renderGhost(context, scene, pos, state, alpha);
    }
}
