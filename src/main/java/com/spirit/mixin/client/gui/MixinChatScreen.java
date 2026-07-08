package com.spirit.mixin.client.gui;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.spirit.koil.api.automation.cli.AutomationChatHudRenderer;
import com.spirit.koil.api.chat.RichChatMessageData;
import com.spirit.koil.api.chat.RichChatScope;
import com.spirit.koil.api.chat.RichMessageBuilder;
import com.spirit.koil.chat.internal.ChatSuggestionAnchor;
import com.spirit.koil.chat.internal.LocalOverflowChatBridge;
import com.spirit.koil.chat.internal.LocalMultilineChatBridge;
import com.spirit.koil.chat.internal.MultilineChatInputLayout;
import com.spirit.koil.chat.internal.RichChatPrivateChunkBridge;
import com.spirit.koil.chat.internal.RichChatPrivateMessageBridge;
import com.spirit.koil.chat.internal.RichChatPreviewFormatter;
import com.spirit.koil.chat.internal.RichChatMessageStore;
import com.spirit.koil.chat.internal.latex.RichChatLatexDetector;
import com.spirit.koil.chat.internal.sync.RichChatSyncClientBridge;
import com.spirit.koil.chat.internal.sync.RichChatSyncedMessageBridge;
import com.spirit.koil.chat.internal.upload.LocalRichAttachmentBridge;
import com.spirit.koil.chat.internal.upload.RichChatAttachmentRenderer;
import com.spirit.koil.chat.internal.upload.RichChatRemoteImageCache;
import com.spirit.koil.chat.internal.upload.RichChatUploadDraft;
import com.spirit.koil.api.chat.RichChatAttachment;
import com.spirit.client.gui.PopupMenu;
import com.spirit.client.gui.UiSoundHelper;
import com.spirit.mixin.client.gui.accessor.TextFieldWidgetAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.command.CommandSource;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.StringHelper;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(ChatScreen.class)
public abstract class MixinChatScreen extends Screen implements ChatSuggestionAnchor {
    @Unique private static final int KOIL_MAX_CHAT_DRAFT_CHARS = 32767;
    @Unique private static final int KOIL_MAX_VANILLA_CHAT_CHARS = 256;
    @Unique private static final long KOIL_MULTI_CLICK_MS = 350L;
    @Unique private static final double KOIL_MULTI_CLICK_RANGE = 6.0D;
    @Unique private static final Pattern KOIL_CLIPBOARD_IMAGE_HTML = Pattern.compile("<img\\s+[^>]*src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    @Unique private static final Pattern KOIL_CLIPBOARD_URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    @Unique private static final Pattern KOIL_PRIVATE_COMMAND = Pattern.compile("^/(?:msg|tell|w)\\s+(\\S+)(?:\\s+(.*))?$", Pattern.CASE_INSENSITIVE);
    @Unique private static final Pattern KOIL_EXECUTE_PRIVATE_COMMAND = Pattern.compile("^/execute\\s+as\\s+(\\S+)\\s+run\\s+(msg|tell|w)\\s+(\\S+)(?:\\s+(.*))?$", Pattern.CASE_INSENSITIVE);
    @Shadow protected TextFieldWidget chatField;
    @Shadow public abstract String normalize(String chatText);

    @Unique private int koil$draftScrollLine;
    @Unique private boolean koil$multilineDragSelecting;
    @Unique private int koil$multilineSelectionAnchor = -1;
    @Unique private boolean koil$chatScrollbarDragging;
    @Unique private long koil$lastDraftClickTime;
    @Unique private double koil$lastDraftClickX;
    @Unique private double koil$lastDraftClickY;
    @Unique private int koil$draftClickCount;
    @Unique private final PopupMenu koil$pmMenu = new PopupMenu();
    @Unique private final PopupMenu koil$pmTargetMenu = new PopupMenu();

    protected MixinChatScreen(Text title) {
        super(title);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void koil$updateMultilineLayoutOnTick(CallbackInfo ci) {
        koil$updateMultilineLayoutReservation();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void koil$expandChatDraftLimit(CallbackInfo ci) {
        if (chatField != null) {
            chatField.setMaxLength(KOIL_MAX_CHAT_DRAFT_CHARS);
        }
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void koil$clearMultilineLayoutOnRemoved(CallbackInfo ci) {
        MultilineChatInputLayout.clear();
        koil$multilineDragSelecting = false;
        koil$multilineSelectionAnchor = -1;
        koil$chatScrollbarDragging = false;
        koil$pmMenu.close();
        koil$pmTargetMenu.close();
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void koil$handleMultilineKeys(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (RichChatAttachmentRenderer.keyPressed(keyCode)) {
            cir.setReturnValue(true);
            return;
        }
        if (chatField == null) {
            return;
        }

        if (Screen.isSelectAll(keyCode)) {
            chatField.setSelectionStart(chatField.getText().length());
            chatField.setSelectionEnd(0);
            koil$multilineSelectionAnchor = 0;
            cir.setReturnValue(true);
            return;
        }

        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && Screen.hasShiftDown()) {
            koil$insertDraftText("\n");
            cir.setReturnValue(true);
            return;
        }

        if (Screen.isPaste(keyCode) || koil$isClipboardImageShortcut(keyCode, modifiers)) {
            if (koil$stageClipboardImage()) {
                cir.setReturnValue(true);
                return;
            }
        }

        if (Screen.isPaste(keyCode)) {
            String clipboard = MinecraftClient.getInstance().keyboard.getClipboard();
            Path pastedPath = koil$pathFromClipboard(clipboard);
            if (pastedPath != null) {
                RichChatUploadDraft.stage(pastedPath);
                cir.setReturnValue(true);
                return;
            }
            if (clipboard.indexOf('\n') >= 0 || clipboard.indexOf('\r') >= 0) {
                koil$insertDraftText(clipboard.replace("\r\n", "\n").replace('\r', '\n'));
                cir.setReturnValue(true);
                return;
            }
        }

        if (koil$isMultilineDraft()) {
            if (keyCode == GLFW.GLFW_KEY_UP && koil$moveCursorVertically(-1)) {
                cir.setReturnValue(true);
            } else if (keyCode == GLFW.GLFW_KEY_DOWN && koil$moveCursorVertically(1)) {
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "normalize", at = @At("HEAD"), cancellable = true)
    private void koil$preserveMultilineNormalize(String chatText, CallbackInfoReturnable<String> cir) {
        if (chatText.indexOf('\n') < 0 && chatText.indexOf('\r') < 0) {
            return;
        }

        String normalized = koil$networkSafeMultiline(chatText);
        cir.setReturnValue(koil$truncateDraft(normalized));
    }

    @Inject(method = "sendMessage", at = @At("HEAD"), cancellable = true)
    private void koil$sendNetworkSafeMultiline(String chatText, boolean addToHistory, CallbackInfoReturnable<Boolean> cir) {
        String trimmedChatText = chatText == null ? "" : chatText.trim();
        if (RichChatPrivateMessageBridge.filterEnabled()
                && ((!trimmedChatText.isEmpty() && !trimmedChatText.startsWith("/")) || RichChatUploadDraft.hasPending())) {
            if (!RichChatPrivateMessageBridge.hasTarget()) {
                MinecraftClient minecraft = MinecraftClient.getInstance();
                if (minecraft != null && minecraft.inGameHud != null) {
                    minecraft.inGameHud.getChatHud().addMessage(Text.literal("Private filter is on, but no target player is selected."));
                }
                cir.setReturnValue(true);
                return;
            }
            if (RichChatUploadDraft.hasPending()) {
                koil$sendPrivateWithAttachment(chatText, addToHistory, cir);
            } else {
                koil$sendPrivateMessage(chatText, addToHistory, cir);
            }
            return;
        }

        if (!trimmedChatText.isEmpty() && trimmedChatText.startsWith("/")) {
            Matcher executePrivateMatcher = KOIL_EXECUTE_PRIVATE_COMMAND.matcher(trimmedChatText);
            if (executePrivateMatcher.matches()) {
                koil$sendExplicitExecutePrivateMessage(
                        chatText,
                        addToHistory,
                        cir,
                        executePrivateMatcher.group(1),
                        executePrivateMatcher.group(2),
                        executePrivateMatcher.group(3),
                        executePrivateMatcher.group(4)
                );
                return;
            }
            Matcher privateMatcher = KOIL_PRIVATE_COMMAND.matcher(trimmedChatText);
            if (privateMatcher.matches()) {
                koil$sendExplicitPrivateMessage(chatText, addToHistory, cir, privateMatcher.group(1), privateMatcher.group(2));
                return;
            }
        }

        if (RichChatUploadDraft.hasPending()) {
            if (trimmedChatText.startsWith("/")) {
                Matcher privateMatcher = KOIL_PRIVATE_COMMAND.matcher(trimmedChatText);
                if (privateMatcher.matches()) {
                    koil$sendExplicitPrivateWithAttachment(chatText, addToHistory, cir, privateMatcher.group(1), privateMatcher.group(2));
                    return;
                }
            }
            koil$sendWithAttachment(chatText, addToHistory, cir);
            return;
        }

        if (koil$isSequentialMultilineCommand(chatText)) {
            koil$sendSequentialMultilineCommands(chatText, addToHistory, cir);
            return;
        }

        String singleLineNetworkText = koil$networkSafeMultiline(chatText == null ? "" : chatText);
        if (!singleLineNetworkText.startsWith("/") && singleLineNetworkText.length() > KOIL_MAX_VANILLA_CHAT_CHARS) {
            String historyText = koil$truncateDraft(chatText == null ? "" : chatText.replace("\r\n", "\n").replace('\r', '\n')).trim();
            if (!historyText.isEmpty()) {
                koil$sendChunkedPublicMessage(historyText, singleLineNetworkText, addToHistory, cir);
                return;
            }
        }

        if (chatText.indexOf('\n') < 0 && chatText.indexOf('\r') < 0) {
            RichChatMessageData richMessage = koil$rememberRichChatMetadata(chatText, chatText, "text", List.of());
            if (richMessage != null && RichChatSyncClientBridge.canSync()) {
                RichChatSyncClientBridge.send(richMessage);
            }
            return;
        }

        String historyText = koil$truncateDraft(chatText.replace("\r\n", "\n").replace('\r', '\n')).trim();
        if (historyText.isEmpty()) {
            cir.setReturnValue(true);
            return;
        }

        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (addToHistory && minecraft.inGameHud != null) {
            minecraft.inGameHud.getChatHud().addToMessageHistory(historyText);
        }

        String networkText = koil$networkSafeMultiline(chatText);
        if (networkText.length() > KOIL_MAX_VANILLA_CHAT_CHARS) {
            if (networkText.startsWith("/")) {
                if (minecraft.inGameHud != null) {
                    minecraft.inGameHud.getChatHud().addMessage(Text.literal("Long multiline commands still exceed vanilla chat limits. Rich packet sync is still pending."));
                }
                cir.setReturnValue(true);
                return;
            }
            List<String> chunks = koil$splitFallbackChunks(networkText, KOIL_MAX_VANILLA_CHAT_CHARS);
            RichChatMessageData richMessage = koil$rememberRichChatMetadata(
                    UUID.randomUUID(),
                    historyText,
                    networkText,
                    "multiline_overflow",
                    List.of(),
                    RichChatScope.PUBLIC,
                    koil$chunkMetadata(chunks, Map.of())
            );
            if (richMessage != null && RichChatSyncClientBridge.canSync()) {
                RichChatSyncClientBridge.send(richMessage);
            }
            if (minecraft.inGameHud != null && minecraft.player != null) {
                String visiblePrefix = "<" + minecraft.player.getGameProfile().getName() + "> ";
                koil$addFormattedPreviewMessage(minecraft, koil$visibleLocalMultiline(historyText, visiblePrefix));
                LocalOverflowChatBridge.remember(visiblePrefix, chunks);
                for (String chunk : chunks) {
                    minecraft.player.networkHandler.sendChatMessage(chunk);
                }
            }
            cir.setReturnValue(true);
            return;
        }

        networkText = StringHelper.truncateChat(networkText);
        if (!networkText.isEmpty() && minecraft.player != null && minecraft.player.networkHandler != null) {
            RichChatMessageData richMessage = koil$rememberRichChatMetadata(historyText, networkText, "multiline", List.of());
            LocalMultilineChatBridge.remember(networkText, historyText);
            if (richMessage != null && RichChatSyncClientBridge.canSync()) {
                RichChatSyncClientBridge.send(richMessage);
            }
            if (networkText.startsWith("/")) {
                minecraft.player.networkHandler.sendChatCommand(networkText.substring(1));
            } else {
                minecraft.player.networkHandler.sendChatMessage(networkText);
            }
        }
        cir.setReturnValue(true);
    }

    @Unique
    private void koil$sendPrivateMessage(String chatText, boolean addToHistory, CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        String historyText = koil$truncateDraft(chatText == null ? "" : chatText.replace("\r\n", "\n").replace('\r', '\n')).trim();
        if (historyText.isEmpty()) {
            cir.setReturnValue(true);
            return;
        }
        if (addToHistory && minecraft != null && minecraft.inGameHud != null) {
            minecraft.inGameHud.getChatHud().addToMessageHistory(historyText);
        }
        String routedBody = historyText.indexOf('\n') >= 0 ? koil$networkSafeMultiline(historyText) : historyText;
        String command = RichChatPrivateMessageBridge.routeOutgoing(routedBody);
        if (command.length() > KOIL_MAX_VANILLA_CHAT_CHARS) {
            koil$sendChunkedPrivateMessage(historyText, routedBody, RichChatPrivateMessageBridge.targetPlayer(), cir);
            return;
        }
        if (minecraft != null && minecraft.player != null && minecraft.player.networkHandler != null) {
            RichChatMessageData richMessage = koil$rememberRichChatMetadata(
                    UUID.randomUUID(),
                    historyText,
                    routedBody,
                    "private_filter",
                    List.of(),
                    RichChatScope.PRIVATE,
                    Map.of("pm_target", RichChatPrivateMessageBridge.targetPlayer())
            );
            if (historyText.indexOf('\n') >= 0) {
                LocalMultilineChatBridge.remember(routedBody, historyText);
            }
            if (richMessage != null && RichChatSyncClientBridge.canSync()) {
                RichChatSyncClientBridge.send(richMessage);
            }
            minecraft.player.networkHandler.sendChatCommand(command);
        }
        cir.setReturnValue(true);
    }

    @Unique
    private void koil$sendExplicitPrivateMessage(String chatText, boolean addToHistory, CallbackInfoReturnable<Boolean> cir, String targetPlayer, String bodyText) {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        String normalizedBody = koil$truncateDraft(bodyText == null ? "" : bodyText.replace("\r\n", "\n").replace('\r', '\n')).trim();
        if (normalizedBody.isEmpty()) {
            if (minecraft != null && minecraft.player != null && minecraft.player.networkHandler != null) {
                String normalizedCommand = koil$truncateDraft(chatText == null ? "" : chatText.replace("\r\n", "\n").replace('\r', '\n')).trim();
                if (!normalizedCommand.isEmpty()) {
                    minecraft.player.networkHandler.sendChatCommand(normalizedCommand.substring(1));
                }
            }
            cir.setReturnValue(true);
            return;
        }
        if (addToHistory && minecraft != null && minecraft.inGameHud != null) {
            minecraft.inGameHud.getChatHud().addToMessageHistory(koil$truncateDraft(chatText == null ? "" : chatText.replace("\r\n", "\n").replace('\r', '\n')).trim());
        }
        String routedBody = normalizedBody.indexOf('\n') >= 0 ? koil$networkSafeMultiline(normalizedBody) : normalizedBody;
        String command = "msg " + targetPlayer + " " + routedBody;
        if (command.length() > KOIL_MAX_VANILLA_CHAT_CHARS) {
            koil$sendChunkedPrivateMessage(normalizedBody, routedBody, targetPlayer, cir);
            return;
        }
        if (minecraft != null && minecraft.player != null && minecraft.player.networkHandler != null) {
            RichChatMessageData richMessage = koil$rememberRichChatMetadata(
                    UUID.randomUUID(),
                    normalizedBody,
                    routedBody,
                    "private_command_text",
                    List.of(),
                    RichChatScope.PRIVATE,
                    Map.of("pm_target", targetPlayer)
            );
            if (normalizedBody.indexOf('\n') >= 0) {
                LocalMultilineChatBridge.remember(routedBody, normalizedBody);
            }
            if (richMessage != null && RichChatSyncClientBridge.canSync()) {
                RichChatSyncClientBridge.send(richMessage);
            }
            minecraft.player.networkHandler.sendChatCommand(command);
        }
        cir.setReturnValue(true);
    }

    @Unique
    private void koil$sendExplicitExecutePrivateMessage(String chatText, boolean addToHistory, CallbackInfoReturnable<Boolean> cir, String executorName, String whisperCommand, String targetPlayer, String bodyText) {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        String normalizedBody = koil$truncateDraft(bodyText == null ? "" : bodyText.replace("\r\n", "\n").replace('\r', '\n')).trim();
        if (normalizedBody.isEmpty()) {
            if (minecraft != null && minecraft.player != null && minecraft.player.networkHandler != null) {
                String normalizedCommand = koil$truncateDraft(chatText == null ? "" : chatText.replace("\r\n", "\n").replace('\r', '\n')).trim();
                if (!normalizedCommand.isEmpty()) {
                    minecraft.player.networkHandler.sendChatCommand(normalizedCommand.substring(1));
                }
            }
            cir.setReturnValue(true);
            return;
        }
        if (addToHistory && minecraft != null && minecraft.inGameHud != null) {
            minecraft.inGameHud.getChatHud().addToMessageHistory(koil$truncateDraft(chatText == null ? "" : chatText.replace("\r\n", "\n").replace('\r', '\n')).trim());
        }
        String routedBody = normalizedBody.indexOf('\n') >= 0 ? koil$networkSafeMultiline(normalizedBody) : normalizedBody;
        String command = "execute as " + executorName + " run " + whisperCommand + " " + targetPlayer + " " + routedBody;
        if (command.length() > KOIL_MAX_VANILLA_CHAT_CHARS) {
            koil$sendChunkedExecutePrivateMessage(normalizedBody, routedBody, executorName, whisperCommand, targetPlayer, cir);
            return;
        }
        if (minecraft != null && minecraft.player != null && minecraft.player.networkHandler != null) {
            minecraft.player.networkHandler.sendChatCommand(command);
        }
        cir.setReturnValue(true);
    }

    @Unique
    private void koil$sendWithAttachment(String chatText, boolean addToHistory, CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (RichChatUploadDraft.isProcessing()) {
            if (minecraft.inGameHud != null) {
                minecraft.inGameHud.getChatHud().addMessage(Text.literal("Attachment is still preparing. Try again in a moment."));
            }
            cir.setReturnValue(true);
            return;
        }
        if (!RichChatUploadDraft.isReady()) {
            if (minecraft.inGameHud != null) {
                minecraft.inGameHud.getChatHud().addMessage(Text.literal(RichChatUploadDraft.statusText()));
            }
            cir.setReturnValue(true);
            return;
        }

        String normalizedRaw = koil$truncateDraft(chatText == null ? "" : chatText.replace("\r\n", "\n").replace('\r', '\n')).trim();
        String attachmentLabel = RichChatUploadDraft.fallbackLabel();
        String historyText = normalizedRaw.isBlank() ? attachmentLabel : normalizedRaw + "\n" + attachmentLabel;
        String fallbackBase = normalizedRaw.isBlank() ? attachmentLabel : koil$networkSafeMultiline(normalizedRaw) + " " + attachmentLabel;
        String networkText = StringHelper.truncateChat(fallbackBase);
        UUID messageId = UUID.randomUUID();
        RichChatAttachment attachment = koil$withChatScope(RichChatUploadDraft.attachmentForMessage(messageId), "public", "");

        if (addToHistory && minecraft.inGameHud != null) {
            minecraft.inGameHud.getChatHud().addToMessageHistory(historyText);
        }
        if (!networkText.isEmpty() && minecraft.player != null && minecraft.player.networkHandler != null) {
            RichChatMessageData richMessage = koil$rememberRichChatMetadata(
                    messageId,
                    normalizedRaw,
                    networkText,
                    "upload",
                    attachment == null ? List.of() : List.of(attachment),
                    RichChatScope.PUBLIC,
                    Map.of()
            );
            if (attachment != null) {
                LocalRichAttachmentBridge.remember(networkText, normalizedRaw, attachment);
            } else {
                LocalMultilineChatBridge.remember(networkText, historyText);
            }
            if (richMessage != null && RichChatSyncClientBridge.canSync()) {
                RichChatSyncClientBridge.send(richMessage);
            }
            if (networkText.startsWith("/")) {
                minecraft.player.networkHandler.sendChatCommand(networkText.substring(1));
            } else {
                minecraft.player.networkHandler.sendChatMessage(networkText);
            }
            RichChatUploadDraft.clear();
        }
        cir.setReturnValue(true);
    }

    @Unique
    private void koil$sendPrivateWithAttachment(String chatText, boolean addToHistory, CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (RichChatUploadDraft.isProcessing()) {
            if (minecraft != null && minecraft.inGameHud != null) {
                minecraft.inGameHud.getChatHud().addMessage(Text.literal("Attachment is still preparing. Try again in a moment."));
            }
            cir.setReturnValue(true);
            return;
        }
        if (!RichChatUploadDraft.isReady()) {
            if (minecraft != null && minecraft.inGameHud != null) {
                minecraft.inGameHud.getChatHud().addMessage(Text.literal(RichChatUploadDraft.statusText()));
            }
            cir.setReturnValue(true);
            return;
        }

        String normalizedRaw = koil$truncateDraft(chatText == null ? "" : chatText.replace("\r\n", "\n").replace('\r', '\n')).trim();
        String attachmentLabel = RichChatUploadDraft.fallbackLabel();
        String historyText = normalizedRaw.isBlank() ? attachmentLabel : normalizedRaw + "\n" + attachmentLabel;
        String routedBody = normalizedRaw.isBlank() ? attachmentLabel : koil$networkSafeMultiline(normalizedRaw) + " " + attachmentLabel;
        String command = RichChatPrivateMessageBridge.routeOutgoing(routedBody);
        UUID messageId = UUID.randomUUID();
        RichChatAttachment attachment = koil$withChatScope(RichChatUploadDraft.attachmentForMessage(messageId), "private", RichChatPrivateMessageBridge.targetPlayer());

        if (addToHistory && minecraft != null && minecraft.inGameHud != null) {
            minecraft.inGameHud.getChatHud().addToMessageHistory(historyText);
        }
        if (command.length() > KOIL_MAX_VANILLA_CHAT_CHARS) {
            koil$sendChunkedPrivateAttachment(messageId, normalizedRaw, routedBody, historyText, attachment, RichChatPrivateMessageBridge.targetPlayer(), "private_filter_upload", cir);
            return;
        }
        if (minecraft != null && minecraft.player != null && minecraft.player.networkHandler != null) {
            RichChatMessageData richMessage = koil$rememberRichChatMetadata(
                    messageId,
                    normalizedRaw,
                    routedBody,
                    "private_filter_upload",
                    attachment == null ? List.of() : List.of(attachment),
                    RichChatScope.PRIVATE,
                    Map.of("pm_target", RichChatPrivateMessageBridge.targetPlayer())
            );
            if (attachment != null) {
                LocalRichAttachmentBridge.remember(routedBody, normalizedRaw, attachment);
            } else {
                LocalMultilineChatBridge.remember(routedBody, historyText);
            }
            if (richMessage != null && RichChatSyncClientBridge.canSync()) {
                RichChatSyncClientBridge.send(richMessage);
            }
            minecraft.player.networkHandler.sendChatCommand(command);
            RichChatUploadDraft.clear();
        }
        cir.setReturnValue(true);
    }

    @Unique
    private void koil$sendExplicitPrivateWithAttachment(String chatText, boolean addToHistory, CallbackInfoReturnable<Boolean> cir, String targetPlayer, String bodyText) {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (RichChatUploadDraft.isProcessing()) {
            if (minecraft != null && minecraft.inGameHud != null) {
                minecraft.inGameHud.getChatHud().addMessage(Text.literal("Attachment is still preparing. Try again in a moment."));
            }
            cir.setReturnValue(true);
            return;
        }
        if (!RichChatUploadDraft.isReady()) {
            if (minecraft != null && minecraft.inGameHud != null) {
                minecraft.inGameHud.getChatHud().addMessage(Text.literal(RichChatUploadDraft.statusText()));
            }
            cir.setReturnValue(true);
            return;
        }

        String normalizedBody = koil$truncateDraft(bodyText == null ? "" : bodyText.replace("\r\n", "\n").replace('\r', '\n')).trim();
        String attachmentLabel = RichChatUploadDraft.fallbackLabel();
        String historyText = normalizedBody.isBlank() ? attachmentLabel : normalizedBody + "\n" + attachmentLabel;
        String fallbackBody = normalizedBody.isBlank() ? attachmentLabel : koil$networkSafeMultiline(normalizedBody) + " " + attachmentLabel;
        String normalizedCommand = koil$truncateDraft(chatText == null ? "" : chatText.replace("\r\n", "\n").replace('\r', '\n')).trim();
        if (normalizedCommand.length() > KOIL_MAX_VANILLA_CHAT_CHARS) {
            UUID messageId = UUID.randomUUID();
            RichChatAttachment attachment = koil$withChatScope(RichChatUploadDraft.attachmentForMessage(messageId), "private", targetPlayer);
            if (addToHistory && minecraft != null && minecraft.inGameHud != null) {
                minecraft.inGameHud.getChatHud().addToMessageHistory(normalizedCommand);
            }
            koil$sendChunkedPrivateAttachment(messageId, normalizedBody, fallbackBody, historyText, attachment, targetPlayer, "private_command_upload", cir);
            return;
        }

        UUID messageId = UUID.randomUUID();
        RichChatAttachment attachment = koil$withChatScope(RichChatUploadDraft.attachmentForMessage(messageId), "private", targetPlayer);
        if (addToHistory && minecraft != null && minecraft.inGameHud != null) {
            minecraft.inGameHud.getChatHud().addToMessageHistory(normalizedCommand);
        }
        if (minecraft != null && minecraft.player != null && minecraft.player.networkHandler != null) {
            RichChatMessageData richMessage = koil$rememberRichChatMetadata(
                    messageId,
                    normalizedBody,
                    fallbackBody,
                    "private_command_upload",
                    attachment == null ? List.of() : List.of(attachment),
                    RichChatScope.PRIVATE,
                    Map.of("pm_target", targetPlayer)
            );
            if (attachment != null) {
                LocalRichAttachmentBridge.remember(fallbackBody, normalizedBody, attachment);
            } else {
                LocalMultilineChatBridge.remember(fallbackBody, historyText);
            }
            if (richMessage != null && RichChatSyncClientBridge.canSync()) {
                RichChatSyncClientBridge.send(richMessage);
            }
            minecraft.player.networkHandler.sendChatCommand(normalizedCommand.substring(1));
            RichChatUploadDraft.clear();
        }
        cir.setReturnValue(true);
    }

    @Unique
    private RichChatMessageData koil$rememberRichChatMetadata(String rawText, String fallbackText, String phase, List<RichChatAttachment> attachments) {
        return koil$rememberRichChatMetadata(UUID.randomUUID(), rawText, fallbackText, phase, attachments, RichChatScope.PUBLIC, Map.of());
    }

    @Unique
    private RichChatMessageData koil$rememberRichChatMetadata(UUID messageId, String rawText, String fallbackText, String phase, List<RichChatAttachment> attachments) {
        return koil$rememberRichChatMetadata(messageId, rawText, fallbackText, phase, attachments, RichChatScope.PUBLIC, Map.of());
    }

    @Unique
    private RichChatMessageData koil$rememberRichChatMetadata(UUID messageId, String rawText, String fallbackText, String phase, List<RichChatAttachment> attachments, RichChatScope scope, Map<String, String> extraMetadata) {
        boolean hasAttachments = attachments != null && !attachments.isEmpty();
        if ((rawText == null || rawText.isBlank()) && !hasAttachments) {
            return null;
        }
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return null;
        }

        String normalizedRaw = rawText == null ? "" : rawText.replace("\r\n", "\n").replace('\r', '\n').trim();
        RichChatLatexDetector.Result latex = RichChatLatexDetector.detect(normalizedRaw);
        boolean multiline = normalizedRaw.indexOf('\n') >= 0;
        if (!latex.hasLatex() && !multiline && !hasAttachments) {
            return null;
        }

        RichMessageBuilder builder = RichMessageBuilder.create()
                .messageId(messageId)
                .type(hasAttachments ? com.spirit.koil.api.chat.RichChatMessageType.MIXED : (latex.hasLatex() ? latex.messageType() : com.spirit.koil.api.chat.RichChatMessageType.MULTILINE_TEXT))
                .rawText(normalizedRaw)
                .fallbackText(fallbackText == null ? normalizedRaw : fallbackText)
                .sender(minecraft.player.getUuid(), minecraft.player.getGameProfile().getName())
                .scope(scope)
                .metadata("source", "vanilla_chat")
                .metadata("phase", phase);

        if (latex.hasLatex()) {
            for (com.spirit.koil.api.chat.RichChatSegment segment : latex.segments()) {
                builder.segment(segment);
            }
            builder.metadata("latex", "true")
                    .metadata("latex_inline_count", Integer.toString(latex.inlineCount()))
                    .metadata("latex_block_count", Integer.toString(latex.blockCount()))
                    .metadata("latex_document_count", Integer.toString(latex.documentCount()));
        } else if (!normalizedRaw.isBlank()) {
            builder.segment(com.spirit.koil.api.chat.RichChatSegment.multilineText(normalizedRaw));
        }
        if (hasAttachments) {
            builder.metadata("attachment_count", Integer.toString(attachments.size()));
            for (RichChatAttachment attachment : attachments) {
                builder.attachment(attachment);
            }
        }
        if (extraMetadata != null) {
            for (Map.Entry<String, String> entry : extraMetadata.entrySet()) {
                builder.metadata(entry.getKey(), entry.getValue());
            }
        }

        RichChatMessageData richMessage = builder.build();
        RichChatMessageStore.remember(richMessage);
        return richMessage;
    }

    @Unique
    private RichChatAttachment koil$withChatScope(RichChatAttachment attachment, String scope, String target) {
        if (attachment == null) {
            return null;
        }
        Map<String, String> metadata = new LinkedHashMap<>(attachment.metadata());
        metadata.put("chat_scope", scope == null ? "" : scope);
        MinecraftClient minecraft = MinecraftClient.getInstance();
        String sender = minecraft != null && minecraft.player != null && minecraft.player.getGameProfile() != null
                ? minecraft.player.getGameProfile().getName()
                : "";
        if (!sender.isBlank()) {
            metadata.put("chat_sender", sender);
        }
        if (target != null && !target.isBlank()) {
            metadata.put("chat_target", target);
            metadata.put("chat_partner", target);
        }
        return new RichChatAttachment(
                attachment.attachmentId(),
                attachment.type(),
                attachment.fileName(),
                attachment.mimeType(),
                attachment.sizeBytes(),
                attachment.sha256(),
                attachment.width(),
                attachment.height(),
                attachment.durationMillis(),
                metadata
        );
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/widget/TextFieldWidget;render(Lnet/minecraft/client/gui/DrawContext;IIF)V"))
    private void koil$renderVanillaOrMultilineField(TextFieldWidget field, DrawContext context, int mouseX, int mouseY, float delta) {
        if (!koil$isMultilineDraft()) {
            field.render(context, mouseX, mouseY, delta);
            koil$renderSingleLineDraftPreview(context, field);
        }
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
    private void koil$renderVanillaInputBackground(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        if (!koil$isMultilineDraft()) {
            context.fill(x1, y1, x2, y2, color);
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void koil$renderMultilineDraft(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (koil$chatScrollbarDragging) {
            MinecraftClient minecraft = MinecraftClient.getInstance();
            long handle = minecraft != null && minecraft.getWindow() != null ? minecraft.getWindow().getHandle() : 0L;
            boolean leftDown = handle != 0L && GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (minecraft != null && minecraft.inGameHud != null && minecraft.inGameHud.getChatHud() instanceof com.spirit.koil.chat.internal.ChatHudRefreshBridge bridge) {
                if (leftDown) {
                    bridge.koil$dragChatScrollbar(mouseY);
                } else {
                    koil$chatScrollbarDragging = false;
                    bridge.koil$endChatScrollbarDrag();
                }
            } else {
                koil$chatScrollbarDragging = false;
            }
        }
        boolean focusedAttachment = RichChatAttachmentRenderer.hasFocusedAttachment();
        if (!focusedAttachment && koil$showChatControls()) {
            koil$renderUploadButton(context, mouseX, mouseY);
            koil$renderPrivateMessageButton(context, mouseX, mouseY);
            koil$renderUploadDraft(context, mouseX, mouseY);
        }
        if (!koil$isMultilineDraft()) {
            MultilineChatInputLayout.clear();
            RichChatAttachmentRenderer.renderFocused(context, this.width, this.height, mouseX, mouseY);
            koil$pmMenu.render(context, mouseX, mouseY);
            koil$pmTargetMenu.render(context, mouseX, mouseY);
            RichChatAttachmentRenderer.renderChatHoverTooltip(context, mouseX, mouseY);
            return;
        }
        koil$updateMultilineLayoutReservation();
        koil$updateMultilineDragState(mouseX, mouseY);

        TextRenderer renderer = this.textRenderer;
        List<String> lines = koil$splitLines(chatField.getText());
        int cursorLine = koil$cursorLine(lines, chatField.getCursor());
        int lineHeight = renderer.fontHeight + 2;
        int maxVisibleLines = Math.max(2, Math.min(6, (this.height - 34) / lineHeight));
        int visibleLines = Math.min(maxVisibleLines, Math.max(2, lines.size()));
        int firstLine = koil$firstVisibleLine(lines, visibleLines, cursorLine);

        int left = 2;
        int right = this.width - 2;
        int bottom = this.height - 2;
        int top = bottom - visibleLines * lineHeight - 8;
        int background = MinecraftClient.getInstance().options.getTextBackgroundColor(Integer.MIN_VALUE);
        context.fill(left, top, right, bottom, background);

        int textX = left + 4;
        int textY = top + 5;
        for (int i = 0; i < visibleLines; i++) {
            int lineIndex = firstLine + i;
            if (lineIndex >= lines.size()) {
                break;
            }
            int lineY = textY + i * lineHeight;
            String line = renderer.trimToWidth(lines.get(lineIndex), Math.max(20, right - left - 12));
            koil$renderDraftSelection(context, renderer, lines, lineIndex, textX, lineY);
            koil$renderDraftLine(context, line, textX, lineY, Math.max(20, right - left - 12));
        }

        if ((System.currentTimeMillis() / 300L) % 2L == 0L && cursorLine >= firstLine && cursorLine < firstLine + visibleLines) {
            int cursorX = textX + renderer.getWidth(koil$lineBeforeCursor(lines, chatField.getCursor()));
            int cursorY = textY + (cursorLine - firstLine) * lineHeight - 1;
            context.fill(cursorX, cursorY, cursorX + 1, cursorY + renderer.fontHeight + 1, 0xFFFFFFFF);
        }

        if (lines.size() > visibleLines) {
            int trackX = right - 5;
            int trackTop = top + 5;
            int trackBottom = bottom - 5;
            int nubHeight = Math.max(10, (trackBottom - trackTop) * visibleLines / lines.size());
            int maxFirst = Math.max(1, lines.size() - visibleLines);
            int nubY = trackTop + (trackBottom - trackTop - nubHeight) * firstLine / maxFirst;
            context.fill(trackX, trackTop, trackX + 1, trackBottom, 0x66303030);
            context.fill(trackX - 1, nubY, trackX + 2, nubY + nubHeight, 0xCCBFC7D5);
        }
        RichChatAttachmentRenderer.renderFocused(context, this.width, this.height, mouseX, mouseY);
        koil$pmMenu.render(context, mouseX, mouseY);
        koil$pmTargetMenu.render(context, mouseX, mouseY);
        RichChatAttachmentRenderer.renderChatHoverTooltip(context, mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void koil$automationHudMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button == 0 && koil$pmTargetMenu.isOpen()) {
            PopupMenu.MenuEntry selected = koil$pmTargetMenu.click(mouseX, mouseY);
            if (selected != null) {
                RichChatPrivateMessageBridge.handleMenuAction(selected.id());
                koil$pmMenu.close();
                if (chatField != null) {
                    chatField.setFocused(true);
                }
                cir.setReturnValue(true);
                return;
            }
            if (!koil$pmTargetMenu.isOpen()) {
                cir.setReturnValue(true);
                return;
            }
        }

        if (button == 0 && koil$pmMenu.isOpen()) {
            PopupMenu.MenuEntry hovered = koil$pmMenu.entryAt(mouseX, mouseY);
            if (hovered != null && "pm_target_header".equals(hovered.id())) {
                koil$pmTargetMenu.openNearAnchor((int) mouseX, (int) mouseY - 10, 0, this.width, this.height, RichChatPrivateMessageBridge.targetMenuEntries(MinecraftClient.getInstance()));
                if (chatField != null) {
                    chatField.setFocused(true);
                }
                cir.setReturnValue(true);
                return;
            }
            PopupMenu.MenuEntry selected = koil$pmMenu.click(mouseX, mouseY);
            if (selected != null) {
                RichChatPrivateMessageBridge.handleMenuAction(selected.id());
                if (chatField != null) {
                    chatField.setFocused(true);
                }
                cir.setReturnValue(true);
                return;
            }
            if (!koil$pmMenu.isOpen()) {
                cir.setReturnValue(true);
                return;
            }
        }

        if (RichChatAttachmentRenderer.mouseClicked(mouseX, mouseY, button)) {
            cir.setReturnValue(true);
            return;
        }

        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (button == 0 && minecraft != null && minecraft.inGameHud != null && minecraft.inGameHud.getChatHud() instanceof com.spirit.koil.chat.internal.ChatHudRefreshBridge bridge) {
            if (bridge.koil$chatScrollbarContains(mouseX, mouseY)) {
                bridge.koil$beginChatScrollbarDrag(mouseY);
                koil$chatScrollbarDragging = true;
                cir.setReturnValue(true);
                return;
            }
        }

        if (AutomationChatHudRenderer.mouseClicked(MinecraftClient.getInstance(), mouseX, mouseY, button)) {
            cir.setReturnValue(true);
            return;
        }

        if (button == 0 && koil$mouseInsideUploadButton(mouseX, mouseY)) {
            UiSoundHelper.playButtonClick();
            koil$openRichChatFileChooser();
            if (chatField != null) {
                chatField.setFocused(true);
            }
            cir.setReturnValue(true);
            return;
        }

        if (button == 0 && koil$mouseInsidePrivateMessageButton(mouseX, mouseY)) {
            koil$pmTargetMenu.close();
            koil$pmMenu.toggleAtPointer(mouseX, mouseY, this.width, this.height, RichChatPrivateMessageBridge.menuEntries(MinecraftClient.getInstance()));
            if (chatField != null) {
                chatField.setFocused(true);
            }
            cir.setReturnValue(true);
            return;
        }

        if (button == 0 && RichChatUploadDraft.hasPending() && koil$mouseInsideUploadRemove(mouseX, mouseY)) {
            RichChatUploadDraft.clear();
            if (chatField != null) {
                chatField.setFocused(true);
            }
            cir.setReturnValue(true);
            return;
        }

        if (button == 0 && koil$isMultilineDraft() && koil$mouseInsideMultilineDraft(mouseX, mouseY)) {
            chatField.setFocused(true);
            int cursor = koil$cursorFromMouse(mouseX, mouseY);
            long now = System.currentTimeMillis();
            boolean repeatedClick = now - koil$lastDraftClickTime <= KOIL_MULTI_CLICK_MS
                    && Math.abs(mouseX - koil$lastDraftClickX) <= KOIL_MULTI_CLICK_RANGE
                    && Math.abs(mouseY - koil$lastDraftClickY) <= KOIL_MULTI_CLICK_RANGE;
            koil$draftClickCount = repeatedClick ? Math.min(3, koil$draftClickCount + 1) : 1;
            koil$lastDraftClickTime = now;
            koil$lastDraftClickX = mouseX;
            koil$lastDraftClickY = mouseY;
            if (Screen.hasShiftDown()) {
                int anchor = koil$multilineSelectionAnchor >= 0 ? koil$multilineSelectionAnchor : chatField.getCursor();
                chatField.setCursor(cursor);
                chatField.setSelectionStart(cursor);
                chatField.setSelectionEnd(anchor);
            } else if (koil$draftClickCount == 2) {
                koil$selectDraftWord(cursor);
                koil$multilineSelectionAnchor = Math.min(koil$selectionStart(), koil$selectionEnd());
                koil$multilineDragSelecting = false;
            } else if (koil$draftClickCount >= 3) {
                koil$selectDraftLine(cursor);
                koil$multilineSelectionAnchor = Math.min(koil$selectionStart(), koil$selectionEnd());
                koil$multilineDragSelecting = false;
            } else {
                chatField.setCursor(cursor);
                chatField.setSelectionStart(cursor);
                chatField.setSelectionEnd(cursor);
                koil$multilineSelectionAnchor = cursor;
                koil$multilineDragSelecting = true;
            }
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void koil$scrollMultilineDraft(double mouseX, double mouseY, double amount, CallbackInfoReturnable<Boolean> cir) {
        if (RichChatAttachmentRenderer.mouseScrolled(mouseX, mouseY, amount)) {
            cir.setReturnValue(true);
            return;
        }

        if (!koil$isMultilineDraft() || !koil$mouseInsideMultilineDraft(mouseX, mouseY)) {
            return;
        }

        List<String> lines = koil$splitLines(chatField.getText());
        int visibleLines = koil$visibleLineCount(lines);
        int maxFirst = Math.max(0, lines.size() - visibleLines);
        int direction = amount > 0.0D ? -1 : 1;
        koil$draftScrollLine = Math.max(0, Math.min(maxFirst, koil$draftScrollLine + direction));
        cir.setReturnValue(true);
    }

    @Unique
    private boolean koil$isMultilineDraft() {
        boolean multiline = chatField != null && chatField.getText().indexOf('\n') >= 0;
        if (!multiline) {
            koil$draftScrollLine = 0;
            koil$multilineDragSelecting = false;
        }
        return multiline;
    }

    @Unique
    private void koil$insertDraftText(String inserted) {
        String text = chatField.getText();
        int selectionStart = Math.max(0, Math.min(koil$selectionStart(), text.length()));
        int selectionEnd = Math.max(0, Math.min(koil$selectionEnd(), text.length()));
        int replaceStart = Math.min(selectionStart, selectionEnd);
        int replaceEnd = Math.max(selectionStart, selectionEnd);
        int cursor = Math.max(0, Math.min(chatField.getCursor(), text.length()));
        if (replaceStart == replaceEnd) {
            replaceStart = cursor;
            replaceEnd = cursor;
        }
        String next = koil$truncateDraft(text.substring(0, replaceStart) + inserted + text.substring(replaceEnd));
        chatField.setText(next);
        int nextCursor = Math.min(chatField.getText().length(), replaceStart + inserted.length());
        chatField.setCursor(nextCursor);
        chatField.setSelectionStart(nextCursor);
        chatField.setSelectionEnd(nextCursor);
        koil$multilineSelectionAnchor = nextCursor;
        koil$updateMultilineLayoutReservation();
    }

    @Unique
    private String koil$networkSafeMultiline(String chatText) {
        return chatText.replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
                .replaceAll("[\\t ]*\\n[\\t ]*", " | ");
    }

    @Unique
    private List<String> koil$splitLines(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= normalized.length(); i++) {
            if (i == normalized.length() || normalized.charAt(i) == '\n') {
                lines.add(normalized.substring(start, i));
                start = i + 1;
            }
        }
        return lines.isEmpty() ? List.of("") : lines;
    }

    @Unique
    private int koil$cursorLine(List<String> lines, int cursor) {
        int index = 0;
        for (int i = 0; i < lines.size(); i++) {
            int lineLength = lines.get(i).length();
            if (cursor <= index + lineLength) {
                return i;
            }
            index += lineLength + 1;
        }
        return Math.max(0, lines.size() - 1);
    }

    @Unique
    private String koil$lineBeforeCursor(List<String> lines, int cursor) {
        int index = 0;
        for (String line : lines) {
            int end = index + line.length();
            if (cursor <= end) {
                return line.substring(0, Math.max(0, Math.min(line.length(), cursor - index)));
            }
            index = end + 1;
        }
        return "";
    }

    @Unique
    private boolean koil$moveCursorVertically(int direction) {
        String text = chatField.getText().replace("\r\n", "\n").replace('\r', '\n');
        int cursor = Math.max(0, Math.min(chatField.getCursor(), text.length()));
        int currentStart = text.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
        int currentEnd = text.indexOf('\n', cursor);
        if (currentEnd < 0) {
            currentEnd = text.length();
        }
        int column = cursor - currentStart;

        if (direction < 0) {
            if (currentStart <= 0) {
                return false;
            }
            int previousEnd = currentStart - 1;
            int previousStart = text.lastIndexOf('\n', Math.max(0, previousEnd - 1)) + 1;
            chatField.setCursor(previousStart + Math.min(column, previousEnd - previousStart));
            return true;
        }

        if (currentEnd >= text.length()) {
            return false;
        }
        int nextStart = currentEnd + 1;
        int nextEnd = text.indexOf('\n', nextStart);
        if (nextEnd < 0) {
            nextEnd = text.length();
        }
        chatField.setCursor(nextStart + Math.min(column, nextEnd - nextStart));
        return true;
    }

    @Unique
    private void koil$updateMultilineDragState(int mouseX, int mouseY) {
        if (!koil$multilineDragSelecting || chatField == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        long handle = client != null && client.getWindow() != null ? client.getWindow().getHandle() : 0L;
        boolean leftDown = handle != 0L && GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (!leftDown) {
            koil$multilineDragSelecting = false;
            return;
        }
        int cursor = koil$cursorFromMouse(mouseX, mouseY);
        chatField.setCursor(cursor);
        chatField.setSelectionStart(cursor);
        chatField.setSelectionEnd(Math.max(0, koil$multilineSelectionAnchor));
    }

    @Unique
    private void koil$renderDraftSelection(DrawContext context, TextRenderer renderer, List<String> lines, int lineIndex, int textX, int lineY) {
        if (!(chatField instanceof TextFieldWidgetAccessor accessor) || lines == null || lineIndex < 0 || lineIndex >= lines.size()) {
            return;
        }
        int selectionStart = Math.min(accessor.koil$getSelectionStart(), accessor.koil$getSelectionEnd());
        int selectionEnd = Math.max(accessor.koil$getSelectionStart(), accessor.koil$getSelectionEnd());
        if (selectionStart == selectionEnd) {
            return;
        }
        int lineGlobalStart = koil$lineGlobalStart(lines, lineIndex);
        int lineGlobalEnd = lineGlobalStart + lines.get(lineIndex).length();
        int selectedStart = Math.max(selectionStart, lineGlobalStart);
        int selectedEnd = Math.min(selectionEnd, lineGlobalEnd);
        if (selectedStart >= selectedEnd) {
            return;
        }
        String line = lines.get(lineIndex);
        int startOffset = Math.max(0, Math.min(line.length(), selectedStart - lineGlobalStart));
        int endOffset = Math.max(0, Math.min(line.length(), selectedEnd - lineGlobalStart));
        int selectionX1 = textX + renderer.getWidth(line.substring(0, startOffset));
        int selectionX2 = textX + renderer.getWidth(line.substring(0, endOffset));
        context.fill(selectionX1, lineY - 1, selectionX2, lineY + renderer.fontHeight + 1, 0x804A8FD6);
    }

    @Unique
    private int koil$selectionStart() {
        return chatField instanceof TextFieldWidgetAccessor accessor ? accessor.koil$getSelectionStart() : chatField.getCursor();
    }

    @Unique
    private int koil$selectionEnd() {
        return chatField instanceof TextFieldWidgetAccessor accessor ? accessor.koil$getSelectionEnd() : chatField.getCursor();
    }

    @Unique
    private void koil$selectDraftWord(int index) {
        String text = chatField.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        int probe = Math.max(0, Math.min(text.length() - 1, index == text.length() ? index - 1 : index));
        int start = probe;
        int end = probe + 1;
        int mode = koil$draftCharMode(text.charAt(probe));
        while (start > 0 && koil$draftCharMode(text.charAt(start - 1)) == mode) {
            start--;
        }
        while (end < text.length() && koil$draftCharMode(text.charAt(end)) == mode) {
            end++;
        }
        chatField.setCursor(end);
        chatField.setSelectionStart(end);
        chatField.setSelectionEnd(start);
    }

    @Unique
    private void koil$selectDraftLine(int index) {
        String text = chatField.getText();
        if (text == null) {
            return;
        }
        int clamped = Math.max(0, Math.min(text.length(), index));
        int start = text.lastIndexOf('\n', Math.max(0, clamped - 1));
        start = start < 0 ? 0 : start + 1;
        int end = text.indexOf('\n', clamped);
        end = end < 0 ? text.length() : end;
        chatField.setCursor(end);
        chatField.setSelectionStart(end);
        chatField.setSelectionEnd(start);
    }

    @Unique
    private int koil$draftCharMode(char c) {
        if (c == '\n' || Character.isWhitespace(c)) {
            return 0;
        }
        if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '/' || c == '\\' || c == ':') {
            return 1;
        }
        return 2;
    }

    @Unique
    private int koil$lineGlobalStart(List<String> lines, int lineIndex) {
        int index = 0;
        for (int i = 0; i < lineIndex && i < lines.size(); i++) {
            index += lines.get(i).length() + 1;
        }
        return index;
    }

    @Unique
    private boolean koil$mouseInsideMultilineDraft(double mouseX, double mouseY) {
        int top = koil$draftTop();
        return mouseX >= 2 && mouseX <= this.width - 2 && mouseY >= top && mouseY <= this.height - 2;
    }

    @Unique
    private int koil$cursorFromMouse(double mouseX, double mouseY) {
        List<String> lines = koil$splitLines(chatField.getText());
        int lineHeight = this.textRenderer.fontHeight + 2;
        int visibleLines = koil$visibleLineCount(lines);
        int cursorLine = koil$cursorLine(lines, chatField.getCursor());
        int firstLine = koil$firstVisibleLine(lines, visibleLines, cursorLine);
        int top = koil$draftTop();
        int line = firstLine + Math.max(0, Math.min(visibleLines - 1, (int)((mouseY - top - 5) / lineHeight)));
        line = Math.max(0, Math.min(lines.size() - 1, line));
        int index = 0;
        for (int i = 0; i < line; i++) {
            index += lines.get(i).length() + 1;
        }

        String textLine = lines.get(line);
        int relativeX = Math.max(0, (int)mouseX - 6);
        int column = this.textRenderer.trimToWidth(textLine, relativeX).length();
        return Math.min(chatField.getText().length(), index + Math.min(column, textLine.length()));
    }

    @Unique
    private int koil$draftTop() {
        int lineHeight = this.textRenderer.fontHeight + 2;
        int visibleLines = koil$visibleLineCount(koil$splitLines(chatField.getText()));
        return this.height - 2 - visibleLines * lineHeight - 8;
    }

    @Unique
    private void koil$updateMultilineLayoutReservation() {
        if (!koil$isMultilineDraft()) {
            MultilineChatInputLayout.clear();
            return;
        }

        int lineHeight = this.textRenderer.fontHeight + 2;
        int visibleLines = koil$visibleLineCount(koil$splitLines(chatField.getText()));
        int draftHeight = visibleLines * lineHeight + 8;
        MultilineChatInputLayout.setReservedHeight(Math.max(0, draftHeight - 12));
    }

    @Unique
    private int koil$visibleLineCount(List<String> lines) {
        int lineHeight = this.textRenderer.fontHeight + 2;
        int maxVisibleLines = Math.max(2, Math.min(6, (this.height - 34) / lineHeight));
        return Math.min(maxVisibleLines, Math.max(2, lines.size()));
    }

    @Unique
    private int koil$firstVisibleLine(List<String> lines, int visibleLines, int cursorLine) {
        int maxFirst = Math.max(0, lines.size() - visibleLines);
        koil$draftScrollLine = Math.max(0, Math.min(maxFirst, koil$draftScrollLine));
        if (cursorLine < koil$draftScrollLine) {
            koil$draftScrollLine = cursorLine;
        } else if (cursorLine >= koil$draftScrollLine + visibleLines) {
            koil$draftScrollLine = cursorLine - visibleLines + 1;
        }
        return Math.max(0, Math.min(maxFirst, koil$draftScrollLine));
    }

    public void filesDragged(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        paths.stream().filter(Objects::nonNull).filter(Files::isRegularFile).findFirst().ifPresent(RichChatUploadDraft::stage);
    }

    @Unique
    private void koil$openRichChatFileChooser() {
        String chosen = TinyFileDialogs.tinyfd_openFileDialog("Attach File To Chat", Path.of(".").toAbsolutePath().normalize().toString(), null, null, false);
        if (chosen == null || chosen.isBlank()) {
            return;
        }
        RichChatUploadDraft.stage(Path.of(chosen));
    }

    @Unique
    private Path koil$pathFromClipboard(String clipboard) {
        if (clipboard == null || clipboard.isBlank() || clipboard.indexOf('\n') >= 0 || clipboard.indexOf('\r') >= 0) {
            return null;
        }
        String clean = clipboard.trim();
        if ((clean.startsWith("\"") && clean.endsWith("\"")) || (clean.startsWith("'") && clean.endsWith("'"))) {
            clean = clean.substring(1, clean.length() - 1);
        }
        if (clean.regionMatches(true, 0, "file://", 0, "file://".length())) {
            try {
                Path path = Path.of(URI.create(clean)).toAbsolutePath().normalize();
                return Files.isRegularFile(path) ? path : null;
            } catch (Exception ignored) {
                clean = clean.substring("file://".length());
            }
        }
        try {
            Path path = Path.of(clean).toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception ignored) {
        }
        try {
            Path path = Path.of(clean.replace("%20", " ")).toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception ignored) {
        }
        return Arrays.stream(clean.split("\\s+"))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .map(part -> {
                    try {
                        Path path = Path.of(part).toAbsolutePath().normalize();
                        return Files.isRegularFile(path) ? path : null;
                    } catch (Exception ignored) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    @Unique
    private boolean koil$isClipboardImageShortcut(int keyCode, int modifiers) {
        return keyCode == GLFW.GLFW_KEY_P && ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 || (modifiers & GLFW.GLFW_MOD_SUPER) != 0);
    }

    @Unique
    private boolean koil$stageClipboardImage() {
        try {
            Transferable transferable = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (transferable == null) {
                return false;
            }
            if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                Object data = transferable.getTransferData(DataFlavor.imageFlavor);
                if (data instanceof Image image) {
                    BufferedImage buffered;
                    if (image instanceof BufferedImage bufferedImage) {
                        buffered = bufferedImage;
                    } else {
                        int width = Math.max(1, image.getWidth(null));
                        int height = Math.max(1, image.getHeight(null));
                        buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D graphics = buffered.createGraphics();
                        graphics.drawImage(image, 0, 0, null);
                        graphics.dispose();
                    }
                    Path directory = Path.of("koil", "cache", "chat_media", "clipboard");
                    Files.createDirectories(directory);
                    Path path = directory.resolve("clipboard-" + System.currentTimeMillis() + ".png");
                    ImageIO.write(buffered, "png", path.toFile());
                    RichChatUploadDraft.stage(path);
                    return true;
                }
            }
            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                Object data = transferable.getTransferData(DataFlavor.javaFileListFlavor);
                if (data instanceof List<?> files) {
                    for (Object entry : files) {
                        if (entry instanceof File file && file.isFile()) {
                            RichChatUploadDraft.stage(file.toPath());
                            return true;
                        }
                    }
                }
            }
            String remoteUrl = koil$clipboardRemoteUrl(transferable);
            if (!remoteUrl.isBlank() && RichChatRemoteImageCache.likelyPreviewableUrl(remoteUrl)) {
                koil$stageClipboardRemoteUrl(remoteUrl);
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @Unique
    private String koil$clipboardRemoteUrl(Transferable transferable) {
        if (transferable == null) {
            return "";
        }
        try {
            if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                Object data = transferable.getTransferData(DataFlavor.stringFlavor);
                if (data instanceof String text) {
                    String fromHtml = koil$extractHtmlImageUrl(text);
                    if (!fromHtml.isBlank()) {
                        return fromHtml;
                    }
                    Matcher matcher = KOIL_CLIPBOARD_URL.matcher(text);
                    if (matcher.find()) {
                        return RichChatRemoteImageCache.sanitizeRemoteUrl(matcher.group());
                    }
                }
            }
            for (DataFlavor flavor : transferable.getTransferDataFlavors()) {
                String mime = flavor == null ? "" : flavor.getMimeType().toLowerCase();
                if (!mime.contains("html") && !mime.contains("uri-list")) {
                    continue;
                }
                Object data = transferable.getTransferData(flavor);
                if (data instanceof String text) {
                    String fromHtml = koil$extractHtmlImageUrl(text);
                    if (!fromHtml.isBlank()) {
                        return fromHtml;
                    }
                    Matcher matcher = KOIL_CLIPBOARD_URL.matcher(text);
                    if (matcher.find()) {
                        return RichChatRemoteImageCache.sanitizeRemoteUrl(matcher.group());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    @Unique
    private String koil$extractHtmlImageUrl(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher htmlMatcher = KOIL_CLIPBOARD_IMAGE_HTML.matcher(text);
        if (htmlMatcher.find()) {
            return RichChatRemoteImageCache.sanitizeRemoteUrl(htmlMatcher.group(1));
        }
        Matcher urlMatcher = KOIL_CLIPBOARD_URL.matcher(text);
        if (urlMatcher.find()) {
            return RichChatRemoteImageCache.sanitizeRemoteUrl(urlMatcher.group());
        }
        return "";
    }

    @Unique
    private void koil$stageClipboardRemoteUrl(String url) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(Text.literal("Fetching clipboard image..."));
        }
        CompletableFuture.runAsync(() -> {
            Path path = RichChatRemoteImageCache.downloadClipboardImage(url).orElse(null);
            MinecraftClient minecraft = MinecraftClient.getInstance();
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                if (path != null) {
                    RichChatUploadDraft.stage(path);
                } else if (minecraft.inGameHud != null) {
                    minecraft.inGameHud.getChatHud().addMessage(Text.literal("Clipboard image download failed."));
                }
            });
        });
    }

    @Unique
    private void koil$renderUploadButton(DrawContext context, int mouseX, int mouseY) {
        int left = 2;
        int top = Math.max(2, koil$inputPanelTop() - 16);
        int width = 46;
        int height = 13;
        int right = left + width;
        int bottom = top + height;
        int background = MinecraftClient.getInstance().options.getTextBackgroundColor(Integer.MIN_VALUE);
        context.fill(left, top, right, bottom, background);
        if (koil$mouseInsideUploadButton(mouseX, mouseY)) {
            context.fill(left, top, right, bottom, 0x224A5E74);
        }
        String label = "Upload";
        context.drawTextWithShadow(this.textRenderer, Text.literal(label), left + (width - this.textRenderer.getWidth(label)) / 2, top + 3, 0xE0E0E0);
        if (koil$mouseInsideUploadButton(mouseX, mouseY)) {
            context.drawTooltip(this.textRenderer, List.of(Text.literal("Attach a file to this chat message.")), mouseX, mouseY);
        }
    }

    @Unique
    private boolean koil$mouseInsideUploadButton(double mouseX, double mouseY) {
        if (!koil$showChatControls()) {
            return false;
        }
        int left = 2;
        int top = Math.max(2, koil$inputPanelTop() - 16);
        return mouseX >= left && mouseX <= left + 46 && mouseY >= top && mouseY <= top + 13;
    }

    @Unique
    private void koil$renderPrivateMessageButton(DrawContext context, int mouseX, int mouseY) {
        int left = 2 + 46 + 4;
        int top = Math.max(2, koil$inputPanelTop() - 16);
        int width = 36;
        int height = 13;
        int right = left + width;
        int bottom = top + height;
        int background = MinecraftClient.getInstance().options.getTextBackgroundColor(Integer.MIN_VALUE);
        context.fill(left, top, right, bottom, background);
        if (koil$mouseInsidePrivateMessageButton(mouseX, mouseY)) {
            context.fill(left, top, right, bottom, 0x224A5E74);
        }
        if (RichChatPrivateMessageBridge.filterEnabled()) {
            context.fill(left, top, right, bottom, 0x224A785D);
        }
        String label = "/msg";
        context.drawTextWithShadow(this.textRenderer, Text.literal(label), left + (width - this.textRenderer.getWidth(label)) / 2, top + 3, 0xE0E0E0);
        if (koil$mouseInsidePrivateMessageButton(mouseX, mouseY)) {
            context.drawTooltip(this.textRenderer, RichChatPrivateMessageBridge.buttonTooltip(), mouseX, mouseY);
        }
    }

    @Unique
    private boolean koil$mouseInsidePrivateMessageButton(double mouseX, double mouseY) {
        if (!koil$showChatControls()) {
            return false;
        }
        int left = 2 + 46 + 4;
        int top = Math.max(2, koil$inputPanelTop() - 16);
        return mouseX >= left && mouseX <= left + 36 && mouseY >= top && mouseY <= top + 13;
    }

    @Unique
    private void koil$renderUploadDraft(DrawContext context, int mouseX, int mouseY) {
        List<String> lines = koil$statusPopupLines();
        if (lines.isEmpty()) {
            return;
        }
        int buttonRight = 2 + 46 + 4 + 36;
        int left = buttonRight + 4;
        int right = Math.max(left + 96, this.width - 2);
        int bottom = Math.max(16, koil$inputPanelTop() - 3);
        int rowHeight = this.textRenderer.fontHeight + 1;
        int height = Math.max(13, 4 + lines.size() * rowHeight + 2);
        int top = bottom - height;
        int background = MinecraftClient.getInstance().options.getTextBackgroundColor(Integer.MIN_VALUE);
        context.fill(left, top, right, bottom, background);
        int accent = koil$statusPopupAccentColor();
        context.fill(left, top, right, bottom, accent);
        int textY = top + 3;
        for (String line : lines) {
            context.drawTextWithShadow(this.textRenderer, Text.literal(this.textRenderer.trimToWidth(line, Math.max(20, right - left - 24))), left + 5, textY, 0xE0E0E0);
            textY += rowHeight;
        }
        if (RichChatUploadDraft.hasPending()) {
            int removeX = right - 18;
            context.drawTextWithShadow(this.textRenderer, Text.literal("x"), removeX + 5, top + 3, 0xFFFF7777);
            if (mouseX >= removeX && mouseX <= right && mouseY >= top && mouseY <= bottom) {
                context.drawTooltip(this.textRenderer, List.of(Text.literal("Remove attachment")), mouseX, mouseY);
            } else if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom) {
                List<Text> tooltip = new ArrayList<>();
                for (String line : lines) {
                    tooltip.add(Text.literal(line));
                }
                for (String line : RichChatUploadDraft.tooltipLines()) {
                    tooltip.add(Text.literal(line));
                }
                context.drawTooltip(this.textRenderer, tooltip, mouseX, mouseY);
            }
        } else if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom) {
            List<Text> tooltip = new ArrayList<>();
            for (String line : lines) {
                tooltip.add(Text.literal(line));
            }
            context.drawTooltip(this.textRenderer, tooltip, mouseX, mouseY);
        }
    }

    @Unique
    private boolean koil$mouseInsideUploadRemove(double mouseX, double mouseY) {
        int left = 2 + 46 + 4;
        int right = Math.max(left + 96, this.width - 2);
        int bottom = Math.max(16, koil$inputPanelTop() - 3);
        int top = bottom - 13;
        int removeX = right - 18;
        return mouseX >= removeX && mouseX <= right && mouseY >= top && mouseY <= bottom;
    }

    @Unique
    private int koil$inputPanelTop() {
        return koil$isMultilineDraft() ? koil$draftTop() : this.height - 16;
    }

    @Unique
    private boolean koil$showChatControls() {
        return true;
    }

    @Unique
    private record KoilChunkPart(String text, boolean prependSpace) {
    }

    @Unique
    private String koil$truncateDraft(String text) {
        if (text == null || text.length() <= KOIL_MAX_CHAT_DRAFT_CHARS) {
            return text == null ? "" : text;
        }
        return text.substring(0, KOIL_MAX_CHAT_DRAFT_CHARS);
    }

    @Unique
    private List<String> koil$splitFallbackChunks(String networkText, int maxChars) {
        List<String> chunks = new ArrayList<>();
        String remaining = networkText == null ? "" : networkText.trim();
        while (!remaining.isEmpty()) {
            if (remaining.length() <= maxChars) {
                chunks.add(remaining);
                break;
            }
            int split = koil$preferredChunkSplit(remaining, maxChars);
            String chunk = remaining.substring(0, Math.min(remaining.length(), Math.max(1, split))).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            remaining = remaining.substring(Math.min(remaining.length(), Math.max(1, split))).trim();
        }
        return chunks;
    }

    @Unique
    private List<KoilChunkPart> koil$splitFallbackChunkParts(String networkText, int maxChars) {
        List<KoilChunkPart> chunks = new ArrayList<>();
        String text = networkText == null ? "" : networkText.trim();
        if (text.isEmpty()) {
            return chunks;
        }
        int position = 0;
        boolean prependSpace = false;
        while (position < text.length()) {
            int remaining = text.length() - position;
            if (remaining <= maxChars) {
                String tail = text.substring(position);
                if (prependSpace) {
                    tail = tail.stripLeading();
                }
                if (!tail.isEmpty()) {
                    chunks.add(new KoilChunkPart(tail, prependSpace));
                }
                break;
            }
            int split = koil$preferredChunkSplit(text.substring(position), maxChars);
            int boundary = Math.min(text.length(), Math.max(position + 1, position + split));
            String rawChunk = text.substring(position, boundary);
            String chunk = rawChunk.stripTrailing();
            if (!chunk.isEmpty()) {
                chunks.add(new KoilChunkPart(prependSpace ? chunk.stripLeading() : chunk, prependSpace));
            }
            int nextPosition = boundary;
            while (nextPosition < text.length() && Character.isWhitespace(text.charAt(nextPosition))) {
                nextPosition++;
            }
            prependSpace = nextPosition > boundary;
            position = nextPosition;
        }
        return chunks;
    }

    @Unique
    private int koil$preferredChunkSplit(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int hardCap = Math.max(1, Math.min(text.length(), maxChars));
        String fitted = text.substring(0, hardCap);
        int paragraph = fitted.lastIndexOf(" | ");
        if (paragraph > maxChars / 3) {
            return paragraph + 3;
        }
        for (int i = fitted.length() - 1; i >= Math.max(1, fitted.length() / 2); i--) {
            char c = fitted.charAt(i - 1);
            if ((c == '.' || c == '!' || c == '?' || c == ';' || c == ':') && i < text.length() && Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        int whitespace = fitted.lastIndexOf(' ');
        if (whitespace > maxChars / 3) {
            return whitespace;
        }
        return hardCap;
    }

    @Unique
    private void koil$sendChunkedPublicMessage(String historyText, String networkText, boolean addToHistory, CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.player.networkHandler == null) {
            cir.setReturnValue(true);
            return;
        }
        if (addToHistory && minecraft.inGameHud != null) {
            minecraft.inGameHud.getChatHud().addToMessageHistory(historyText);
        }
        List<String> chunks = koil$splitFallbackChunks(networkText, KOIL_MAX_VANILLA_CHAT_CHARS);
        RichChatMessageData richMessage = koil$rememberRichChatMetadata(
                UUID.randomUUID(),
                historyText,
                networkText,
                "text_overflow",
                List.of(),
                RichChatScope.PUBLIC,
                koil$chunkMetadata(chunks, Map.of())
        );
        if (richMessage != null && RichChatSyncClientBridge.canSync()) {
            RichChatSyncClientBridge.send(richMessage);
        }
        if (minecraft.inGameHud != null) {
            String visiblePrefix = "<" + minecraft.player.getGameProfile().getName() + "> ";
            koil$addFormattedPreviewMessage(minecraft, koil$visibleLocalMultiline(historyText, visiblePrefix));
            LocalOverflowChatBridge.remember(visiblePrefix, chunks);
        }
        for (String chunk : chunks) {
            minecraft.player.networkHandler.sendChatMessage(chunk);
        }
        cir.setReturnValue(true);
    }

    @Unique
    private void koil$sendChunkedPrivateMessage(String historyText, String routedBody, String targetPlayer, CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.player.networkHandler == null) {
            cir.setReturnValue(true);
            return;
        }
        int bodyLimit = koil$privateBodyChunkLimit(targetPlayer);
        List<KoilChunkPart> chunkParts = koil$splitFallbackChunkParts(routedBody, bodyLimit);
        List<String> chunks = chunkParts.stream().map(KoilChunkPart::text).toList();
        long chunkGroupTimestamp = System.currentTimeMillis();
        List<String> sentChunks = new ArrayList<>(chunkParts.size());
        for (int i = 0; i < chunkParts.size(); i++) {
            KoilChunkPart part = chunkParts.get(i);
            sentChunks.add(RichChatPrivateChunkBridge.encode(chunkGroupTimestamp, i, chunkParts.size(), part.prependSpace(), part.text()));
        }
        RichChatMessageData richMessage = koil$rememberRichChatMetadata(
                UUID.randomUUID(),
                historyText,
                routedBody,
                "private_filter_overflow",
                List.of(),
                RichChatScope.PRIVATE,
                koil$chunkMetadata(chunks, sentChunks, Map.of("pm_target", targetPlayer, "chunk_group_ts", Long.toString(chunkGroupTimestamp)))
        );
        if (richMessage != null) {
            RichChatSyncedMessageBridge.remember(richMessage);
            if (RichChatSyncClientBridge.canSync()) {
                RichChatSyncClientBridge.send(richMessage);
            }
        }
        if (minecraft.inGameHud != null) {
            String visiblePrefix = "You whisper to " + targetPlayer + ": ";
            koil$addFormattedPreviewMessage(minecraft, koil$visibleLocalMultiline(historyText, visiblePrefix));
            LocalOverflowChatBridge.remember(visiblePrefix, sentChunks);
        }
        for (String chunk : sentChunks) {
            minecraft.player.networkHandler.sendChatCommand("msg " + targetPlayer + " " + chunk);
        }
        cir.setReturnValue(true);
    }

    @Unique
    private void koil$sendChunkedExecutePrivateMessage(String historyText, String routedBody, String executorName, String whisperCommand, String targetPlayer, CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.player.networkHandler == null) {
            cir.setReturnValue(true);
            return;
        }
        int bodyLimit = koil$executePrivateBodyChunkLimit(executorName, whisperCommand, targetPlayer);
        List<KoilChunkPart> chunkParts = koil$splitFallbackChunkParts(routedBody, bodyLimit);
        long chunkGroupTimestamp = System.currentTimeMillis();
        if (chunkParts.isEmpty()) {
            cir.setReturnValue(true);
            return;
        }
        for (int i = 0; i < chunkParts.size(); i++) {
            KoilChunkPart part = chunkParts.get(i);
            String encoded = RichChatPrivateChunkBridge.encode(chunkGroupTimestamp, i, chunkParts.size(), part.prependSpace(), part.text());
            minecraft.player.networkHandler.sendChatCommand("execute as " + executorName + " run " + whisperCommand + " " + targetPlayer + " " + encoded);
        }
        cir.setReturnValue(true);
    }

    @Unique
    private void koil$sendChunkedPrivateAttachment(UUID messageId, String rawText, String fallbackBody, String historyText, RichChatAttachment attachment, String targetPlayer, String phase, CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.player.networkHandler == null) {
            cir.setReturnValue(true);
            return;
        }
        int bodyLimit = koil$privateBodyChunkLimit(targetPlayer);
        List<KoilChunkPart> chunkParts = koil$splitFallbackChunkParts(fallbackBody, bodyLimit);
        List<String> chunks = chunkParts.stream().map(KoilChunkPart::text).toList();
        long chunkGroupTimestamp = System.currentTimeMillis();
        List<String> sentChunks = new ArrayList<>(chunkParts.size());
        for (int i = 0; i < chunkParts.size(); i++) {
            KoilChunkPart part = chunkParts.get(i);
            sentChunks.add(RichChatPrivateChunkBridge.encode(chunkGroupTimestamp, i, chunkParts.size(), part.prependSpace(), part.text()));
        }
        RichChatMessageData richMessage = koil$rememberRichChatMetadata(
                messageId,
                rawText,
                fallbackBody,
                phase,
                attachment == null ? List.of() : List.of(attachment),
                RichChatScope.PRIVATE,
                koil$chunkMetadata(chunks, sentChunks, Map.of("pm_target", targetPlayer, "chunk_group_ts", Long.toString(chunkGroupTimestamp)))
        );
        if (richMessage != null) {
            RichChatSyncedMessageBridge.remember(richMessage);
            if (RichChatSyncClientBridge.canSync()) {
                RichChatSyncClientBridge.send(richMessage);
            }
        }
        if (attachment != null && !sentChunks.isEmpty()) {
            LocalRichAttachmentBridge.remember(sentChunks.get(0), rawText, attachment);
        }
        if (minecraft.inGameHud != null) {
            String visiblePrefix = "You whisper to " + targetPlayer + ": ";
            koil$addFormattedPreviewMessage(minecraft, koil$visibleLocalMultiline(historyText, visiblePrefix));
            LocalOverflowChatBridge.remember(visiblePrefix, sentChunks);
        }
        for (String chunk : sentChunks) {
            minecraft.player.networkHandler.sendChatCommand("msg " + targetPlayer + " " + chunk);
        }
        RichChatUploadDraft.clear();
        cir.setReturnValue(true);
    }

    @Unique
    private int koil$privateBodyChunkLimit(String targetPlayer) {
        String target = targetPlayer == null ? "" : targetPlayer.trim();
        int overhead = "msg ".length() + target.length() + 1;
        return Math.max(24, KOIL_MAX_VANILLA_CHAT_CHARS - overhead);
    }

    @Unique
    private int koil$executePrivateBodyChunkLimit(String executorName, String whisperCommand, String targetPlayer) {
        String executor = executorName == null ? "" : executorName.trim();
        String command = whisperCommand == null || whisperCommand.isBlank() ? "msg" : whisperCommand.trim();
        String target = targetPlayer == null ? "" : targetPlayer.trim();
        int hiddenChunkOverhead = 48;
        int overhead = "execute as ".length() + executor.length() + " run ".length() + command.length() + 1 + target.length() + 1 + hiddenChunkOverhead;
        return Math.max(24, KOIL_MAX_VANILLA_CHAT_CHARS - overhead);
    }

    @Unique
    private Map<String, String> koil$chunkMetadata(List<String> chunks, Map<String, String> baseMetadata) {
        return koil$chunkMetadata(chunks, List.of(), baseMetadata);
    }

    @Unique
    private Map<String, String> koil$chunkMetadata(List<String> chunks, List<String> sentChunks, Map<String, String> baseMetadata) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (baseMetadata != null) {
            metadata.putAll(baseMetadata);
        }
        if (chunks == null || chunks.isEmpty()) {
            return metadata;
        }
        metadata.put("chunk_count", Integer.toString(chunks.size()));
        for (int i = 0; i < chunks.size(); i++) {
            metadata.put("chunk_" + i, chunks.get(i));
        }
        if (sentChunks != null && !sentChunks.isEmpty()) {
            metadata.put("sent_chunk_count", Integer.toString(sentChunks.size()));
            for (int i = 0; i < sentChunks.size(); i++) {
                metadata.put("sent_chunk_" + i, sentChunks.get(i));
            }
        }
        return metadata;
    }

    @Unique
    private String koil$visibleLocalMultiline(String rawText, String prefix) {
        String normalized = rawText == null ? "" : rawText.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        if (lines.length == 0) {
            return prefix;
        }
        String indent = LocalMultilineChatBridge.indentForPrefix(prefix);
        StringBuilder builder = new StringBuilder(prefix.length() + normalized.length() + indent.length() * Math.max(0, lines.length - 1));
        builder.append(prefix).append(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            builder.append('\n').append(indent).append(lines[i]);
        }
        return builder.toString();
    }

    @Unique
    private void koil$addFormattedPreviewMessage(MinecraftClient minecraft, String visibleText) {
        if (minecraft == null || minecraft.inGameHud == null || visibleText == null || visibleText.isBlank()) {
            return;
        }
        Text preview = RichChatPreviewFormatter.format(Text.literal(visibleText));
        minecraft.inGameHud.getChatHud().addMessage(preview == null ? Text.literal(visibleText) : preview);
    }

    @Unique
    private boolean koil$isSequentialMultilineCommand(String chatText) {
        if (chatText == null) {
            return false;
        }
        String normalized = chatText.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.indexOf('\n') < 0) {
            return false;
        }
        boolean foundCommand = false;
        for (String line : normalized.split("\n", -1)) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!trimmed.startsWith("/")) {
                return false;
            }
            foundCommand = true;
        }
        return foundCommand;
    }

    @Unique
    private void koil$sendSequentialMultilineCommands(String chatText, boolean addToHistory, CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.player.networkHandler == null) {
            cir.setReturnValue(true);
            return;
        }
        String normalized = koil$truncateDraft(chatText == null ? "" : chatText.replace("\r\n", "\n").replace('\r', '\n')).trim();
        if (normalized.isEmpty()) {
            cir.setReturnValue(true);
            return;
        }
        if (addToHistory && minecraft.inGameHud != null) {
            minecraft.inGameHud.getChatHud().addToMessageHistory(normalized);
        }
        for (String line : normalized.split("\n", -1)) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("/")) {
                minecraft.player.networkHandler.sendChatCommand(trimmed.substring(1));
            } else {
                minecraft.player.networkHandler.sendChatMessage(trimmed);
            }
        }
        cir.setReturnValue(true);
    }

    @Override
    public boolean koil$useCustomChatSuggestionAnchor() {
        return koil$isMultilineDraft();
    }

    @Override
    public int koil$chatSuggestionAnchorX(net.minecraft.client.util.math.Rect2i vanillaArea, int popupWidth, boolean commandSuggestion) {
        if (!koil$isMultilineDraft()) {
            return vanillaArea == null ? 2 : vanillaArea.getX();
        }
        List<String> lines = koil$splitLines(chatField.getText());
        String beforeCursor = koil$lineBeforeCursor(lines, chatField.getCursor());
        int tokenStart = 0;
        for (int i = beforeCursor.length() - 1; i >= 0; i--) {
            char c = beforeCursor.charAt(i);
            if (Character.isWhitespace(c)) {
                tokenStart = i + 1;
                break;
            }
        }
        if (commandSuggestion && beforeCursor.startsWith("/")) {
            tokenStart = 0;
        }
        return 6 + this.textRenderer.getWidth(beforeCursor.substring(0, Math.max(0, Math.min(tokenStart, beforeCursor.length()))));
    }

    @Override
    public int koil$chatSuggestionAnchorY(int popupHeight) {
        return Math.max(2, koil$draftTop() - popupHeight - 2);
    }

    @Unique
    private void koil$renderSingleLineDraftPreview(DrawContext context, TextFieldWidget field) {
        if (context == null || field == null || field.getText() == null || field.getText().isEmpty()) {
            return;
        }
        if (!(field instanceof TextFieldWidgetAccessor accessor)) {
            return;
        }
        String text = field.getText();
        boolean command = text.startsWith("/");
        boolean rich = RichChatAttachmentRenderer.containsLiveFormatting(text) || koil$looksLikeHeader(text);
        if (!command && !rich) {
            return;
        }
        int first = Math.max(0, Math.min(accessor.koil$getFirstCharacterIndex(), text.length()));
        String visible = this.textRenderer.trimToWidth(text.substring(first), field.getInnerWidth());
        int x = field.getX() + 4;
        int y = field.getY() + Math.max(1, (field.getHeight() - this.textRenderer.fontHeight) / 2);
        context.enableScissor(field.getX() + 1, field.getY() + 1, field.getX() + field.getWidth() - 1, field.getY() + field.getHeight() - 1);
        koil$renderDraftLine(context, visible, x, y, field.getInnerWidth());
        context.disableScissor();
    }

    @Unique
    private void koil$renderDraftLine(DrawContext context, String line, int x, int y, int maxWidth) {
        if (line == null || line.isEmpty()) {
            return;
        }
        List<KoilStyledChunk> chunks = line.startsWith("/") ? koil$commandPreviewChunks(line) : koil$formattedPreviewChunks(line);
        koil$drawStyledChunks(context, chunks, x, y, maxWidth);
    }

    @Unique
    private void koil$drawStyledChunks(DrawContext context, List<KoilStyledChunk> chunks, int x, int y, int maxWidth) {
        int cursor = x;
        int right = x + Math.max(8, maxWidth);
        for (KoilStyledChunk chunk : chunks) {
            if (chunk == null || chunk.text().isEmpty() || cursor >= right) {
                continue;
            }
            String visible = this.textRenderer.trimToWidth(chunk.text(), Math.max(1, right - cursor));
            if (visible.isEmpty()) {
                continue;
            }
            OrderedText ordered = Text.literal(visible).setStyle(chunk.style()).asOrderedText();
            cursor = RichChatAttachmentRenderer.renderOrDrawText(context, this.textRenderer, ordered, cursor, y, chunk.color());
        }
    }

    @Unique
    private List<KoilStyledChunk> koil$commandPreviewChunks(String line) {
        List<KoilStyledChunk> chunks = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            return chunks;
        }
        int index = 0;
        while (index < line.length()) {
            char c = line.charAt(index);
            if (Character.isWhitespace(c)) {
                int end = index + 1;
                while (end < line.length() && Character.isWhitespace(line.charAt(end))) {
                    end++;
                }
                chunks.add(new KoilStyledChunk(line.substring(index, end), Style.EMPTY, 0xFFE0E0E0));
                index = end;
                continue;
            }
            int end = index + 1;
            boolean quoted = c == '"' || c == '\'';
            if (quoted) {
                while (end < line.length() && line.charAt(end) != c) {
                    end++;
                }
                if (end < line.length()) {
                    end++;
                }
            } else {
                while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
                    end++;
                }
            }
            String token = line.substring(index, end);
            int color = 0xFFE0E0E0;
            Style style = Style.EMPTY;
            if (index == 0) {
                color = 0xFFD7C16D;
                style = style.withBold(true);
            } else if (token.startsWith("@")) {
                color = 0xFF8FD0E3;
            } else if (quoted) {
                color = 0xFFA9D98A;
            } else if (token.matches("-?\\d+(?:\\.\\d+)?")) {
                color = 0xFF8FC5FF;
            } else if (token.contains(":")) {
                color = 0xFFA9D98A;
            }
            chunks.add(new KoilStyledChunk(token, style, color));
            index = end;
        }
        return chunks;
    }

