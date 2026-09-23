package com.spirit.koil.api.design.sprite.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.systems.SceneLightingSystem;
import com.spirit.koil.api.design.sprite.systems.FluidSystem;
import com.spirit.koil.api.design.sprite.world.FluidCell;
import com.spirit.koil.api.design.sprite.world.FluidGrid;
import com.spirit.koil.api.design.sprite.world.Scene;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scene-native 2D liquid renderer. It renders KoilFluidGrid directly, so flowed
 * liquid does not need a Particle proxy or a legacy block-world spawn.
 *
 * <p>Rev AG gives side-view fluid a neighbor-derived surface profile. Instead
 * of stacking flat rectangles, adjacent levels share edge heights and produce
 * vanilla-inspired sloped surfaces. Falling columns remain vertical.</p>
 */
public final class FluidRenderer2D {
    private static final Identifier WATER_STILL = new Identifier("minecraft", "textures/block/water_still.png");
    private static final Identifier WATER_FLOW = new Identifier("minecraft", "textures/block/water_flow.png");
    private static final Identifier LAVA_STILL = new Identifier("minecraft", "textures/block/lava_still.png");
    private static final Identifier LAVA_FLOW = new Identifier("minecraft", "textures/block/lava_flow.png");

    public void render(DrawContext context, Scene scene) {
        render(context, scene, SceneDepthCompositor.build(scene), null);
    }

    public void render(DrawContext context, Scene scene, Integer depthFilter) {
        render(context, scene, SceneDepthCompositor.build(scene, depthFilter), depthFilter);
    }

    void render(DrawContext context, Scene scene, SceneDepthCompositor.DepthMask depthMask) {
        render(context, scene, depthMask, null);
    }

    /** Render exactly one hidden-axis slice for deterministic back-to-front composition. */
    void renderDepth(DrawContext context, Scene scene,
                     SceneDepthCompositor.DepthMask depthMask, int depth) {
        render(context, scene, depthMask, depth);
    }

