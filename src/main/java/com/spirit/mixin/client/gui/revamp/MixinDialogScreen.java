package com.spirit.mixin.client.gui.revamp;

import com.spirit.koil.api.design.DesignLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DialogScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DialogScreen.class)
public class MixinDialogScreen {
    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo info) {
        MinecraftClient client = MinecraftClient.getInstance();
        context.drawTexture(DesignLoader.getLoadingTexture(), 0, 0, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), 0.0F, 0.0F, 319, 192, 319, 192);
    }
}
