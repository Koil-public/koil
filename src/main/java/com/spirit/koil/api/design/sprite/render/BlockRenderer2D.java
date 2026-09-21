package com.spirit.koil.api.design.sprite.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.core.SceneProjection;
import com.spirit.koil.api.design.sprite.systems.SceneLightingSystem;
import com.spirit.koil.api.design.sprite.world.BlockCell;
import com.spirit.koil.api.design.sprite.world.BlockGrid;
import com.spirit.koil.api.design.sprite.world.Scene;
import net.minecraft.block.AbstractChestBlock;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BellBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.world.FoliageColors;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scene-native static block renderer.
 *
 * <p>Every ordinary block is drawn from Minecraft's baked BlockState model with
 * its original quad geometry and original atlas UVs. Koil only projects those
 * vertices into the selected 2D scene plane. It does not substitute a texture,
 * stretch a whole sprite over each model element, or create a second visual-only
 * state.</p>
 */
public final class BlockRenderer2D {
    private static final float EDGE_SURFACE_PIXELS = 2.0F;
    private static final float STAIR_PROFILE_EDGE_PIXELS = 1.25F;
    private static final float PROJECTED_EPSILON = 0.00008F;

    private final MinecraftBakedModel2DResolver modelResolver = new MinecraftBakedModel2DResolver();
    private final ChestRenderer2D chestRenderer = new ChestRenderer2D();
    private final BlockEntityRenderer2D blockEntityRenderer = new BlockEntityRenderer2D();

    private enum SurfacePass {
        ALL,
        OPAQUE_TERRAIN,
        FOREGROUND_OVERLAY
    }

    public void render(DrawContext context, Scene scene) {
        render(context, scene, SceneDepthCompositor.build(scene), null, SurfacePass.ALL);
    }

    public void render(DrawContext context, Scene scene, Integer depthFilter) {
        render(context, scene, SceneDepthCompositor.build(scene, depthFilter), depthFilter, SurfacePass.ALL);
    }

    void render(DrawContext context, Scene scene, SceneDepthCompositor.DepthMask depthMask) {
        render(context, scene, depthMask, null, SurfacePass.ALL);
    }

    void renderOpaqueTerrain(DrawContext context, Scene scene, SceneDepthCompositor.DepthMask depthMask) {
        render(context, scene, depthMask, null, SurfacePass.OPAQUE_TERRAIN);
    }

    void renderForegroundOverlays(DrawContext context, Scene scene, SceneDepthCompositor.DepthMask depthMask) {
        render(context, scene, depthMask, null, SurfacePass.FOREGROUND_OVERLAY);
    }

    /**
     * Renders one hidden-axis slice. SceneRenderer owns inter-layer composition
     * and resets framebuffer depth between slices so Minecraft model depth is
     * only compared inside this layer.
     */
    void renderDepth(DrawContext context, Scene scene,
                     SceneDepthCompositor.DepthMask depthMask, int depth) {
        render(context, scene, depthMask, depth, SurfacePass.ALL);
    }

