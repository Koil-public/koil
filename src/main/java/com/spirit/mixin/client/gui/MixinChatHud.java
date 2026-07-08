package com.spirit.mixin.client.gui;

import com.spirit.koil.api.automation.AutomationChatTrigger;
import com.spirit.koil.api.automation.cli.AutomationChatHudRenderer;
import com.spirit.koil.api.stats.global.client.MarketHudRenderer;
import com.spirit.koil.chat.internal.ChatHudRefreshBridge;
import com.spirit.koil.chat.internal.LocalOverflowChatBridge;
import com.spirit.koil.chat.internal.LocalMultilineChatBridge;
import com.spirit.koil.chat.internal.MultilineChatInputLayout;
import com.spirit.koil.chat.internal.RichChatCodeBlockBridge;
import com.spirit.koil.chat.internal.RichChatPrivateChunkBridge;
import com.spirit.koil.chat.internal.RichChatPrivateMessageBridge;
import com.spirit.koil.chat.internal.RichChatBodyWrapFormatter;
import com.spirit.koil.chat.internal.RichChatTimestampBridge;
import com.spirit.koil.chat.internal.latex.RichChatLatexFormatter;
import com.spirit.koil.chat.internal.latex.RichChatLatexTextureCache;
import com.spirit.koil.chat.internal.upload.LocalRichAttachmentBridge;
import com.spirit.koil.chat.internal.upload.RichChatAttachmentRenderer;
import com.spirit.koil.chat.internal.upload.RichChatUploadDraft;
import com.spirit.koil.chat.internal.upload.RichChatWebImageBridge;
import com.spirit.koil.chat.internal.sync.RichChatSyncedMessageBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

@Mixin(ChatHud.class)
public abstract class MixinChatHud implements ChatHudRefreshBridge {
    @Unique private static final Pattern KOIL_PLAYER_CHAT_PREFIX = Pattern.compile("^<[^>]+>\\s+.*$");
    @Unique private static final Pattern KOIL_JOIN_LEAVE_MESSAGE = Pattern.compile("^.+\\s+(joined|left)\\s+the\\s+game\\.?$", Pattern.CASE_INSENSITIVE);
    @Unique private static final Pattern KOIL_COMMANDISH_MESSAGE = Pattern.compile("^(unknown command|incorrect argument|usage:|set own game mode to|teleported|gave |cleared |summoned |killed |located |time set|weather set|difficulty set|saved the game|reloaded|data of|no entity was found|found no elements|seed:).*$", Pattern.CASE_INSENSITIVE);
    @Unique private static final MessageIndicator KOIL_PRIVATE_INDICATOR_BRIGHT = new MessageIndicator(0x8A8A8A, null, Text.literal("Private message"), "Private");
    @Unique private static final MessageIndicator KOIL_PRIVATE_INDICATOR_DIM = new MessageIndicator(0x6A6A6A, null, Text.literal("Private message"), "Private");
    @Unique private static final MessageIndicator KOIL_PLAYER_ACTIVITY_INDICATOR_BRIGHT = new MessageIndicator(0x9A9142, null, Text.literal("Player activity"), "Activity");
    @Unique private static final MessageIndicator KOIL_PLAYER_ACTIVITY_INDICATOR_DIM = new MessageIndicator(0x7A7334, null, Text.literal("Player activity"), "Activity");
    @Unique private static final MessageIndicator KOIL_COMMAND_INDICATOR_BRIGHT = new MessageIndicator(0xD8872F, null, Text.literal("Command output"), "Command");
    @Unique private static final MessageIndicator KOIL_COMMAND_INDICATOR_DIM = new MessageIndicator(0xA86724, null, Text.literal("Command output"), "Command");
    @Unique private static final MessageIndicator KOIL_PUBLIC_FILTER_INDICATOR = new MessageIndicator(0x515A67, null, Text.literal("Public chat"), "Public");

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

    @Invoker("addMessage")
    protected abstract void koil$invokeAddMessage(Text message, MessageSignatureData signature, int ticks, MessageIndicator indicator, boolean refresh);

    @Invoker("logChatMessage")
    protected abstract void koil$invokeLogChatMessage(Text message, MessageIndicator indicator);

