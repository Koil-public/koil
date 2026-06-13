package com.spirit.mixin.client.gui.revamp;

import com.spirit.koil.api.design.KoilScreenBackgrounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.TabNavigationWidget;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(TabNavigationWidget.class)
public class MixinTabNavigationWidget {
    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"
            )
    )
    private void koil$renderCreateWorldHeaderPanel(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        if (!koil$isCreateWorldScreen()) {
            context.fill(x1, y1, x2, y2, color);
            return;
        }
        context.fill(x1, y1, x2, y2, 0x70000000);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIFFIIII)V"
            )
    )
    private void koil$renderCreateWorldHeaderSeparator(DrawContext context, Identifier texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight) {
        if (!koil$isCreateWorldScreen()) {
            context.drawTexture(texture, x, y, u, v, width, height, textureWidth, textureHeight);
            return;
        }
        context.fill(x, y, x + width, y + height, 0x72000000);
        context.fill(x, y, x + width, y + 1, 0x88FFFFFF);
    }

    private boolean koil$isCreateWorldScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null
                && client.world == null
                && client.currentScreen instanceof CreateWorldScreen
                && KoilScreenBackgrounds.uiRedesignEnabled();
    }
}
