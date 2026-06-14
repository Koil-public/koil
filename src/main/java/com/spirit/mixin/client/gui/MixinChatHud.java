package com.spirit.mixin.client.gui;

import com.spirit.koil.api.automation.AutomationChatTrigger;
import com.spirit.koil.api.automation.cli.AutomationChatHudRenderer;
import com.spirit.koil.api.stats.global.client.MarketHudRenderer;
import com.spirit.koil.chat.client.vanilla.RichChatVanillaLineRenderer;
import com.spirit.koil.chat.client.vanilla.RichChatVanillaMessageIndex;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ChatHud.class)
public abstract class MixinChatHud {
    @Shadow @Final private MinecraftClient client;
    @Shadow @Final private List<ChatHudLine> messages;
    @Shadow @Final private List<ChatHudLine.Visible> visibleMessages;
    @Shadow private int scrolledLines;

    @Shadow private boolean isChatHidden() {
        return false;
    }

    @Shadow private boolean isChatFocused() {
        return false;
    }

    @Shadow public abstract int getVisibleLineCount();

    @Shadow public abstract double getChatScale();

    @Shadow public abstract int getWidth();

    @Shadow private int getLineHeight() {
        return 0;
    }

    private boolean koil$shiftedForAutomationHud;
    private int koil$automationHudReservedHeight;

    @Inject(method = "render", at = @At("HEAD"))
    private void koil$beginAutomationHudRender(DrawContext context, int currentTick, int mouseX, int mouseY, CallbackInfo ci) {
        koil$shiftedForAutomationHud = false;
        koil$automationHudReservedHeight = 0;
        int reservedHeight = AutomationChatHudRenderer.reservedHeight(client) + MarketHudRenderer.reservedHeight(client);
        if (reservedHeight > 0) {
            koil$automationHudReservedHeight = reservedHeight;
            koil$shiftedForAutomationHud = true;
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, -koil$automationHudReservedHeight, 0.0F);
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void koil$finishAutomationHudRender(DrawContext context, int currentTick, int mouseX, int mouseY, CallbackInfo ci) {
        koil$renderRichChatVanillaEnhancements(context, currentTick);
        if (koil$shiftedForAutomationHud) {
            context.getMatrices().pop();
            koil$shiftedForAutomationHud = false;
        }
    }

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void koil$automationUsernameTrigger(Text message, CallbackInfo ci) {
        AutomationChatTrigger.maybeOpenPrompt(message);
        RichChatVanillaMessageIndex.attachToVanillaMessage(message);
    }

    private void koil$renderRichChatVanillaEnhancements(DrawContext context, int currentTick) {
        if (client == null || client.options == null || isChatHidden() || visibleMessages.isEmpty()) {
            return;
        }
        double chatScale = getChatScale();
        if (chatScale <= 0.0D) {
            return;
        }
        int visibleLineCount = getVisibleLineCount();
        if (visibleLineCount <= 0) {
            return;
        }
        int chatWidth = (int) Math.ceil((double) getWidth() / chatScale);
        int baseY = (int) Math.floor((double) (context.getScaledWindowHeight() - 40) / chatScale);
        double chatOpacity = ((Double) client.options.getChatOpacity().getValue()) * 0.8999999761581421D + 0.10000000149011612D;
        context.getMatrices().push();
        context.getMatrices().scale((float) chatScale, (float) chatScale, 1.0F);
        context.getMatrices().translate(4.0F, 0.0F, 75.0F);
        RichChatVanillaLineRenderer.renderBadges(context, client, messages, visibleMessages, currentTick, scrolledLines, visibleLineCount, chatWidth, getLineHeight(), baseY, chatOpacity, isChatFocused());
        context.getMatrices().pop();
    }
}
