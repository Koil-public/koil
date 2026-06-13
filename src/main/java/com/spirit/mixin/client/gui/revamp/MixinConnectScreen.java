package com.spirit.mixin.client.gui.revamp;

import com.google.gson.JsonElement;
import com.spirit.koil.api.design.DesignLoader;
import com.spirit.koil.api.util.file.audio.AudioManager;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConnectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.spirit.Main.*;

@Mixin(ConnectScreen.class)
public class MixinConnectScreen {
    private int dotCount;
    private long lastUpdateTime;

    @Inject(method = "init*", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            this.lastUpdateTime = System.currentTimeMillis();
            this.dotCount = 0;
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo info) {
        AudioManager.stopAllAudio();
        MinecraftClient client = MinecraftClient.getInstance();
        JsonElement jsonBackgroundElement = JSONFileEditor.getValueFromJson("./koil/sys/config.json", "jsonBackground");
        context.drawTexture(DesignLoader.getLoadingTexture(), 0, 0, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), 0.0F, 0.0F, 319, 192, 319, 192);


        client.getTextureManager().bindTexture(LOGO_TEXTURE);
        context.drawTexture(LOGO_TEXTURE, client.getWindow().getScaledWidth() - 60, client.getWindow().getScaledHeight() - 60, 0, 0, 45, 45, 45, 45);
        client.getTextureManager().bindTexture(MOJANG_LOGO);
        context.drawTexture(MOJANG_LOGO, client.getWindow().getScaledWidth() - 130, client.getWindow().getScaledHeight() - 60, 0, 0, 45, 45, 45, 45);

        // Update dot animation
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastUpdateTime >= 500) {
            lastUpdateTime = currentTime;
            dotCount = (dotCount + 1) % 4;
        }

        int loadingImageWidth = 184 + (dotCount == 0 ? 0 : (dotCount == 1 ? 15 : (dotCount == 2 ? 30 : 44)));
        int loadingImageHeight = 35;

        client.getTextureManager().bindTexture(LOADING_TEXT_TEXTURE);
        context.drawTexture(LOADING_TEXT_TEXTURE, 10, client.getWindow().getScaledHeight() - 30, 0, 0, loadingImageWidth / 2, loadingImageHeight / 2, 228 / 2, loadingImageHeight / 2);
    }
}
