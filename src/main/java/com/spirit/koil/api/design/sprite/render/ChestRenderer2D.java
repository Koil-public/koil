package com.spirit.koil.api.design.sprite.render;

import com.spirit.koil.api.design.sprite.core.SceneCellPos;
import com.spirit.koil.api.design.sprite.world.BlockCell;
import com.spirit.koil.api.design.sprite.world.Scene;
import net.minecraft.block.AbstractChestBlock;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.TexturedRenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.entity.ChestBlockEntityRenderer;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;

/**
 * Worldless chest-family renderer using Minecraft's own chest model definitions
 * and chest texture atlas. The normal BlockEntityRenderer path asks chest logic
 * for world/neighbor information before it can choose models/textures; in a
 * detached Koil scene that can legitimately return nothing or throw. This class
 * renders the same vanilla lid/base/latch geometry directly from
 * ChestBlockEntityRenderer's public TexturedModelData factories.
 */
public final class ChestRenderer2D {
    private record ChestModel(ModelPart root, ModelPart lid, ModelPart latch, ModelPart base) { }

    private ChestModel single;
    private ChestModel left;
    private ChestModel right;

    public boolean renderIfChest(DrawContext context, Scene scene, SceneCellPos pos, BlockCell cell) {
        if (context == null || scene == null || pos == null || cell == null) return false;
        BlockState state = cell.blockState();
        if (state == null || state.isAir() || !(state.getBlock() instanceof AbstractChestBlock<?>)) return false;
        if (!(state.getBlock() instanceof BlockEntityProvider provider)) return false;

        BlockEntity entity;
        try {
            entity = provider.createBlockEntity(new BlockPos(pos.x(), pos.y(), pos.depth()), state);
        } catch (RuntimeException ignored) {
            return false;
        }
        if (entity == null) return false;

        ChestType chestType = state.contains(Properties.CHEST_TYPE)
                ? state.get(Properties.CHEST_TYPE) : ChestType.SINGLE;
        ChestModel model = switch (chestType) {
            case LEFT -> leftModel();
            case RIGHT -> rightModel();
            default -> singleModel();
        };
        if (model == null) return false;

        SpriteIdentifier texture;
        try {
            texture = TexturedRenderLayers.getChestTextureId(entity, chestType, false);
        } catch (RuntimeException ignored) {
            return false;
        }
        if (texture == null) return false;

        VertexConsumer vertices;
        try {
            vertices = texture.getVertexConsumer(context.getVertexConsumers(), RenderLayer::getEntityCutout);
        } catch (RuntimeException ignored) {
            return false;
        }
        if (vertices == null) return false;

        float pixels = scene.projection().cellPixels();
        float leftPx = scene.projection().cellCenterScreenX(pos) - pixels * 0.5F;
        float bottomPx = scene.projection().cellCenterScreenY(pos) + pixels * 0.5F;
        Direction facing = state.contains(Properties.HORIZONTAL_FACING)
                ? state.get(Properties.HORIZONTAL_FACING) : Direction.NORTH;

        MatrixStack matrices = context.getMatrices();
        matrices.push();
        try {
            // Draw native chest model units into one Koil scene cell. The native
            // model itself has the slight inset used by Minecraft, so we do not
            // substitute a flat block texture or hand-authored chest silhouette.
            matrices.translate(leftPx, bottomPx, 0.0F);
            matrices.scale(pixels, -pixels, Math.max(1.0F, pixels * 0.25F));
            matrices.translate(0.5F, 0.5F, 0.5F);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-facing.asRotation()));
            matrices.translate(-0.5F, -0.5F, -0.5F);

            // Chest lid state is scene-native and detached from a real World. Match
            // vanilla's cubic easing before applying the native model-part pitch.
            float progress = scene.chestSystem().lidProgress(pos, scene.clock().renderAlpha());
            float inverse = 1.0F - Math.max(0.0F, Math.min(1.0F, progress));
            float eased = 1.0F - inverse * inverse * inverse;
            float lidPitch = -(eased * ((float) Math.PI / 2.0F));
            model.lid().pitch = lidPitch;
            model.latch().pitch = lidPitch;
            model.lid().render(matrices, vertices,
                    LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
            model.latch().render(matrices, vertices,
                    LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
            model.base().render(matrices, vertices,
                    LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            matrices.pop();
        }
    }

    private ChestModel singleModel() {
        if (single == null) single = create(ChestBlockEntityRenderer.getSingleTexturedModelData());
        return single;
    }

    private ChestModel leftModel() {
        if (left == null) left = create(ChestBlockEntityRenderer.getLeftDoubleTexturedModelData());
        return left;
    }

    private ChestModel rightModel() {
        if (right == null) right = create(ChestBlockEntityRenderer.getRightDoubleTexturedModelData());
        return right;
    }

    private static ChestModel create(TexturedModelData data) {
        if (data == null) return null;
        try {
            ModelPart root = data.createModel();
            return new ChestModel(root, root.getChild("lid"), root.getChild("lock"), root.getChild("bottom"));
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