    private void render(DrawContext context, Scene scene, SceneDepthCompositor.DepthMask depthMask,
                        Integer depthFilter, SurfacePass surfacePass) {
        if (context == null || scene == null || scene.blocks().blockCount() == 0) return;
        // UI screens and effect renderers may leave a shader-color multiplier in
        // RenderSystem state. Scene depth must never inherit that tint/brightness.
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        blockEntityRenderer.beginFrame();
        if (depthMask == null) depthMask = SceneDepthCompositor.build(scene, depthFilter);

        // SceneDepthCompositor already resolved and cached the exposed surface.
        // Do not rescan the full authoritative terrain body every render pass.
        // A depth-filtered diagnostic render remains a rare editor path and may
        // scan the grid directly; the normal all-layer compositor is O(visible).
        List<BlockGrid.Entry> entries = new ArrayList<>();
        if (depthFilter == null) {
            switch (surfacePass) {
                case OPAQUE_TERRAIN -> entries.addAll(depthMask.opaqueEntries());
                case FOREGROUND_OVERLAY -> entries.addAll(depthMask.overlayEntries());
                case ALL -> {
                    entries.addAll(depthMask.opaqueEntries());
                    entries.addAll(depthMask.overlayEntries());
                }
            }
        } else {
            for (BlockGrid.Entry entry : scene.blocks().entries()) {
                if (entry == null || entry.position() == null || entry.cell() == null) continue;
                BlockState state = entry.cell().blockState();
                if (state == null || state.isAir()) continue;
                if (scene.projection().depthCoordinate(entry.position()) != depthFilter) continue;
                if (depthMask.renderBlock(scene, entry.position(), state)) entries.add(entry);
            }
        }
        float viewportPad = Math.max(2.0F, scene.projection().cellPixels() * 1.5F);
        entries.removeIf(e -> !insideViewport(context, scene, e.position(), viewportPad));

        entries.sort(Comparator.comparingInt((BlockGrid.Entry e) -> scene.projection().depthCoordinate(e.position()))
                .thenComparingInt(e -> -e.position().y())
                .thenComparingInt(e -> e.position().x()));

        for (BlockGrid.Entry entry : entries) {
            renderCell(context, scene, entry.position(), entry.cell());
        }
        blockEntityRenderer.endFrame();
        // Exact baked quads use DrawContext's shared immediate consumer. Flush once
        // after the full block pass, not once per quad/block.
        context.draw();
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

    /**
     * Editor-only placement ghost. It projects the selected authoritative
     * BlockState with Minecraft's baked UV/model data without inserting a
     * temporary cell into the scene or triggering neighbor/gameplay updates.
     */
    public boolean renderGhost(DrawContext context, Scene scene, SceneCellPos pos, BlockState state, float alpha) {
        if (context == null || scene == null || pos == null || state == null || state.isAir()) return false;
        long seed = renderingSeed(pos, state);
        List<MinecraftBakedModel2DResolver.Part> parts;
        if (state.getBlock() instanceof FenceGateBlock) {
            parts = modelResolver.resolveFenceGate(state, scene.projection().mode(), seed);
        } else if (state.getBlock() instanceof AbstractRailBlock || state.getBlock() instanceof RedstoneWireBlock) {
            parts = modelResolver.resolveWithEdgeSurfaces(state, scene.projection().mode(), seed);
        } else if (state.getBlock() instanceof StairsBlock) {
            parts = modelResolver.resolveWithProfileEdges(state, scene.projection().mode(), seed);
        } else {
            parts = modelResolver.resolveDepthComposite(state, scene.projection().mode(), seed);
            if (parts.isEmpty()) parts = modelResolver.resolve(state, scene.projection().mode(), seed);
        }
        if (parts.isEmpty()) return false;
        float pixels = scene.projection().cellPixels();
        float originX = scene.projection().cellCenterScreenX(pos) - pixels * 0.5F;
        float originY = scene.projection().cellCenterScreenY(pos) - pixels * 0.5F;
        int a = Math.max(24, Math.min(210, Math.round(Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F)));
        MatrixStack.Entry matrices = context.getMatrices().peek();
        VertexConsumer consumer = context.getVertexConsumers().getBuffer(RenderLayer.getTranslucent());
        for (MinecraftBakedModel2DResolver.Part part : parts) {
            if (part == null || part.sprite() == null || part.vertices().size() != 4) continue;
            int tint = resolveTint(scene, pos, state, part.tintIndex());
            float shade = shadeFactor(state, part);
            float r = (((tint >> 16) & 0xFF) / 255.0F) * shade;
            float g = (((tint >> 8) & 0xFF) / 255.0F) * shade;
            float b = ((tint & 0xFF) / 255.0F) * shade;
            List<MinecraftBakedModel2DResolver.Vertex> vertices = part.vertices();
            if (part.facesAwayFromCamera()) vertices = List.of(vertices.get(3), vertices.get(2), vertices.get(1), vertices.get(0));
            for (MinecraftBakedModel2DResolver.Vertex vertex : vertices) {
                float x = originX + vertex.screenX() * pixels;
                float y = originY + vertex.screenY() * pixels;
                float z = part.hiddenDepth() * 0.01F + 0.001F;
                emit(consumer, matrices, x, y, z, r, g, b, vertex.u(), vertex.v(),
                        part.normalX(), part.normalY(), part.normalZ(), a);
            }
        }
        // Caller owns the flush so editor layer-preview ghosts can batch many
        // native model projections into a single draw pass.
        return true;
    }


    private void renderCell(DrawContext context, Scene scene, SceneCellPos pos,
                            BlockCell cell) {
        if (cell == null) return;
        BlockState state = cell.blockState();
        if (state == null || state.isAir()) return;
        SceneLightingSystem.LightColor sceneLight = scene.lighting().color(pos);
        SceneLightingSystem.ProjectedCorners lightCorners = scene.lighting().projectedCorners(pos);
        // Chest-family renderers query world/neighbor state in vanilla before
        // rendering. Use the same native chest model definitions/atlas directly
        // so normal + ender chests remain visible in a detached Koil scene.
        // Native model-part/block-entity renderers use their own vertex paths. Flush
        // previously batched baked blocks first, then tint and flush the native
        // model while the RGB scene-light uniform is active.
        if (state.getBlock() instanceof AbstractChestBlock<?>
                && renderSpecialWithLight(context, sceneLight,
                () -> chestRenderer.renderIfChest(context, scene, pos, cell))) {
            return;
        }
        // Builtin/entity models fully replace the baked path. Avoid entering the
        // flush/tint path for ordinary blocks that cannot own a block entity.
        if (state.hasBlockEntity() && renderSpecialWithLight(context, sceneLight,
                () -> blockEntityRenderer.renderBuiltinIfNative(context, scene, pos, cell))) {
            return;
        }

        // Bell is a native two-part composite: its body is a BER while the mount
        // is baked BlockState geometry. In a flattened 2D projection the hidden-Z
        // separation disappears, so drawing the body after the support makes it
        // incorrectly paint over the mount when rotated. Render/flush the body
        // first, then let the actual baked support occlude it where appropriate.
        boolean bellComposite = state.getBlock() instanceof BellBlock;
        if (bellComposite) {
            renderSpecialWithLight(context, sceneLight,
                    () -> blockEntityRenderer.renderAdditiveIfNative(context, scene, pos, cell));
        }

        boolean thinNativeSurface = state.getBlock() instanceof RedstoneWireBlock
                || state.getBlock() instanceof AbstractRailBlock;
        boolean stairProfile = state.getBlock() instanceof StairsBlock;
        RenderLayer nativeLayer = nativeLayer(state);
        boolean translucentVolume = nativeLayer == RenderLayer.getTranslucent();
        long seed = renderingSeed(pos, state);
        List<MinecraftBakedModel2DResolver.Part> parts;
        if (state.getBlock() instanceof FenceGateBlock) {
            parts = modelResolver.resolveFenceGate(state, scene.projection().mode(), seed);
        } else if (thinNativeSurface) {
            parts = modelResolver.resolveWithEdgeSurfaces(state, scene.projection().mode(), seed);
        } else if (stairProfile) {
            parts = modelResolver.resolveWithProfileEdges(state, scene.projection().mode(), seed);
        } else if (translucentVolume) {
            // Glass/ice-like blocks keep only the camera-facing volume surface so
            // alpha is not doubled by front+back faces in a flat UI compositor.
            parts = modelResolver.resolve(state, scene.projection().mode(), seed);
        } else {
            // Gates, doors, stairs, walls, fences and other opaque/cutout models
            // need the full baked geometry depth-composited into the 2D plane.
            parts = modelResolver.resolveDepthComposite(state, scene.projection().mode(), seed);
        }
        if (parts.isEmpty()) {
            if (!bellComposite && state.hasBlockEntity()) {
                renderSpecialWithLight(context, sceneLight,
                        () -> blockEntityRenderer.renderAdditiveIfNative(context, scene, pos, cell));
            }
            return;
        }

        float pixels = scene.projection().cellPixels();
        float originX = scene.projection().cellCenterScreenX(pos) - pixels * 0.5F;
        float originY = scene.projection().cellCenterScreenY(pos) - pixels * 0.5F;
        // Inter-layer ordering is owned by SceneRenderer. Keep native model
        // depth local to the current slice so large/negative layer numbers can
        // never push valid terrain outside the GUI depth range.
        float layerZ = 0.0F;

        for (MinecraftBakedModel2DResolver.Part part : parts) {
            if (part == null || part.sprite() == null || part.vertices().size() != 4) continue;
            int tint = resolveTint(scene, pos, state, part.tintIndex());
            if (part.projectedArea() <= PROJECTED_EPSILON && (thinNativeSurface || stairProfile)) {
                float edgePixels = stairProfile ? STAIR_PROFILE_EDGE_PIXELS : EDGE_SURFACE_PIXELS;
                renderEdgeSurfacePart(context, lightCorners, state, scene.projection().mode(),
                        originX, originY, pixels, part, tint, edgePixels, layerZ);
            } else {
                renderProjectedPart(context, lightCorners, state, originX, originY, pixels, part, tint, layerZ);
            }
        }
        // Other additive block-entity renderers retain their native post-baked order.
        // Bell is deliberately pre-composited above because hidden depth collapses
        // in Koil's side-view projection.
        if (!bellComposite && state.hasBlockEntity()) {
            renderSpecialWithLight(context, sceneLight,
                    () -> blockEntityRenderer.renderAdditiveIfNative(context, scene, pos, cell));
        }
    }

    /** Draws the baked quad at its exact projected vertices with its baked atlas UVs. */
    private void renderProjectedPart(DrawContext context, SceneLightingSystem.ProjectedCorners lightCorners,
                                     BlockState state, float originX, float originY, float pixels,
                                     MinecraftBakedModel2DResolver.Part part, int tint, float layerZ) {
        float shade = shadeFactor(state, part);
        float baseR = (((tint >> 16) & 0xFF) / 255.0F) * shade;
        float baseG = (((tint >> 8) & 0xFF) / 255.0F) * shade;
        float baseB = ((tint & 0xFF) / 255.0F) * shade;

        MatrixStack.Entry matrices = context.getMatrices().peek();
        VertexConsumer consumer = vertexConsumer(context, state);
        if (consumer == null) return;
        List<MinecraftBakedModel2DResolver.Vertex> vertices = part.vertices();
        // Baked quad winding is defined for Minecraft's 3D camera/culling. Once
        // collapsed into Koil's orthographic 2D plane, a perfectly valid far-side
        // gate/door/stair quad can become back-facing and disappear. Reverse only
        // those projected quads so the complete native model survives projection.
        if (part.facesAwayFromCamera()) {
            vertices = List.of(vertices.get(3), vertices.get(2), vertices.get(1), vertices.get(0));
        }
        emitProjectedQuad(consumer, matrices, lightCorners, originX, originY, pixels, part, vertices,
                baseR, baseG, baseB, layerZ);

        // Fence-gate templates contain many narrow planes/posts. On Minecraft's
        // real 3D camera each plane gets a natural front/back result. Koil flattens
        // hidden depth into one UI plane, so gate pieces can still be discarded by
        // the native cull state after projection. Emit the opposite winding too.
        if (state.getBlock() instanceof FenceGateBlock) {
            List<MinecraftBakedModel2DResolver.Vertex> reverse = List.of(
                    vertices.get(3), vertices.get(2), vertices.get(1), vertices.get(0));
            emitProjectedQuad(consumer, matrices, lightCorners, originX, originY, pixels, part, reverse,
                    baseR, baseG, baseB, layerZ + 0.00001F);
        }
    }

    private static void emitProjectedQuad(VertexConsumer consumer, MatrixStack.Entry matrices,
                                          SceneLightingSystem.ProjectedCorners lightCorners,
                                          float originX, float originY, float pixels,
                                          MinecraftBakedModel2DResolver.Part part,
                                          List<MinecraftBakedModel2DResolver.Vertex> vertices,
                                          float baseR, float baseG, float baseB, float zOffset) {
        for (MinecraftBakedModel2DResolver.Vertex vertex : vertices) {
            float x = originX + vertex.screenX() * pixels;
            float y = originY + vertex.screenY() * pixels;
            float z = part.hiddenDepth() * 0.01F + zOffset;
            SceneLightingSystem.LightColor light = lightCorners.sample(vertex.screenX(), vertex.screenY());
            emit(consumer, matrices, x, y, z,
                    baseR * light.r(), baseG * light.g(), baseB * light.b(),
                    vertex.u(), vertex.v(), part.normalX(), part.normalY(), part.normalZ());
        }
    }


    /**
     * A truly horizontal Minecraft surface is mathematically edge-on in XY/ZY
     * side view. Redstone dust and flat rails would therefore disappear. Koil
     * preserves the native baked texture/UV and compresses only the hidden depth
     * axis into a two-pixel strip. Vertical redstone-up quads remain ordinary
     * projected baked quads.
     */
    private void renderEdgeSurfacePart(DrawContext context, SceneLightingSystem.ProjectedCorners lightCorners,
                                       BlockState state, SceneProjection.Mode projectionMode,
                                       float originX, float originY, float pixels,
                                       MinecraftBakedModel2DResolver.Part part, int tint,
                                       float requestedThickness, float layerZ) {
        float thickness = Math.max(1.0F, Math.min(requestedThickness, pixels * 0.125F));
        float thicknessNorm = thickness / Math.max(1.0F, pixels);
        float base = 0.0F;
        for (MinecraftBakedModel2DResolver.Vertex vertex : part.vertices()) base += vertex.screenY();
        base *= 0.25F;

        float shade = shadeFactor(state, part);
        float baseR = (((tint >> 16) & 0xFF) / 255.0F) * shade;
        float baseG = (((tint >> 8) & 0xFF) / 255.0F) * shade;
        float baseB = ((tint & 0xFF) / 255.0F) * shade;
        MatrixStack.Entry matrices = context.getMatrices().peek();
        VertexConsumer consumer = vertexConsumer(context, state);
        if (consumer == null) return;

        for (MinecraftBakedModel2DResolver.Vertex vertex : part.vertices()) {
            float collapsed = switch (projectionMode) {
                case XY_SIDE -> vertex.modelZ();
                case ZY_SIDE -> vertex.modelX();
                case XZ_TOP -> vertex.modelY();
            };
            float screenY = base - thicknessNorm + (1.0F - collapsed) * thicknessNorm;
            float x = originX + vertex.screenX() * pixels;
            float y = originY + screenY * pixels;
            float z = layerZ + part.hiddenDepth() * 0.01F;
            SceneLightingSystem.LightColor light = lightCorners.sample(vertex.screenX(), screenY);
            emit(consumer, matrices, x, y, z,
                    baseR * light.r(), baseG * light.g(), baseB * light.b(),
                    vertex.u(), vertex.v(), part.normalX(), part.normalY(), part.normalZ());
        }
    }

    @FunctionalInterface
    private interface SpecialRenderer { boolean render(); }

    private static boolean renderSpecialWithLight(DrawContext context, SceneLightingSystem.LightColor light,
                                                  SpecialRenderer renderer) {
        if (context == null || renderer == null) return false;
        SceneLightingSystem.LightColor applied = light == null
                ? SceneLightingSystem.LightColor.WHITE : light;
        // Flush baked geometry before changing the shader-color uniform, otherwise
        // an already-buffered block could inherit the next block entity's light.
        context.draw();
        RenderSystem.setShaderColor(applied.r(), applied.g(), applied.b(), 1.0F);
        boolean rendered;
        try {
            rendered = renderer.render();
            if (rendered) context.draw();
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        return rendered;
    }

    private static VertexConsumer vertexConsumer(DrawContext context, BlockState state) {
        if (context == null || state == null) return null;
        return context.getVertexConsumers().getBuffer(nativeLayer(state));
    }

    private static RenderLayer nativeLayer(BlockState state) {
        if (state == null) return RenderLayer.getTranslucent();
        try { return RenderLayers.getBlockLayer(state); }
        catch (RuntimeException ignored) { return RenderLayer.getTranslucent(); }
    }

    private static void emit(VertexConsumer consumer, MatrixStack.Entry matrices,
                             float x, float y, float z,
                             float red, float green, float blue,
                             float u, float v,
                             float normalX, float normalY, float normalZ) {
        emit(consumer, matrices, x, y, z, red, green, blue, u, v, normalX, normalY, normalZ, 255);
    }

    private static void emit(VertexConsumer consumer, MatrixStack.Entry matrices,
                             float x, float y, float z,
                             float red, float green, float blue,
                             float u, float v,
                             float normalX, float normalY, float normalZ, int alpha) {
        // Native block render layers use the BLOCK vertex format:
        // position, color, UV, light, normal. Overlay belongs to entity formats
        // and must not be emitted here.
        consumer.vertex(matrices.getPositionMatrix(), x, y, z)
                .color(clampColor(red), clampColor(green), clampColor(blue), Math.max(0, Math.min(255, alpha)))
                .texture(u, v)
                .light(LightmapTextureManager.MAX_LIGHT_COORDINATE)
                .normal(matrices.getNormalMatrix(), normalX, normalY, normalZ)
                .next();
    }

    private static int resolveTint(Scene scene, SceneCellPos pos, BlockState state, int tintIndex) {
        if (state == null || tintIndex < 0) return 0xFFFFFF;
        if (state.getBlock() instanceof RedstoneWireBlock && state.contains(Properties.POWER)) {
            return RedstoneWireBlock.getWireColor(state.get(Properties.POWER)) & 0xFFFFFF;
        }

        // Fixed vanilla leaf colors do not use the biome colormap. Resolve them
        // before the generic LEAVES fallback so spruce/birch/mangrove remain
        // faithful even in a detached scene with no ClientWorld.
        if (state.isOf(Blocks.SPRUCE_LEAVES)) return FoliageColors.getSpruceColor() & 0xFFFFFF;
        if (state.isOf(Blocks.BIRCH_LEAVES)) return FoliageColors.getBirchColor() & 0xFFFFFF;
        if (state.isOf(Blocks.MANGROVE_LEAVES)) return FoliageColors.getMangroveColor() & 0xFFFFFF;

        // Biome-tinted families must resolve against Koil's detached environment
        // before calling BlockColors with a null world. Vanilla's provider uses a
        // fallback color when world/pos are absent, which would erase the selected
        // biome tint from oak leaves, grass and similar vegetation.
        if (scene != null && scene.environment() != null) {
            if (usesBiomeFoliageTint(state)) {
                return scene.environment().foliageColor();
            }
            if (usesBiomeGrassTint(state)) {
                double x = pos == null ? 0.0D : pos.x();
                double z = pos == null ? 0.0D : pos.depth();
                return scene.environment().grassColorAt(x, z);
            }
        }

        // Fixed-color vanilla/modded providers do not need biome context, so they
        // can still own every other tint index directly.
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getBlockColors() != null) {
            try {
                int color = client.getBlockColors().getColor(state, null, null, tintIndex);
                if (color != -1) return color & 0xFFFFFF;
            } catch (RuntimeException ignored) { }
        }
        return 0xFFFFFF;
    }

    private static boolean usesBiomeFoliageTint(BlockState state) {
        if (state == null) return false;
        // These are the vanilla foliage families whose BlockColorProvider samples
        // the biome foliage resolver. Do not use the broad #leaves tag here:
        // cherry/azalea families intentionally retain their own native/fixed color.
        return state.isOf(Blocks.OAK_LEAVES)
                || state.isOf(Blocks.JUNGLE_LEAVES)
                || state.isOf(Blocks.ACACIA_LEAVES)
                || state.isOf(Blocks.DARK_OAK_LEAVES)
                || state.isOf(Blocks.VINE);
    }

    private static boolean usesBiomeGrassTint(BlockState state) {
        if (state == null) return false;
        return state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.GRASS)
                || state.isOf(Blocks.FERN)
                || state.isOf(Blocks.TALL_GRASS)
                || state.isOf(Blocks.LARGE_FERN)
                || state.isOf(Blocks.SUGAR_CANE);
    }