    @Inject(method = "render", at = @At("HEAD"))
    private void koil$beginAutomationHudRender(DrawContext context, int currentTick, int mouseX, int mouseY, CallbackInfo ci) {
        RichChatAttachmentRenderer.beginFrame(mouseX, mouseY);
        koil$shiftedForAutomationHud = false;
        koil$automationHudReservedHeight = 0;
        int reservedHeight = AutomationChatHudRenderer.reservedHeight(client)
                + MarketHudRenderer.reservedHeight(client)
                + MultilineChatInputLayout.reservedHeight(client)
                + RichChatUploadDraft.reservedHeight();
        if (reservedHeight > 0) {
            koil$automationHudReservedHeight = reservedHeight;
            koil$shiftedForAutomationHud = true;
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, -koil$automationHudReservedHeight, 0.0F);
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void koil$finishAutomationHudRender(DrawContext context, int currentTick, int mouseX, int mouseY, CallbackInfo ci) {
        if (client != null && client.currentScreen instanceof ChatScreen) {
            koil$renderChatScrollbar(context);
        }
        if (koil$shiftedForAutomationHud) {
            context.getMatrices().pop();
            koil$shiftedForAutomationHud = false;
        }
        RichChatPrivateMessageBridge.renderPrivateOnlyOverlay(context, client == null ? null : client.textRenderer);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;III)I"
            )
    )
    private int koil$renderLatexTextures(DrawContext context, TextRenderer renderer, OrderedText orderedText, int x, int y, int color) {
        return RichChatAttachmentRenderer.renderOrDrawText(context, renderer, orderedText, x, y, color);
    }

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void koil$automationUsernameTrigger(Text message, CallbackInfo ci) {
        AutomationChatTrigger.maybeOpenPrompt(message);
        RichChatTimestampBridge.remember(message);
    }

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V", at = @At("HEAD"), cancellable = true)
    private void koil$rewriteLocalMultilineFallback(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {
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
                MessageIndicator replacementIndicator = koil$selectIndicator(synced.message(), indicator);
                koil$invokeLogChatMessage(koil$logFriendlyText(synced.message()), replacementIndicator);
                koil$invokeAddMessage(synced.message(), signature, koil$currentTicks(), replacementIndicator, false);
            }
            ci.cancel();
            return;
        }
        rewritten = synced.message();
        rewritten = RichChatWebImageBridge.rewrite(rewritten);
        rewritten = RichChatLatexFormatter.format(rewritten);
        rewritten = RichChatPrivateMessageBridge.observeAndRewrite(rewritten);
        rewritten = RichChatCodeBlockBridge.rewrite(rewritten);
        if (rewritten == null || !RichChatPrivateMessageBridge.isPrivateMessageLine(rewritten.getString())) {
            rewritten = RichChatBodyWrapFormatter.format(rewritten);
        }
        RichChatTimestampBridge.remember(rewritten);
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
        if (message == null) {
            return original;
        }
        String visible = RichChatPrivateMessageBridge.stripVisibleMarkersForLayout(RichChatPrivateMessageBridge.rebuildVisibleText(message.getString()));
        String trimmed = visible == null ? "" : visible.trim();
        if (trimmed.isEmpty()) {
            return original;
        }
        if (RichChatPrivateMessageBridge.isPrivateMessageLine(message.getString()) || RichChatPrivateMessageBridge.isPrivateMessageLine(trimmed)) {
            if (RichChatPrivateMessageBridge.filterEnabled() && RichChatPrivateMessageBridge.isSelectedConversationLine(message.getString())) {
                return null;
            }
            return RichChatPrivateMessageBridge.filterEnabled() ? KOIL_PRIVATE_INDICATOR_DIM : KOIL_PRIVATE_INDICATOR_BRIGHT;
        }
        if (KOIL_PLAYER_CHAT_PREFIX.matcher(trimmed).matches()) {
            return RichChatPrivateMessageBridge.filterEnabled() ? KOIL_PUBLIC_FILTER_INDICATOR : null;
        }
        if (KOIL_JOIN_LEAVE_MESSAGE.matcher(trimmed).matches()) {
            return RichChatPrivateMessageBridge.filterEnabled() ? KOIL_PLAYER_ACTIVITY_INDICATOR_DIM : KOIL_PLAYER_ACTIVITY_INDICATOR_BRIGHT;
        }
        if (koil$looksLikeCommandOrSystem(trimmed, original)) {
            return RichChatPrivateMessageBridge.filterEnabled() ? KOIL_COMMAND_INDICATOR_DIM : KOIL_COMMAND_INDICATOR_BRIGHT;
        }
        return original;
    }

    @Unique
    private boolean koil$looksLikeCommandOrSystem(String trimmed, MessageIndicator original) {
        if (trimmed == null || trimmed.isBlank()) {
            return false;
        }
        if (KOIL_COMMANDISH_MESSAGE.matcher(trimmed).matches()) {
            return true;
        }
        if (trimmed.startsWith("[System]") || trimmed.startsWith("System:")) {
            return true;
        }
        if (original == null) {
            return false;
        }
        String loggedName = original.loggedName();
        if (loggedName != null) {
            String normalized = loggedName.toLowerCase(Locale.ROOT);
            if (normalized.contains("system") || normalized.contains("server")) {
                return true;
            }
        }
        String hover = original.text() == null ? "" : original.text().getString().toLowerCase(Locale.ROOT);
        return hover.contains("system") || hover.contains("server") || hover.contains("message");
    }

    @Override
    public void koil$refreshPrivateMessageView() {
        visibleMessages.clear();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatHudLine line = messages.get(i);
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
        int viewportTop = RichChatLatexTextureCache.currentChatViewportTop() - koil$automationHudReservedHeight;
        int viewportBottom = RichChatLatexTextureCache.currentChatViewportBottom() - koil$automationHudReservedHeight;
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
