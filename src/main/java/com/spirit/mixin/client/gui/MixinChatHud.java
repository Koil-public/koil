package com.spirit.mixin.client.gui;

import com.spirit.koil.api.automation.AutomationChatTrigger;
import com.spirit.koil.api.chat.ChatHudPanelStack;
import com.spirit.koil.api.chat.ChatHudRefreshBridge;
import com.spirit.koil.api.chat.LocalOverflowChatBridge;
import com.spirit.koil.api.chat.LocalMultilineChatBridge;
import com.spirit.koil.api.chat.MultilineChatInputLayout;
import com.spirit.koil.api.chat.RichChatCodeBlockBridge;
import com.spirit.koil.api.chat.RichChatCommandFeedbackFormatter;
import com.spirit.koil.api.chat.RichChatCommandOutputBridge;
import com.spirit.koil.api.chat.RichChatPrivateChunkBridge;
import com.spirit.koil.api.chat.RichChatPrivateMessageBridge;
import com.spirit.koil.api.chat.RichChatBodyWrapFormatter;
import com.spirit.koil.api.chat.RichChatRenderContext;
import com.spirit.koil.api.chat.RichChatRowClassifier;
import com.spirit.koil.api.chat.RichChatRowType;
import com.spirit.koil.api.chat.RichChatTimestampBridge;
import com.spirit.koil.api.chat.latex.RichChatLatexFormatter;
import com.spirit.koil.api.chat.latex.RichChatLatexTextureCache;
import com.spirit.koil.api.chat.upload.LocalRichAttachmentBridge;
import com.spirit.koil.api.chat.upload.RichChatAttachmentRenderer;
import com.spirit.koil.api.chat.upload.RichChatUploadDraft;
import com.spirit.koil.api.chat.upload.RichChatWebAttachmentBridge;
import com.spirit.koil.api.chat.sync.RichChatSyncedMessageBridge;
import com.spirit.koil.api.chat.RichChatSettings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import net.minecraft.client.util.ChatMessages;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;
import java.util.Objects;

@Mixin(ChatHud.class)
public abstract class MixinChatHud implements ChatHudRefreshBridge {
    @Unique private static final MessageIndicator KOIL_PRIVATE_INDICATOR_BRIGHT = new MessageIndicator(0x8A8A8A, null, Text.literal("Private message"), "Private");
    @Unique private static final MessageIndicator KOIL_PRIVATE_INDICATOR_DIM = new MessageIndicator(0x6A6A6A, null, Text.literal("Private message"), "Private");
    @Unique private static final MessageIndicator KOIL_PLAYER_ACTIVITY_INDICATOR_BRIGHT = new MessageIndicator(0x9A9142, null, Text.literal("Player activity"), "Activity");
    @Unique private static final MessageIndicator KOIL_PLAYER_ACTIVITY_INDICATOR_DIM = new MessageIndicator(0x7A7334, null, Text.literal("Player activity"), "Activity");
    @Unique private static final MessageIndicator KOIL_ADVANCEMENT_TASK_INDICATOR_BRIGHT = new MessageIndicator(0x4F9C52, null, Text.literal("Advancement"), "Advancement");
    @Unique private static final MessageIndicator KOIL_ADVANCEMENT_TASK_INDICATOR_DIM = new MessageIndicator(0x3C7840, null, Text.literal("Advancement"), "Advancement");
    @Unique private static final MessageIndicator KOIL_ADVANCEMENT_GOAL_INDICATOR_BRIGHT = new MessageIndicator(0x4F78B8, null, Text.literal("Goal reached"), "Goal");
    @Unique private static final MessageIndicator KOIL_ADVANCEMENT_GOAL_INDICATOR_DIM = new MessageIndicator(0x395A8A, null, Text.literal("Goal reached"), "Goal");
    @Unique private static final MessageIndicator KOIL_ADVANCEMENT_CHALLENGE_INDICATOR_BRIGHT = new MessageIndicator(0xA35AC6, null, Text.literal("Challenge completed"), "Challenge");
    @Unique private static final MessageIndicator KOIL_ADVANCEMENT_CHALLENGE_INDICATOR_DIM = new MessageIndicator(0x7A4396, null, Text.literal("Challenge completed"), "Challenge");
    @Unique private static final MessageIndicator KOIL_COMMAND_INDICATOR_BRIGHT = new MessageIndicator(0xD8872F, null, Text.literal("Command output"), "Command");
    @Unique private static final MessageIndicator KOIL_COMMAND_INDICATOR_DIM = new MessageIndicator(0xA86724, null, Text.literal("Command output"), "Command");
    @Unique private static final MessageIndicator KOIL_COMMAND_BLOCK_IMPULSE_INDICATOR_BRIGHT = new MessageIndicator(0xD8872F, null, Text.literal("Impulse command block output"), "Impulse CB");
    @Unique private static final MessageIndicator KOIL_COMMAND_BLOCK_IMPULSE_INDICATOR_DIM = new MessageIndicator(0xA86724, null, Text.literal("Impulse command block output"), "Impulse CB");
    @Unique private static final MessageIndicator KOIL_COMMAND_BLOCK_CHAIN_INDICATOR_BRIGHT = new MessageIndicator(0x4F9C52, null, Text.literal("Chain command block output"), "Chain CB");
    @Unique private static final MessageIndicator KOIL_COMMAND_BLOCK_CHAIN_INDICATOR_DIM = new MessageIndicator(0x3C7840, null, Text.literal("Chain command block output"), "Chain CB");
    @Unique private static final MessageIndicator KOIL_COMMAND_BLOCK_REPEATING_INDICATOR_BRIGHT = new MessageIndicator(0x8753C7, null, Text.literal("Repeating command block output"), "Repeating CB");
    @Unique private static final MessageIndicator KOIL_COMMAND_BLOCK_REPEATING_INDICATOR_DIM = new MessageIndicator(0x68409A, null, Text.literal("Repeating command block output"), "Repeating CB");
    @Unique private static final MessageIndicator KOIL_PUBLIC_FILTER_INDICATOR = new MessageIndicator(0x515A67, null, Text.literal("Public chat"), "Public");
    @Unique private static final MessageIndicator KOIL_ATTENTION_INDICATOR = new MessageIndicator(0xD84A4A, null, Text.literal("Mention message"), "Mention");

