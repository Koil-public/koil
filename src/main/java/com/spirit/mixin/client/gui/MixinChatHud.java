package com.spirit.mixin.client.gui;

import com.spirit.koil.api.automation.AutomationChatTrigger;
import com.spirit.koil.api.automation.cli.AutomationChatHudRenderer;
import com.spirit.koil.api.stats.global.client.MarketHudRenderer;
import com.spirit.koil.chat.internal.LocalMultilineChatBridge;
import com.spirit.koil.chat.internal.MultilineChatInputLayout;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatHud.class)
public abstract class MixinChatHud {
    @Shadow @Final private MinecraftClient client;

    private boolean koil$shiftedForAutomationHud;
    private int koil$automationHudReservedHeight;

    @Inject(method = "render", at = @At("HEAD"))
    private void koil$beginAutomationHudRender(DrawContext context, int currentTick, int mouseX, int mouseY, CallbackInfo ci) {
        koil$shiftedForAutomationHud = false;
        koil$automationHudReservedHeight = 0;
        int reservedHeight = AutomationChatHudRenderer.reservedHeight(client)
                + MarketHudRenderer.reservedHeight(client)
                + MultilineChatInputLayout.reservedHeight(client);
        if (reservedHeight > 0) {
            koil$automationHudReservedHeight = reservedHeight;
            koil$shiftedForAutomationHud = true;
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, -koil$automationHudReservedHeight, 0.0F);
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void koil$finishAutomationHudRender(DrawContext context, int currentTick, int mouseX, int mouseY, CallbackInfo ci) {
        if (koil$shiftedForAutomationHud) {
            context.getMatrices().pop();
            koil$shiftedForAutomationHud = false;
        }
    }

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void koil$automationUsernameTrigger(Text message, CallbackInfo ci) {
        AutomationChatTrigger.maybeOpenPrompt(message);
    }

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V", at = @At("HEAD"), cancellable = true)
    private void koil$rewriteLocalMultilineFallback(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {
        Text rewritten = LocalMultilineChatBridge.rewrite(message);
        if (rewritten != message) {
            ((ChatHud)(Object)this).addMessage(rewritten, signature, indicator);
            ci.cancel();
        }
    }
}
