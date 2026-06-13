package com.spirit.mixin.client.gui.revamp.world;

import com.spirit.koil.api.design.KoilScreenBackgrounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.ExperimentsScreen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ExperimentsScreen.class)
public class MixinExperimentsScreen {
    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIFFIIII)V"
            )
    )
    private void koil$renderSharedContentBackground(DrawContext context, Identifier texture, int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (KoilScreenBackgrounds.uiRedesignEnabled()) {
            context.fill(x, y, x + width, y + height, 0x65000000);
            return;
        }
        context.drawTexture(texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }
}
