package com.spirit.koil.api.design.sprite.render;

import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.gameplay.BellSystem;
import com.spirit.koil.api.design.sprite.world.BlockCell;
import com.spirit.koil.api.design.sprite.world.Scene;
import net.minecraft.block.BellBlock;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.entity.BellBlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Detached bridge for Minecraft blocks whose visible model is owned wholly or
 * partly by a block-entity renderer.
 *
 * <p>Normal block entities continue through Minecraft's registered renderer.
 * Bells are special in vanilla: the support is baked BlockState geometry while
 * the moving bell body is a separate model. Koil reconstructs only that native
 * body from {@link BellBlockEntityRenderer#getTexturedModelData()} and applies
 * the vanilla swing equation from its detached scene timeline. This avoids a
 * fake World and avoids illegal access to BellBlockEntity's package-private
 * client animation fields.</p>
 */
public final class BlockEntityRenderer2D {
    private static final float BELL_BODY_Z_BIAS = -0.05F;
    private static final String BELL_BODY_PART = "bell_body";

    private record Cached(BlockState state, BlockEntity entity) { }

    private final Map<SceneCellPos, Cached> cache = new HashMap<>();
    private final Set<SceneCellPos> touched = new HashSet<>();
    private ModelPart bellBody;

    public void beginFrame() {
        touched.clear();
    }

    public void endFrame() {
        cache.keySet().removeIf(pos -> !touched.contains(pos));
    }

    /** Renders a builtin/entity model that fully replaces the baked block path. */
    public boolean renderBuiltinIfNative(DrawContext context, Scene scene, SceneCellPos pos, BlockCell cell) {
        if (cell != null && cell.blockState() != null && cell.blockState().getBlock() instanceof BellBlock) {
            return false;
        }
        return renderNative(context, scene, pos, cell, true);
    }

    /**
     * Renders an additive block-entity component. Bell uses Minecraft's native
     * bell-body model/texture plus exact vanilla motion, while other additive
     * renderers continue through Minecraft's registered BER dispatcher.
     */
    public boolean renderAdditiveIfNative(DrawContext context, Scene scene, SceneCellPos pos, BlockCell cell) {
        if (cell != null && cell.blockState() != null && cell.blockState().getBlock() instanceof BellBlock) {
            return renderBellBody(context, scene, pos);
        }
        return renderNative(context, scene, pos, cell, false);
    }

    private boolean renderBellBody(DrawContext context, Scene scene, SceneCellPos pos) {
        if (context == null || scene == null || pos == null || scene.bellSystem() == null) return false;
        ModelPart body = bellBody();
        if (body == null) return false;

        BellSystem.RenderState animation = scene.bellSystem().renderState(pos, scene.clock().gameRenderAlpha());
        float pitch = 0.0F;
        float roll = 0.0F;
        if (animation.ringing()) {
            float ticks = animation.ringTicks();
            // Exact vanilla BellBlockEntityRenderer swing function.
            float swing = MathHelper.sin(ticks / (float) Math.PI) / (4.0F + ticks / 3.0F);
            Direction side = animation.sideHit();
            if (side == Direction.NORTH) pitch = -swing;
            else if (side == Direction.SOUTH) pitch = swing;
            else if (side == Direction.EAST) roll = -swing;
            else if (side == Direction.WEST) roll = swing;
        }

        body.pitch = pitch;
        body.yaw = 0.0F;
        body.roll = roll;

        float pixels = scene.projection().cellPixels();
        float left = scene.projection().cellCenterScreenX(pos) - pixels * 0.5F;
        float bottom = scene.projection().cellCenterScreenY(pos) + pixels * 0.5F;

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        try {
            matrices.translate(left, bottom, BELL_BODY_Z_BIAS);
            switch (scene.projection().mode()) {
                case XY_SIDE -> { }
                case ZY_SIDE -> matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(90.0F));
                case XZ_TOP -> matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90.0F));
            }
            // Body first, baked support second. Flatten only Minecraft's hidden
            // scene-depth axis, not the visible model axes.
            matrices.scale(pixels, -pixels, 0.01F);
            VertexConsumer vertices = BellBlockEntityRenderer.BELL_BODY_TEXTURE.getVertexConsumer(
                    context.getVertexConsumers(), RenderLayer::getEntitySolid);
            body.render(matrices, vertices, LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            matrices.pop();
        }
    }

    private ModelPart bellBody() {
        if (bellBody != null) return bellBody;
        try {
            ModelPart root = BellBlockEntityRenderer.getTexturedModelData().createModel();
            bellBody = root.getChild(BELL_BODY_PART);
        } catch (RuntimeException ignored) {
            bellBody = null;
        }
        return bellBody;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean renderNative(DrawContext context, Scene scene, SceneCellPos pos, BlockCell cell,
                                 boolean requireBuiltin) {
        if (context == null || scene == null || pos == null || cell == null) return false;
        BlockState state = cell.blockState();
        if (state == null || state.isAir() || !(state.getBlock() instanceof BlockEntityProvider provider)) return false;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getBlockRenderManager() == null || client.getBlockEntityRenderDispatcher() == null) return false;

        BakedModel baked;
        try { baked = client.getBlockRenderManager().getModel(state); }
        catch (RuntimeException ignored) { return false; }
        if (baked == null || requireBuiltin != baked.isBuiltin()) return false;

        touched.add(pos);
        Cached cached = cache.get(pos);
        BlockEntity entity = cached == null || !cached.state().equals(state) ? null : cached.entity();
        if (entity == null) {
            try {
                entity = provider.createBlockEntity(new BlockPos(pos.x(), pos.y(), pos.depth()), state);
            } catch (RuntimeException ignored) {
                return false;
            }
            if (entity == null) return false;
            cache.put(pos, new Cached(state, entity));
        } else if (!entity.getCachedState().equals(state)) {
            entity.setCachedState(state);
            cache.put(pos, new Cached(state, entity));
        }

        BlockEntityRenderDispatcher dispatcher = client.getBlockEntityRenderDispatcher();
        BlockEntityRenderer renderer;
        try { renderer = dispatcher.get(entity); }
        catch (RuntimeException ignored) { return false; }
        if (renderer == null) return false;

        float pixels = scene.projection().cellPixels();
        float left = scene.projection().cellCenterScreenX(pos) - pixels * 0.5F;
        float bottom = scene.projection().cellCenterScreenY(pos) + pixels * 0.5F;

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        try {
            matrices.translate(left, bottom, 0.0F);
            switch (scene.projection().mode()) {
                case XY_SIDE -> { }
                case ZY_SIDE -> matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(90.0F));
                case XZ_TOP -> matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90.0F));
            }
            matrices.scale(pixels, -pixels, Math.max(1.0F, pixels * 0.20F));
            renderer.render(entity, scene.clock().gameRenderAlpha(), matrices, context.getVertexConsumers(),
                    LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            matrices.pop();
        }
    }

    public void reset() {
        cache.clear();
        touched.clear();
        bellBody = null;
    }
}
