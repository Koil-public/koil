package com.spirit.mixin.client.render;

import com.spirit.koil.api.automation.cli.AutomationPresenceState;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.joml.Matrix4f;

@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer<T extends Entity> {
    @Shadow @Final protected EntityRenderDispatcher dispatcher;

    @Shadow public abstract TextRenderer getTextRenderer();

    @Inject(method = "renderLabelIfPresent", at = @At("TAIL"))
    private void koil$renderAutomationUnderline(T entity, Text text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        if (!(entity instanceof PlayerEntity player) || !AutomationPresenceState.automationModeFor(player) || text == null) {
            return;
        }
        double distance = this.dispatcher.getSquaredDistanceToCamera(entity);
        if (distance > 4096.0D) {
            return;
        }

        TextRenderer textRenderer = getTextRenderer();
        int color = AutomationPresenceState.colorFor(player);
        int textWidth = textRenderer.getWidth(text);
        if (textWidth <= 0) {
            return;
        }
        int x = -textWidth / 2;
        int top = textRenderer.fontHeight;
        int bottom = top + 1;

        matrices.push();
        matrices.translate(0.0F, entity.getHeight() + 0.5F, 0.0F);
        matrices.multiply(this.dispatcher.getRotation());
        matrices.scale(-0.025F, -0.025F, 0.025F);
        matrices.translate(0.0F, 0.0F, 0.01F);
        drawUnderlineQuad(matrices.peek().getPositionMatrix(), vertexConsumers.getBuffer(RenderLayer.getTextBackgroundSeeThrough()), x - 1, x + textWidth + 1, top, bottom, color, light);
        matrices.pop();
    }

    private static void drawUnderlineQuad(Matrix4f matrix, VertexConsumer consumer, int left, int right, int top, int bottom, int color, int light) {
        int red = color >> 16 & 255;
        int green = color >> 8 & 255;
        int blue = color & 255;
        int alpha = color >> 24 & 255;
        if (alpha == 0) {
            alpha = 255;
        }
        consumer.vertex(matrix, left, bottom, 0.0F).color(red, green, blue, alpha).light(light).next();
        consumer.vertex(matrix, right, bottom, 0.0F).color(red, green, blue, alpha).light(light).next();
        consumer.vertex(matrix, right, top, 0.0F).color(red, green, blue, alpha).light(light).next();
        consumer.vertex(matrix, left, top, 0.0F).color(red, green, blue, alpha).light(light).next();
    }
}