    private void render(DrawContext context, Scene scene, SceneDepthCompositor.DepthMask depthMask, Integer depthFilter) {
        if (context == null || scene == null || scene.fluids().fluidCount() == 0) return;
        if (depthMask == null) depthMask = SceneDepthCompositor.build(scene, depthFilter);
        List<FluidGrid.Entry> entries = new ArrayList<>();
        if (depthFilter == null) {
            entries.addAll(depthMask.fluidEntries());
        } else {
            for (FluidGrid.Entry entry : scene.fluids().entries()) {
                if (entry == null || entry.position() == null || entry.cell() == null) continue;
                if (scene.projection().depthCoordinate(entry.position()) != depthFilter) continue;
                if (depthMask.renderFluid(scene, entry.position())) entries.add(entry);
            }
        }
        float viewportPad = Math.max(2.0F, scene.projection().cellPixels() * 1.5F);
        entries.removeIf(e -> !insideViewport(context, scene, e.position(), viewportPad));
        entries.sort(Comparator.comparingInt((FluidGrid.Entry e) -> scene.projection().depthCoordinate(e.position()))
                .thenComparingInt(e -> e.position().y()).thenComparingInt(e -> e.position().x()));
        float age = scene.clock().physicsStep() / 60.0F;
        for (FluidGrid.Entry entry : entries) {
            renderCell(context, scene, entry.position(), entry.cell(), age);
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static boolean insideViewport(DrawContext context, Scene scene,
                                          SceneCellPos pos, float pad) {
        if (context == null || scene == null || pos == null) return false;
        float x = scene.projection().cellCenterScreenX(pos);
        float y = scene.projection().cellCenterScreenY(pos);
        return x >= -pad && x <= context.getScaledWindowWidth() + pad
                && y >= -pad && y <= context.getScaledWindowHeight() + pad;
    }

    private void renderCell(DrawContext context, Scene scene, SceneCellPos pos, FluidCell cell, float age) {
        FluidState state = cell.state();
        if (state == null || state.isEmpty()) return;
        boolean water = FluidSystem.isWater(state);
        boolean lava = FluidSystem.isLava(state);
        if (!water && !lava) return;

        float pixels = scene.projection().cellPixels();
        float centerX = scene.projection().cellCenterScreenX(pos);
        float centerY = scene.projection().cellCenterScreenY(pos);
        int left = Math.round(centerX - pixels * 0.5F);
        int right = Math.round(centerX + pixels * 0.5F);
        int bottom = Math.round(centerY + pixels * 0.5F);
        int fullTop = Math.round(centerY - pixels * 0.5F);
        int width = Math.max(1, right - left);
        int fullHeight = Math.max(1, Math.round(pixels));

        FluidSystem.SurfaceProfile profile = scene.fluidSystem().surfaceProfile(pos);
        boolean topProjection = scene.projection().mode() == SceneProjection.Mode.XZ_TOP;
        float leftHeight = topProjection ? 1.0F : profile.leftHeight();
        float rightHeight = topProjection ? 1.0F : profile.rightHeight();
        boolean sloped = !topProjection && !cell.falling() && profile.sloped();
        float averageHeight = Math.max(0.0F, Math.min(1.0F, profile.averageHeight()));
        int flatTop = topProjection ? fullTop : Math.round(bottom - pixels * averageHeight);

        // Vanilla chooses flowing visuals for moving/sloped surfaces. Koil does
        // the same at the visual-policy level while retaining the native texture
        // and animation frames from the active resource pack.
        boolean stillVisual = cell.source() && !cell.falling() && !sloped;
        Identifier stillTexture = water ? WATER_STILL : LAVA_STILL;
        Identifier texture = water
                ? (stillVisual ? WATER_STILL : WATER_FLOW)
                : (stillVisual ? LAVA_STILL : LAVA_FLOW);
        MinecraftTextureFrames.View frame = MinecraftTextureFrames.frame(texture, age);
        if (!stillVisual) {
            // Vanilla flowing liquid frames are physically larger than the still
            // tile. Normalize them against the still frame so one Koil cell shows
            // one logical liquid tile rather than the old doubled/32px motif.
            MinecraftTextureFrames.View stillFrame = MinecraftTextureFrames.frame(stillTexture, age);
            frame = MinecraftTextureFrames.normalizeToReference(frame, stillFrame);
        }

        float red = 1.0F, green = 1.0F, blue = 1.0F, alpha = 1.0F;
        if (water) {
            int rgb = scene.environment().waterColor();
            red = ((rgb >> 16) & 0xFF) / 255.0F;
            green = ((rgb >> 8) & 0xFF) / 255.0F;
            blue = (rgb & 0xFF) / 255.0F;
            alpha = 0.78F;
        }
        SceneLightingSystem.LightColor sceneLight = scene.lighting().color(pos);
        red *= sceneLight.r();
        green *= sceneLight.g();
        blue *= sceneLight.b();

        // SceneRenderer composites hidden-axis slices back-to-front and clears
        // depth between them. Fluids therefore stay in local slice depth instead
        // of competing with another layer through GUI/OpenGL Z conventions.
        context.getMatrices().push();
        try {
            RenderSystem.enableBlend();
            if (frame.interpolate() && frame.interpolation() > 0.001F) {
                float interpolation = frame.interpolation();
                drawPass(context, texture, frame.x(), frame.y(), frame.width(), frame.height(),
                        frame.textureWidth(), frame.textureHeight(), left, right, fullTop, bottom,
                        width, fullHeight, pixels, leftHeight, rightHeight, flatTop, sloped,
                        red, green, blue, alpha * (1.0F - interpolation));
                drawPass(context, texture, frame.nextX(), frame.nextY(), frame.width(), frame.height(),
                        frame.textureWidth(), frame.textureHeight(), left, right, fullTop, bottom,
                        width, fullHeight, pixels, leftHeight, rightHeight, flatTop, sloped,
                        red, green, blue, alpha * interpolation);
            } else {
                drawPass(context, texture, frame.x(), frame.y(), frame.width(), frame.height(),
                        frame.textureWidth(), frame.textureHeight(), left, right, fullTop, bottom,
                        width, fullHeight, pixels, leftHeight, rightHeight, flatTop, sloped,
                        red, green, blue, alpha);
            }
        } finally {
            context.getMatrices().pop();
        }
    }

    private static void drawPass(DrawContext context, Identifier texture,
                                 int u, int v, int sourceWidth, int sourceHeight,
                                 int textureWidth, int textureHeight,
                                 int left, int right, int fullTop, int bottom,
                                 int width, int fullHeight, float pixels,
                                 float leftHeight, float rightHeight, int flatTop, boolean sloped,
                                 float red, float green, float blue, float alpha) {
        if (alpha <= 0.001F) return;
        RenderSystem.setShaderColor(red, green, blue, alpha);

        if (!sloped) {
            if (flatTop >= bottom) return;
            boolean clipped = flatTop > fullTop;
            if (!clipped) {
                drawFlowTexture(context, texture, left, fullTop, width, fullHeight,
                        u, v, sourceWidth, sourceHeight, textureWidth, textureHeight);
                return;
            }
            context.enableScissor(left, flatTop, right, bottom);
            try {
                drawFlowTexture(context, texture, left, fullTop, width, fullHeight,
                        u, v, sourceWidth, sourceHeight, textureWidth, textureHeight);
            } finally {
                context.disableScissor();
            }
            return;
        }

        // Draw one screen-pixel strip at a time while keeping the entire native
        // texture quad anchored to the cell. The scissor only changes the visible
        // top edge, so UVs stay continuous across the trapezoid instead of each
        // strip repeating/stretching the water texture.
        for (int column = 0; column < width; column++) {
            float t = (column + 0.5F) / width;
            float height = leftHeight + (rightHeight - leftHeight) * t;
            int columnTop = Math.round(bottom - pixels * Math.max(0.0F, Math.min(1.0F, height)));
            if (columnTop >= bottom) continue;
            int x0 = left + column;
            int x1 = Math.min(right, x0 + 1);
            if (x1 <= x0) continue;
            context.enableScissor(x0, columnTop, x1, bottom);
            try {
                drawFlowTexture(context, texture, left, fullTop, width, fullHeight,
                        u, v, sourceWidth, sourceHeight, textureWidth, textureHeight);
            } finally {
                context.disableScissor();
            }
        }
    }
    private static void drawFlowTexture(DrawContext context, Identifier texture,
                                        int x, int y, int width, int height,
                                        int u, int v, int sourceWidth, int sourceHeight,
                                        int textureWidth, int textureHeight) {
        // Never rotate Minecraft's water/lava texture. Direction is communicated
        // by the fluid surface geometry, matching the native visual language.
        context.drawTexture(texture, x, y, width, height,
                u, v, sourceWidth, sourceHeight, textureWidth, textureHeight);
    }

}
