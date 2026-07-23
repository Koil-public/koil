package com.spirit.mixin.client.gui.revamp;

import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.CreditsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(CreditsScreen.class)
public class MixinCreditsScreen {
    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void koil$renderCreditsBackground(DrawContext context, CallbackInfo ci) {
        if (!JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        KoilVanillaScreenChrome.renderCreditsShell(context, client, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());
        ci.cancel();
    }
}