    @Shadow @Final private MinecraftClient client;
    @Shadow @Final private List<ChatHudLine> messages;
    @Shadow @Final private List<ChatHudLine.Visible> visibleMessages;
    @Shadow private int scrolledLines;
    @Shadow public abstract int getVisibleLineCount();
    @Shadow public abstract int getWidth();
    @Shadow public abstract double getChatScale();

    private boolean koil$shiftedForAutomationHud;
    private int koil$automationHudReservedHeight;
    private boolean koil$chatScrollbarDragging;
    private int koil$chatScrollbarDragOffset;

    @ModifyVariable(method = {"getTextStyleAt", "getIndicatorAt"}, at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private double koil$alignVanillaHoverYWithShiftedChat(double mouseY) {
        return mouseY + koil$automationHudReservedHeight;
    }

    @Invoker("addMessage")
    protected abstract void koil$invokeAddMessage(Text message, MessageSignatureData signature, int ticks, MessageIndicator indicator, boolean refresh);

    @Invoker("logChatMessage")
    protected abstract void koil$invokeLogChatMessage(Text message, MessageIndicator indicator);

    @Redirect(
            method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;ILnet/minecraft/client/gui/hud/MessageIndicator;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/util/ChatMessages;breakRenderedChatMessageLines(Lnet/minecraft/text/StringVisitable;ILnet/minecraft/client/font/TextRenderer;)Ljava/util/List;"
            )
    )
    private List<OrderedText> koil$wrapWithoutHiddenPrivateMarkerWidth(StringVisitable message, int width, TextRenderer renderer) {
        String visible = message == null ? "" : message.getString();
        int adjustedWidth = width + RichChatPrivateMessageBridge.nativeWrapWidthAdjustment(renderer, visible);
        return ChatMessages.breakRenderedChatMessageLines(message, adjustedWidth, renderer);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void koil$beginAutomationHudRender(DrawContext context, int currentTick, int mouseX, int mouseY, CallbackInfo ci) {
        RichChatAttachmentRenderer.beginFrame(mouseX, mouseY);
        koil$shiftedForAutomationHud = false;
        koil$automationHudReservedHeight = 0;
        int reservedHeight = ChatHudPanelStack.reservedHeight(client)
                + MultilineChatInputLayout.reservedHeight(client)
                + RichChatUploadDraft.reservedHeight();
        ChatHudPanelStack.beginChatFrame(reservedHeight);
        if (reservedHeight > 0) {
            koil$automationHudReservedHeight = reservedHeight;
            koil$shiftedForAutomationHud = true;
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, -koil$automationHudReservedHeight, 0.0F);
        }
        RichChatRenderContext.beginChatHudFrame(-koil$automationHudReservedHeight);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void koil$finishAutomationHudRender(DrawContext context, int currentTick, int mouseX, int mouseY, CallbackInfo ci) {
        if (client != null && client.currentScreen instanceof ChatScreen) {
            koil$renderChatScrollbar(context);
        }
        if (koil$shiftedForAutomationHud) {
            context.getMatrices().pop();
            koil$shiftedForAutomationHud = false;
        }
        RichChatRenderContext.endChatHudFrame();
        // Private-only mode filters native rows during refresh, retaining
        // Minecraft's normal per-message fade and background behavior.
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;III)I"
            )
    )
    private int koil$renderLatexTextures(DrawContext context, TextRenderer renderer, OrderedText orderedText, int x, int y, int color) {
        ChatHudPanelStack.observeChatLine(context, y);
        if (!RichChatSettings.enabled() || (!RichChatSettings.mediaEnabled() && !RichChatSettings.latexEnabled() && !RichChatSettings.effectsEnabled())) {
            return context.drawTextWithShadow(renderer, orderedText, x, y, color);
        }
        return RichChatAttachmentRenderer.renderOrDrawText(context, renderer, orderedText, x, y, color);
    }

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void koil$automationUsernameTrigger(Text message, CallbackInfo ci) {
        AutomationChatTrigger.maybeOpenPrompt(message);
        if (RichChatSettings.timestampsEnabled()) {
            RichChatTimestampBridge.remember(message);
        }
    }

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V", at = @At("HEAD"), cancellable = true)
    private void koil$rewriteLocalMultilineFallback(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {
        if (!RichChatSettings.enabled()) {
            return;
        }
        if (LocalOverflowChatBridge.consume(message)) {
            ci.cancel();
            return;
        }
        RichChatPrivateChunkBridge.RewriteResult privateChunks = RichChatPrivateChunkBridge.rewrite(message);
        if (privateChunks.cancel()) {
            ci.cancel();
            return;
        }
        Text rewritten = privateChunks.message();
        rewritten = LocalRichAttachmentBridge.rewrite(rewritten);
        rewritten = LocalMultilineChatBridge.rewrite(rewritten);
        RichChatSyncedMessageBridge.RewriteResult synced = RichChatSyncedMessageBridge.rewrite(rewritten);
        if (synced.cancel()) {
            if (synced.message() != null) {
                Text syncedMessage = RichChatPrivateMessageBridge.observeAndRewrite(synced.message());
                MessageIndicator replacementIndicator = koil$selectIndicator(syncedMessage, indicator);
                koil$invokeLogChatMessage(koil$logFriendlyText(syncedMessage), replacementIndicator);
                koil$invokeAddMessage(syncedMessage, signature, koil$currentTicks(), replacementIndicator, false);
            }
            ci.cancel();
            return;
        }
        rewritten = synced.message();
        if (RichChatSettings.mediaEnabled()) {
            rewritten = RichChatWebAttachmentBridge.rewrite(rewritten);
        }
        if (RichChatSettings.latexEnabled()) {
            rewritten = RichChatLatexFormatter.format(rewritten);
        }
        rewritten = RichChatPrivateMessageBridge.observeAndRewrite(rewritten);
        if (RichChatSettings.effectsEnabled()) {
            rewritten = RichChatCodeBlockBridge.rewrite(rewritten);
        }
        rewritten = RichChatCommandOutputBridge.enhanceSyntaxFailure(rewritten);
        RichChatRowType rowType = RichChatRowClassifier.classify(rewritten, indicator);
        boolean commandFeedback = rowType == RichChatRowType.COMMAND_OUTPUT
                || rowType == RichChatRowType.COMMAND_BLOCK_IMPULSE
                || rowType == RichChatRowType.COMMAND_BLOCK_CHAIN
                || rowType == RichChatRowType.COMMAND_BLOCK_REPEATING;
        if (rewritten != null && rowType.usesBodyIndent() && rowType != RichChatRowType.PRIVATE_MESSAGE && !commandFeedback) {
            rewritten = RichChatBodyWrapFormatter.format(rewritten, rowType);
        }
        if (commandFeedback) {
            rewritten = RichChatCommandFeedbackFormatter.styleBeforeNativeWrap(
                    rewritten,
                    rowType == RichChatRowType.COMMAND_BLOCK_REPEATING
            );
        }
        if (RichChatSettings.timestampsEnabled()) {
            RichChatTimestampBridge.remember(rewritten);
        }
        MessageIndicator replacementIndicator = koil$selectIndicator(rewritten, indicator);
        if (rewritten != message || !Objects.equals(replacementIndicator, indicator)) {
            koil$invokeLogChatMessage(koil$logFriendlyText(rewritten), replacementIndicator);
            koil$invokeAddMessage(rewritten, signature, koil$currentTicks(), replacementIndicator, false);
            ci.cancel();
        }
    }

    @Unique
    private int koil$currentTicks() {
        return client != null && client.inGameHud != null ? client.inGameHud.getTicks() : 0;
    }

    @Unique
    private Text koil$logFriendlyText(Text message) {
        if (message == null) {
            return Text.empty();
        }
        String visible = message.getString();
        if (visible == null || visible.isBlank()) {
            return message;
        }
        String rebuilt = RichChatPrivateMessageBridge.rebuildVisibleText(visible);
        rebuilt = RichChatCodeBlockBridge.logFriendlyText(rebuilt);
        rebuilt = RichChatPrivateMessageBridge.stripVisibleMarkersForLayout(rebuilt);
        return rebuilt.equals(visible) ? message : Text.literal(rebuilt);
    }

    @Unique
    private MessageIndicator koil$selectIndicator(Text message, MessageIndicator original) {
        if (!RichChatSettings.indicatorsEnabled() || !RichChatSettings.chatColorsEnabled()) {
            return original;
        }
        if (message == null) {
            return original;
        }
        RichChatRowType rowType = RichChatRowClassifier.classify(message, original);
        if (rowType == RichChatRowType.PRIVATE_MESSAGE) {
            if (RichChatPrivateMessageBridge.filterEnabled() && RichChatPrivateMessageBridge.isSelectedConversationLine(message.getString())) {
                return null;
            }
            return RichChatPrivateMessageBridge.filterEnabled() ? KOIL_PRIVATE_INDICATOR_DIM : KOIL_PRIVATE_INDICATOR_BRIGHT;
        }
        if (rowType == RichChatRowType.PLAYER_CHAT) {
            return RichChatPrivateMessageBridge.filterEnabled() ? KOIL_PUBLIC_FILTER_INDICATOR : null;
        }
        if (rowType == RichChatRowType.ATTENTION) {
            return KOIL_ATTENTION_INDICATOR;
        }
        if (rowType == RichChatRowType.PLAYER_ACTIVITY) {
            return RichChatPrivateMessageBridge.filterEnabled() ? KOIL_PLAYER_ACTIVITY_INDICATOR_DIM : KOIL_PLAYER_ACTIVITY_INDICATOR_BRIGHT;
        }
        if (rowType == RichChatRowType.ADVANCEMENT_TASK) {
            return RichChatPrivateMessageBridge.filterEnabled() ? KOIL_ADVANCEMENT_TASK_INDICATOR_DIM : KOIL_ADVANCEMENT_TASK_INDICATOR_BRIGHT;
        }
        if (rowType == RichChatRowType.ADVANCEMENT_GOAL) {
            return RichChatPrivateMessageBridge.filterEnabled() ? KOIL_ADVANCEMENT_GOAL_INDICATOR_DIM : KOIL_ADVANCEMENT_GOAL_INDICATOR_BRIGHT;
        }
        if (rowType == RichChatRowType.ADVANCEMENT_CHALLENGE) {
            return RichChatPrivateMessageBridge.filterEnabled() ? KOIL_ADVANCEMENT_CHALLENGE_INDICATOR_DIM : KOIL_ADVANCEMENT_CHALLENGE_INDICATOR_BRIGHT;
        }
        if (rowType == RichChatRowType.COMMAND_OUTPUT) {
            return RichChatPrivateMessageBridge.filterEnabled() ? KOIL_COMMAND_INDICATOR_DIM : KOIL_COMMAND_INDICATOR_BRIGHT;
        }
        if (rowType == RichChatRowType.COMMAND_BLOCK_IMPULSE) {
            return RichChatPrivateMessageBridge.filterEnabled() ? KOIL_COMMAND_BLOCK_IMPULSE_INDICATOR_DIM : KOIL_COMMAND_BLOCK_IMPULSE_INDICATOR_BRIGHT;
        }
        if (rowType == RichChatRowType.COMMAND_BLOCK_CHAIN) {
            return RichChatPrivateMessageBridge.filterEnabled() ? KOIL_COMMAND_BLOCK_CHAIN_INDICATOR_DIM : KOIL_COMMAND_BLOCK_CHAIN_INDICATOR_BRIGHT;
        }
        if (rowType == RichChatRowType.COMMAND_BLOCK_REPEATING) {
            return RichChatPrivateMessageBridge.filterEnabled() ? KOIL_COMMAND_BLOCK_REPEATING_INDICATOR_DIM : KOIL_COMMAND_BLOCK_REPEATING_INDICATOR_BRIGHT;
        }
        return original;
    }

    @Override
    public void koil$refreshPrivateMessageView() {
        visibleMessages.clear();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatHudLine line = messages.get(i);
            if (!RichChatPrivateMessageBridge.shouldIncludeInNativePrivateView(line.content())) {
                continue;
            }
            Text visible = RichChatPrivateMessageBridge.rebuildMessageForRefresh(line.content());
            MessageIndicator refreshedIndicator = koil$selectIndicator(visible, line.indicator());
            koil$invokeAddMessage(visible, line.signature(), line.creationTick(), refreshedIndicator, true);
        }
    }

    @Override
    public boolean koil$chatScrollbarContains(double mouseX, double mouseY) {
        ScrollbarMetrics metrics = koil$chatScrollbarMetrics();
        return metrics != null
                && mouseX >= metrics.x() - 2
                && mouseX <= metrics.x() + metrics.width() + 2
                && mouseY >= metrics.y()
                && mouseY <= metrics.y() + metrics.height();
    }

    @Override
    public void koil$beginChatScrollbarDrag(double mouseY) {
        ScrollbarMetrics metrics = koil$chatScrollbarMetrics();
        if (metrics == null) {
            return;
        }
        koil$chatScrollbarDragging = true;
        if (mouseY >= metrics.thumbY() && mouseY <= metrics.thumbY() + metrics.thumbHeight()) {
            koil$chatScrollbarDragOffset = (int) mouseY - metrics.thumbY();
        } else {
            koil$chatScrollbarDragOffset = metrics.thumbHeight() / 2;
            koil$setChatScrollFromThumbTop((int) mouseY - koil$chatScrollbarDragOffset, metrics);
        }
    }

    @Override
    public void koil$dragChatScrollbar(double mouseY) {
        if (!koil$chatScrollbarDragging) {
            return;
        }
        ScrollbarMetrics metrics = koil$chatScrollbarMetrics();
        if (metrics == null) {
            return;
        }
        koil$setChatScrollFromThumbTop((int) mouseY - koil$chatScrollbarDragOffset, metrics);
    }

    @Override
    public void koil$endChatScrollbarDrag() {
        koil$chatScrollbarDragging = false;
    }

    private void koil$renderChatScrollbar(DrawContext context) {
        ScrollbarMetrics metrics = koil$chatScrollbarMetrics();
        if (context == null || metrics == null) {
            return;
        }
        context.fill(metrics.x(), metrics.y(), metrics.x() + metrics.width(), metrics.y() + metrics.height(), 0x20374455);
        context.fill(metrics.x(), metrics.thumbY(), metrics.x() + metrics.width(), metrics.thumbY() + metrics.thumbHeight(), 0x8890A7C1);
    }

    private ScrollbarMetrics koil$chatScrollbarMetrics() {
        int visibleLineCount = Math.max(1, getVisibleLineCount());
        int totalLines = visibleMessages == null ? 0 : visibleMessages.size();
        if (totalLines <= visibleLineCount) {
            return null;
        }
        int viewportTop = RichChatRenderContext.currentChatViewportTop();
        int viewportBottom = RichChatRenderContext.currentChatViewportBottom();
        int viewportHeight = Math.max(1, viewportBottom - viewportTop);
        int trackX = 4 + Math.round((float) (getWidth() * getChatScale())) + 6;
        int trackY = viewportTop;
        int trackWidth = 3;
        int thumbHeight = Math.max(18, viewportHeight * visibleLineCount / Math.max(visibleLineCount, totalLines));
        int maxScroll = Math.max(1, totalLines - visibleLineCount);
        float ratio = Math.max(0.0F, Math.min(1.0F, scrolledLines / (float) maxScroll));
        int thumbY = trackY + Math.round((viewportHeight - thumbHeight) * (1.0F - ratio));
        return new ScrollbarMetrics(trackX, trackY, trackWidth, viewportHeight, thumbY, thumbHeight, maxScroll);
    }

    private void koil$setChatScrollFromThumbTop(int thumbTop, ScrollbarMetrics metrics) {
        if (metrics == null) {
            return;
        }
        int minTop = metrics.y();
        int maxTop = metrics.y() + metrics.height() - metrics.thumbHeight();
        int clampedTop = Math.max(minTop, Math.min(maxTop, thumbTop));
        int track = Math.max(1, metrics.height() - metrics.thumbHeight());
        float ratio = (clampedTop - minTop) / (float) track;
        scrolledLines = Math.max(0, Math.min(metrics.maxScroll(), Math.round((1.0F - ratio) * metrics.maxScroll())));
    }

    private record ScrollbarMetrics(int x, int y, int width, int height, int thumbY, int thumbHeight, int maxScroll) {
    }
}
