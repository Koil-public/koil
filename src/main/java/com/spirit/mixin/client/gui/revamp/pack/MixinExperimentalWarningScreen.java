package com.spirit.mixin.client.gui.revamp.pack;

import com.spirit.koil.api.design.KoilScreenBackgrounds;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.pack.ExperimentalWarningScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(ExperimentalWarningScreen.class)
public class MixinExperimentalWarningScreen {
    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo info) {
        MinecraftClient client = MinecraftClient.getInstance();
        KoilScreenBackgrounds.render(context, client, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());
        context.fill(0, 0, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), KoilScreenBackgrounds.overlayColor(client));
    }
}
