package com.spirit.mixin.client.gui;

import com.spirit.koil.api.chat.ChatSuggestionAnchor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatInputSuggestor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatInputSuggestor.class)
public abstract class MixinChatInputSuggestor {
    @Inject(method = "renderMessages", at = @At("HEAD"), cancellable = true)
    private void koil$hideVanillaCommandErrorOverlay(DrawContext context, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.currentScreen instanceof ChatSuggestionAnchor anchor && anchor.koil$useCustomChatSuggestionAnchor()) {
            ci.cancel();
        }
    }
}
