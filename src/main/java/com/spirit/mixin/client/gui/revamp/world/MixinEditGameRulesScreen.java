package com.spirit.mixin.client.gui.revamp.world;

import com.spirit.koil.api.design.KoilScreenBackgrounds;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.EditGameRulesScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EditGameRulesScreen.class)
public class MixinEditGameRulesScreen {
    @Inject(method = "render", at = @At("HEAD"))
    private void koil$renderSharedBackground(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo info) {
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
        KoilScreenBackgrounds.render(context, client, width, height);
        context.fill(0, 0, width, height, KoilScreenBackgrounds.overlayColor(client));
    }
}
