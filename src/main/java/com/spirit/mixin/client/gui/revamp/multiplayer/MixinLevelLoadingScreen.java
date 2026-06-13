package com.spirit.mixin.client.gui.revamp.multiplayer;

import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.util.file.audio.AudioManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.LevelLoadingScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelLoadingScreen.class)
public class MixinLevelLoadingScreen {
    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo info) {
        AudioManager.stopAllAudio();
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
        KoilScreenBackgrounds.render(context, client, width, height);
        context.fill(0, 0, width, height, KoilScreenBackgrounds.overlayColor(client));
    }
}
