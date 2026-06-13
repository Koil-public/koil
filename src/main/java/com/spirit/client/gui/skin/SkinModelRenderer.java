package com.spirit.client.gui.skin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;

public final class SkinModelRenderer {
    private SkinModelRenderer() {
    }

    public static void render(DrawContext context, Identifier texture, boolean slim, int centerX, int centerY, float scale, float yaw, float pitch, float roll) {
        render(context, texture, slim, centerX, centerY, scale, yaw, pitch, roll, true, true);
    }

    public static void render(DrawContext context, Identifier texture, boolean slim, int centerX, int centerY, float scale, float yaw, float pitch, float roll, boolean showBaseLayer, boolean showOverlayLayer) {
        render(context, texture, slim, centerX, centerY, scale, yaw, pitch, roll, showBaseLayer, showBaseLayer, showBaseLayer, showBaseLayer, showBaseLayer, showBaseLayer, showOverlayLayer, showOverlayLayer, showOverlayLayer, showOverlayLayer, showOverlayLayer, showOverlayLayer);
    }

    public static void render(DrawContext context, Identifier texture, boolean slim, int centerX, int centerY, float scale, float yaw, float pitch, float roll, boolean showBaseHead, boolean showBaseBody, boolean showBaseRightArm, boolean showBaseLeftArm, boolean showBaseRightLeg, boolean showBaseLeftLeg, boolean showOverlayHead, boolean showOverlayBody, boolean showOverlayRightArm, boolean showOverlayLeftArm, boolean showOverlayRightLeg, boolean showOverlayLeftLeg) {
        MinecraftClient client = MinecraftClient.getInstance();
        MatrixStack matrices = context.getMatrices();
        ModelPart modelPart = client.getEntityModelLoader().getModelPart(slim ? EntityModelLayers.PLAYER_SLIM : EntityModelLayers.PLAYER);
        PlayerEntityModel<PlayerEntity> model = new PlayerEntityModel<>(modelPart, slim);
        model.child = false;
        Identifier skin = texture == null ? new Identifier("minecraft", slim ? "textures/entity/alex.png" : "textures/entity/steve.png") : texture;
        model.setVisible(true);
        model.head.visible = showBaseHead;
        model.body.visible = showBaseBody;
        model.rightArm.visible = showBaseRightArm;
        model.leftArm.visible = showBaseLeftArm;
        model.rightLeg.visible = showBaseRightLeg;
        model.leftLeg.visible = showBaseLeftLeg;
        model.hat.visible = showOverlayHead;
        model.jacket.visible = showOverlayBody;
        model.rightSleeve.visible = showOverlayRightArm;
        model.leftSleeve.visible = showOverlayLeftArm;
        model.rightPants.visible = showOverlayRightLeg;
        model.leftPants.visible = showOverlayLeftLeg;
        model.head.pitch = 0.0F;
        model.head.yaw = 0.0F;
        model.head.roll = 0.0F;
        model.hat.pitch = 0.0F;
        model.hat.yaw = 0.0F;
        model.hat.roll = 0.0F;
        model.body.pitch = 0.0F;
        model.body.yaw = 0.0F;
        model.body.roll = 0.0F;
        model.jacket.pitch = 0.0F;
        model.jacket.yaw = 0.0F;
        model.jacket.roll = 0.0F;
        model.rightArm.pitch = 0.06F;
        model.leftArm.pitch = -0.06F;
        model.rightLeg.pitch = -0.035F;
        model.leftLeg.pitch = 0.035F;
        model.hat.copyTransform(model.head);
        model.jacket.copyTransform(model.body);
        model.rightSleeve.copyTransform(model.rightArm);
        model.leftSleeve.copyTransform(model.leftArm);
        model.rightPants.copyTransform(model.rightLeg);
        model.leftPants.copyTransform(model.leftLeg);
        matrices.push();
        matrices.translate(centerX, centerY, 360.0F);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(roll));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F + yaw));
        matrices.scale(-scale, scale, scale);
        matrices.translate(0.0F, -0.34F, 0.0F);
        VertexConsumerProvider.Immediate consumers = client.getBufferBuilders().getEntityVertexConsumers();
        model.render(matrices, consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(skin)), 15728880, OverlayTexture.DEFAULT_UV, 1.0F, 1.0F, 1.0F, 1.0F);
        consumers.draw();
        matrices.pop();
    }





    public static float wrap(float value) {
        while (value > 360.0F) {
            value -= 360.0F;
        }
        while (value < -360.0F) {
            value += 360.0F;
        }
        return value;
    }

    public static float clampPitch(float value) {
        return Math.max(-89.0F, Math.min(89.0F, value));
    }
}
