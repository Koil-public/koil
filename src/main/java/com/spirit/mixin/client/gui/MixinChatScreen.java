package com.spirit.mixin.client.gui;

import com.spirit.koil.api.automation.cli.AutomationChatHudRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public abstract class MixinChatScreen {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void koil$automationHudMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (AutomationChatHudRenderer.mouseClicked(MinecraftClient.getInstance(), mouseX, mouseY, button)) {
            cir.setReturnValue(true);
        }
    }
}
