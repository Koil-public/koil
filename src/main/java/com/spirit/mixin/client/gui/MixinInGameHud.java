package com.spirit.mixin.client.gui;

import com.spirit.Client;
import com.spirit.client.gui.EntityInspectionTooltipBuilder;
import com.spirit.client.gui.debug.F3OverlayRenderer;
import com.spirit.koil.api.automation.cli.AutomationChatHudRenderer;
import com.spirit.koil.api.stats.global.client.MarketHudRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.EntityHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

@Mixin(InGameHud.class)
public abstract class MixinInGameHud {
    @Shadow @Final private MinecraftClient client;

    @Inject(method = "render", at = @At("TAIL"))
    private void koil$renderAutomationChatHud(DrawContext context, float tickDelta, CallbackInfo ci) {
        AutomationChatHudRenderer.render(context, client);
        MarketHudRenderer.render(context, client);
        context.getMatrices().push();
        context.getMatrices().translate(0.0F, 0.0F, 800.0F);
        F3OverlayRenderer.render(context, client);
        context.getMatrices().pop();
        renderEntityInspectionTooltip(context);
    }

    private void renderEntityInspectionTooltip(DrawContext context) {
        if (client == null || client.player == null || client.getWindow() == null) {
            return;
        }
        int code = InputUtil.fromTranslationKey(Client.KOIL_UTIL_NBT_KEY.getBoundKeyTranslationKey()).getCode();
        if (!InputUtil.isKeyPressed(client.getWindow().getHandle(), code)) {
            return;
        }
        if (!(client.crosshairTarget instanceof EntityHitResult hitResult)) {
            return;
        }
        Entity entity = hitResult.getEntity();
        if (entity == null) {
            return;
        }
        List<net.minecraft.text.Text> lines = EntityInspectionTooltipBuilder.build(entity);
        if (lines.isEmpty()) {
            return;
        }
        int tooltipX = Math.min(client.getWindow().getScaledWidth() - 340, (client.getWindow().getScaledWidth() / 2) + 18);
        int tooltipY = Math.max(18, (client.getWindow().getScaledHeight() / 2) - 72);
        context.drawTooltip(client.textRenderer, lines, Optional.empty(), tooltipX, tooltipY);
    }
}