    @Unique
    private List<KoilStyledChunk> koil$formattedPreviewChunks(String line) {
        List<KoilStyledChunk> out = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            return out;
        }
        Style baseStyle = Style.EMPTY;
        if (koil$looksLikeHeader(line)) {
            int hashes = 0;
            while (hashes < line.length() && hashes < 6 && line.charAt(hashes) == '#') {
                hashes++;
            }
            out.add(new KoilStyledChunk(line.substring(0, Math.min(line.length(), hashes + 1)), Style.EMPTY, 0xFFA8B0BC));
            koil$collectDraftFormattedChunks(line.substring(Math.min(line.length(), hashes + 1)), baseStyle.withBold(true).withUnderline(true).withColor(Formatting.WHITE), out);
            return out;
        }
        koil$collectDraftFormattedChunks(line, baseStyle, out);
        return out;
    }

    @Unique
    private void koil$collectDraftFormattedChunks(String text, Style baseStyle, List<KoilStyledChunk> out) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int index = 0;
        while (index < text.length()) {
            KoilMarkerMatch match = koil$nextDraftMarker(text, index);
            if (match == null) {
                out.add(new KoilStyledChunk(text.substring(index), baseStyle, 0xFFE0E0E0));
                return;
            }
            if (match.start() > index) {
                out.add(new KoilStyledChunk(text.substring(index, match.start()), baseStyle, 0xFFE0E0E0));
            }
            int close = text.indexOf(match.marker(), match.start() + match.marker().length());
            if (close < 0) {
                out.add(new KoilStyledChunk(match.marker(), Style.EMPTY, 0xFFA8B0BC));
                index = match.start() + match.marker().length();
                continue;
            }
            String inner = text.substring(match.start() + match.marker().length(), close);
            out.add(new KoilStyledChunk(match.marker(), Style.EMPTY, 0xFFA8B0BC));
            if ("||".equals(match.marker())) {
                out.add(new KoilStyledChunk(inner, baseStyle.withObfuscated(true), 0xFFE0E0E0));
            } else {
                koil$collectDraftFormattedChunks(inner, koil$applyDraftStyle(baseStyle, match.marker()), out);
            }
            out.add(new KoilStyledChunk(match.marker(), Style.EMPTY, 0xFFA8B0BC));
            index = close + match.marker().length();
        }
    }

    @Unique
    private KoilMarkerMatch koil$nextDraftMarker(String text, int from) {
        String[] markers = {"***", "**", "__", "--", "||", "*"};
        KoilMarkerMatch best = null;
        for (String marker : markers) {
            int index = text.indexOf(marker, from);
            if (index < 0) {
                continue;
            }
            if (best == null || index < best.start() || (index == best.start() && marker.length() > best.marker().length())) {
                best = new KoilMarkerMatch(marker, index);
            }
        }
        return best;
    }

    @Unique
    private Style koil$applyDraftStyle(Style baseStyle, String marker) {
        return switch (marker) {
            case "***" -> baseStyle.withBold(true).withItalic(true);
            case "**" -> baseStyle.withBold(true);
            case "__" -> baseStyle.withUnderline(true);
            case "*" -> baseStyle.withItalic(true);
            case "--" -> baseStyle.withStrikethrough(true);
            default -> baseStyle;
        };
    }

    @Unique
    private boolean koil$looksLikeHeader(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int hashes = 0;
        while (hashes < text.length() && hashes < 6 && text.charAt(hashes) == '#') {
            hashes++;
        }
        return hashes > 0 && hashes < text.length() && text.charAt(hashes) == ' ';
    }

    @Unique
    private List<String> koil$statusPopupLines() {
        List<String> lines = new ArrayList<>();
        String upload = RichChatUploadDraft.statusText();
        if (!upload.isBlank()) {
            lines.add(upload);
        }
        String command = koil$commandPreviewStatus();
        if (!command.isBlank()) {
            lines.add(command);
        }
        String math = koil$mathPreviewStatus();
        if (!math.isBlank()) {
            lines.add(math);
        }
        return lines;
    }

    @Unique
    private int koil$statusPopupAccentColor() {
        String command = koil$commandPreviewStatus();
        if (command.startsWith("Will fail")) {
            return 0x44E06A6A;
        }
        if (command.startsWith("Command") || command.startsWith("Will run")) {
            return 0x446A92E0;
        }
        if (!koil$mathPreviewStatus().isBlank()) {
            return 0x447C6AE0;
        }
        return RichChatUploadDraft.isReady() ? 0x446BCB8F : RichChatUploadDraft.isProcessing() ? 0x44E0C15C : 0x44E06A6A;
    }

    @Unique
    private String koil$commandPreviewStatus() {
        String text = chatField == null ? "" : chatField.getText();
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("/")) {
            return "";
        }
        if (koil$isSequentialMultilineCommand(text)) {
            long commands = Arrays.stream(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1))
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && line.startsWith("/"))
                    .count();
            return "Will run " + commands + " commands in order";
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null || client.getNetworkHandler().getCommandDispatcher() == null) {
            return "Command preview unavailable";
        }
        try {
            String commandText = trimmed.substring(1);
            ParseResults<CommandSource> parse = client.getNetworkHandler().getCommandDispatcher().parse(commandText, client.getNetworkHandler().getCommandSource());
            if (!parse.getExceptions().isEmpty()) {
                CommandSyntaxException exception = parse.getExceptions().values().iterator().next();
                String message = exception == null || exception.getRawMessage() == null ? "unknown command error" : exception.getRawMessage().getString();
                return "Will fail: " + message;
            }
            if (parse.getReader().canRead()) {
                return "Will fail: unexpected text near \"" + parse.getReader().getRemaining() + "\"";
            }
            return "Command syntax looks valid";
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? "command parse error" : exception.getMessage().replace('\n', ' ').replace('\r', ' ').trim();
            return "Will fail: " + message;
        }
    }

    @Unique
    private String koil$mathPreviewStatus() {
        String text = chatField == null ? "" : chatField.getText();
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("/")) {
            return "";
        }
        Double value = koil$evaluateLatexExpression(trimmed);
        if (value != null) {
            return "[Latex Formula]: " + koil$formatNumber(value);
        }
        if (!koil$looksLikeMathExpression(trimmed)) {
            return "";
        }
        Double math = koil$evaluateExpression(trimmed);
        return math == null ? "" : "Math: " + koil$formatNumber(math);
    }

    @Unique
    private boolean koil$looksLikeMathExpression(String text) {
        return text != null && text.matches("[0-9+\\-*/^().,\\s]+");
    }

    @Unique
    private Double koil$evaluateLatexExpression(String text) {
        if (text == null || text.isBlank() || !text.contains("$")) {
            return null;
        }
        String formula = text.trim();
        if ((formula.startsWith("$$") && formula.endsWith("$$")) || (formula.startsWith("$") && formula.endsWith("$"))) {
            formula = formula.replace("$$", "").replace("$", "").trim();
        }
        formula = formula.replace("\\cdot", "*").replace("\\times", "*").replace("\\div", "/").replace("\\left", "").replace("\\right", "");
        while (formula.contains("\\frac{")) {
            int start = formula.indexOf("\\frac{");
            int mid = formula.indexOf("}{", start);
            int end = formula.indexOf("}", mid + 2);
            if (start < 0 || mid < 0 || end < 0) {
                break;
            }
            String numerator = formula.substring(start + 6, mid);
            String denominator = formula.substring(mid + 2, end);
            formula = formula.substring(0, start) + "((" + numerator + ")/(" + denominator + "))" + formula.substring(end + 1);
        }
        formula = formula.replace('{', '(').replace('}', ')');
        if (!koil$looksLikeMathExpression(formula)) {
            return null;
        }
        return koil$evaluateExpression(formula);
    }

    @Unique
    private Double koil$evaluateExpression(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new Object() {
                int index;

                double parseExpression() {
                    double value = parseTerm();
                    while (true) {
                        skipWhitespace();
                        if (match('+')) {
                            value += parseTerm();
                        } else if (match('-')) {
                            value -= parseTerm();
                        } else {
                            return value;
                        }
                    }
                }

                double parseTerm() {
                    double value = parsePower();
                    while (true) {
                        skipWhitespace();
                        if (match('*')) {
                            value *= parsePower();
                        } else if (match('/')) {
                            value /= parsePower();
                        } else {
                            return value;
                        }
                    }
                }

                double parsePower() {
                    double value = parseFactor();
                    skipWhitespace();
                    if (match('^')) {
                        value = Math.pow(value, parsePower());
                    }
                    return value;
                }

                double parseFactor() {
                    skipWhitespace();
                    if (match('+')) {
                        return parseFactor();
                    }
                    if (match('-')) {
                        return -parseFactor();
                    }
                    if (match('(')) {
                        double value = parseExpression();
                        match(')');
                        return value;
                    }
                    int start = index;
                    while (index < text.length() && (Character.isDigit(text.charAt(index)) || text.charAt(index) == '.')) {
                        index++;
                    }
                    if (start == index) {
                        throw new IllegalArgumentException("bad expression");
                    }
                    return Double.parseDouble(text.substring(start, index));
                }

                boolean match(char c) {
                    if (index < text.length() && text.charAt(index) == c) {
                        index++;
                        return true;
                    }
                    return false;
                }

                void skipWhitespace() {
                    while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                        index++;
                    }
                }
            }.parseExpression();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Unique
    private String koil$formatNumber(double value) {
        long rounded = Math.round(value);
        if (Math.abs(value - rounded) < 0.0000001D) {
            return Long.toString(rounded);
        }
        return String.format(java.util.Locale.ROOT, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    @Unique
    private record KoilStyledChunk(String text, Style style, int color) {
    }

    @Unique
    private record KoilMarkerMatch(String marker, int start) {
    }
}