    private static float shadeFactor(BlockState state, MinecraftBakedModel2DResolver.Part part) {
        if (part == null || !part.shade()) return 1.0F;
        if (state == null || !(state.getBlock() instanceof StairsBlock)) return 1.0F;

        // Stair-only readability shading. This is local MODEL geometry shading,
        // never scene-depth shading, so layer 1 remains identical in brightness
        // to layer 0 for the same stair state.
        Direction face = part.face();
        float directional = switch (face == null ? Direction.SOUTH : face) {
            case DOWN -> 0.60F;
            case UP -> 1.0F;
            case NORTH, SOUTH -> 0.88F;
            case WEST, EAST -> 0.74F;
        };

        // A stair facing into the hidden axis otherwise collapses visually into a
        // plank-textured square. Baked element depth separates the riser/tread
        // volumes without changing the underlying Minecraft texture.
        float localDepth = Math.max(0.0F, Math.min(1.0F, part.hiddenDepth()));
        return directional * (0.74F + 0.26F * localDepth);
    }

    private static int clampColor(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0F)));
    }

    private static long renderingSeed(SceneCellPos pos, BlockState state) {
        long seed = 31L * pos.x() + 17L * pos.y() + 13L * pos.depth();
        try {
            seed ^= state.getRenderingSeed(new net.minecraft.util.math.BlockPos(pos.x(), pos.y(), pos.depth()));
        } catch (RuntimeException ignored) {
            seed ^= state.hashCode();
        }
        return seed;
    }
}
