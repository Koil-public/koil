package com.spirit.mixin.client.gui.revamp.world;

import com.spirit.koil.api.design.KoilScreenBackgrounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CreateWorldScreen.class)
public class MixinCreateWorldScreen {
    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo info) {
        MinecraftClient client = MinecraftClient.getInstance();
        KoilScreenBackgrounds.render(context, client, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());
        context.fill(0, 0, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), KoilScreenBackgrounds.overlayColor(client));
    }

    @Inject(method = "renderBackgroundTexture", at = @At("HEAD"), cancellable = true)
    private void koil$renderSharedBackgroundTexture(DrawContext context, CallbackInfo info) {
        MinecraftClient client = MinecraftClient.getInstance();
        KoilScreenBackgrounds.render(context, client, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());
        context.fill(0, 0, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), KoilScreenBackgrounds.overlayColor(client));
        info.cancel();
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIFFIIII)V"
            )
    )
    private void koil$renderFooterSeparator(DrawContext context, Identifier texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight) {
        if (!KoilScreenBackgrounds.uiRedesignEnabled()) {
            context.drawTexture(texture, x, y, u, v, width, height, textureWidth, textureHeight);
            return;
        }
        context.fill(x, Math.max(0, y - 2), x + width, y + height + 2, 0x72000000);
        context.fill(x, y, x + width, y + 1, 0x9AFFFFFF);
    }
}
