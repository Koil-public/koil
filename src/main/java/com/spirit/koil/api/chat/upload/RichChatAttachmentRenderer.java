package com.spirit.koil.api.chat.upload;

import com.spirit.koil.api.chat.RichChatAttachment;
import com.spirit.koil.api.chat.RichChatAttachmentType;
import com.spirit.koil.api.chat.RichChatSettings;
import com.spirit.koil.api.chat.ChatHudPanelRegistry;
import com.spirit.client.gui.InfoPopup;
import com.spirit.client.gui.PopupMenu;
import com.spirit.client.gui.ide.EditorSyntaxHighlighter;
import com.spirit.client.gui.ide.FIleIconHelper;
import com.spirit.client.gui.ide.FileExplorerScreen;
import com.spirit.client.gui.ide.TextFileViewSupport;
import com.spirit.koil.api.design.uiColorVal;
import com.spirit.koil.api.chat.RichChatCodeBlockBridge;
import com.spirit.koil.api.util.file.audio.AudioManager;
import com.spirit.koil.api.util.file.media.VisualPlaybackSession;
import com.spirit.koil.api.util.file.media.VisualPlaybackState;
import com.spirit.koil.api.util.file.media.VisualTransportControls;
import com.spirit.koil.api.util.file.media.ActiveVisualPlaybackRegistry;
import com.spirit.koil.api.util.file.media.image.AnimatedGifPlaybackSession;
import com.spirit.koil.api.util.file.media.image.ImageTexture;
import com.spirit.koil.api.util.file.media.image.ImageTextureService;
import com.spirit.koil.api.util.file.media.video.VideoPreviewSnapshot;
import com.spirit.koil.api.util.file.media.video.VideoService;
import com.spirit.koil.api.chat.RichChatPrivateMessageBridge;
import com.spirit.koil.api.chat.RichChatRenderContext;
import com.spirit.koil.api.chat.RichChatTimestampBridge;
import com.spirit.koil.api.chat.latex.RichChatLatexTextureCache;
import com.spirit.koil.api.chat.latex.RichChatLatexTextureRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.glfw.GLFW;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.awt.Color;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RichChatAttachmentRenderer {
    private static final int MAX_THUMB_WIDTH = 269;
    private static final int MAX_THUMB_HEIGHT = 148;
    private static final int VIDEO_MAX_THUMB_HEIGHT = 300;
    private static final int VIDEO_FOOTER_HEIGHT = 26;
    private static final int MIN_THUMB_WIDTH = 72;
    private static final int MIN_THUMB_HEIGHT = 40;
    private static final int MARKER_TIMESTAMP_CLEARANCE = 3;
    private static final int MEDIA_START_NUDGE = 1;
    private static final int CARD_TEXT_SECONDARY = 0xFFB8C4D2;
    private static final int MENU_BUTTON_SIZE = 14;
    private static final int TIMELINE_HEIGHT = 10;
    private static final int FILTERED_VISUAL_RESERVE_ROWS = 3;
    private static final int PINNED_FILE_Y_OFFSET = -10;
    private static final int AUDIO_MENU_Y_OFFSET = -2;
    private static final int EXTRA_RENDER_RIGHT = 20;
    private static final int EXTRA_RENDER_BOTTOM = 1;
    private static final int EXTRA_FILE_AUDIO_WIDTH = 18;
    private static final int CODE_BLOCK_PADDING = 4;
    private static final PopupMenu ACTION_MENU = new PopupMenu();
    private static final InfoPopup INFO_POPUP = new InfoPopup();
    private static final DateTimeFormatter INFO_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final Map<UUID, ImageTexture> THUMBNAILS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> FAILURES = new ConcurrentHashMap<>();
    private static final Map<String, MediaBounds> BOUNDS = new ConcurrentHashMap<>();
    private static final Map<ButtonKey, ButtonBounds> BUTTONS = new ConcurrentHashMap<>();
    private static final Map<String, CodeButtonBounds> CODE_BUTTONS = new ConcurrentHashMap<>();
    private static final Map<String, UsernameHover> USERNAME_HOVERS = new ConcurrentHashMap<>();
    private static final Map<String, HoverTooltip> HOVER_TOOLTIPS = new ConcurrentHashMap<>();
    private static final Map<String, SpoilerBounds> SPOILER_BOUNDS = new ConcurrentHashMap<>();
    private static final Map<String, LinkBounds> LINK_BOUNDS = new ConcurrentHashMap<>();
    private static final Map<UUID, VisualPlaybackSession> VIDEO_SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> VIDEO_SESSION_KEYS = new ConcurrentHashMap<>();
    private static final Map<UUID, VisualPlaybackSession> GIF_SESSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> GIF_SESSION_KEYS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> OCCURRENCE_DRAW_X = new ConcurrentHashMap<>();
    private static final Set<String> DRAWN_THIS_FRAME = ConcurrentHashMap.newKeySet();
    private static final Set<String> VISIBLE_SPOILERS = ConcurrentHashMap.newKeySet();
    private static final Set<String> REVEALED_SPOILERS = ConcurrentHashMap.newKeySet();
    private static FocusState focusState;
    private static RichChatAttachment pinnedAttachment;
    private static VisualPlaybackSession pinnedVisualSession;
    private static String pinnedVisualSessionKey;
    private static UUID pinnedVisualAttachmentId;
    private static RichChatAttachment actionMenuAttachment;
    private static String actionMenuCodeBlockId;
    private static SeekDrag seekDrag;
    private static UUID activeAudioAttachmentId;
    private static boolean seekMouseDownLastFrame;
    private static double frameMouseX;
    private static double frameMouseY;
    private static long lastVideoClickTime;
    private static UUID lastVideoClickAttachmentId;
    private static final Pattern MASKED_LINK = Pattern.compile("\\[([^\\]\\n]+)]\\(([^)\\n]+)\\)");

    private RichChatAttachmentRenderer() {
    }

    public static int reservedMarkerRows(RichChatAttachment attachment) {
        return reservedMarkerRows(attachment, 0);
    }

    public static int reservedMarkerRows(RichChatAttachment attachment, int leadingWidth) {
        if (attachment == null || attachment.type() == null) {
            return 15;
        }
        return switch (attachment.type()) {
            case AUDIO -> fixedReservedRows(42);
            // File content occupies two chat lines; the previous 34px reserve
            // left two additional empty lines below the background-free row.
            case FILE -> Math.max(2, fixedReservedRows(34) - 2);
            case IMAGE, GIF -> visualReservedRows(attachment, false, leadingWidth);
            case VIDEO -> visualReservedRows(attachment, true, leadingWidth);
            default -> 2;
        };
    }

    public static void beginFrame(double mouseX, double mouseY) {
        REVEALED_SPOILERS.retainAll(VISIBLE_SPOILERS);
        VISIBLE_SPOILERS.clear();
        DRAWN_THIS_FRAME.clear();
        OCCURRENCE_DRAW_X.clear();
        BOUNDS.clear();
        BUTTONS.clear();
        CODE_BUTTONS.clear();
        USERNAME_HOVERS.clear();
        HOVER_TOOLTIPS.clear();
        SPOILER_BOUNDS.clear();
        LINK_BOUNDS.clear();
        frameMouseX = mouseX;
        frameMouseY = mouseY;
    }

    public static boolean hasFocusedAttachment() {
        return focusState != null;
    }

    public static int pinnedPanelHeight(MinecraftClient client) {
        return pinnedPanelHeight(client, ChatHudPanelRegistry.panelWidth(client));
    }

    public static int pinnedPanelHeight(MinecraftClient client, int availableWidth) {
        RichChatAttachment attachment = pinnedAttachment;
        if (attachment == null || client == null || client.player == null || !attachmentTypeEnabled(attachment.type())) {
            return 0;
        }
        if (attachment.type() == RichChatAttachmentType.AUDIO) {
            return 45;
        }
        if (attachment.type() == RichChatAttachmentType.FILE) {
            return 24;
        }
        return pinnedVisualSize(client, attachment, availableWidth).height();
    }

    public static void renderPinnedPanel(DrawContext context, MinecraftClient client, int y) {
        renderPinnedPanel(context, client, 0, y, ChatHudPanelRegistry.panelWidth(client));
    }

    public static void renderPinnedPanel(DrawContext context, MinecraftClient client, int x, int y, int availableWidth) {
        RichChatAttachment attachment = pinnedAttachment;
        int height = pinnedPanelHeight(client, availableWidth);
        if (context == null || client == null || attachment == null || height <= 0) {
            return;
        }
        int width = pinnedPanelWidth(client, availableWidth);
        boolean chatOpen = client.currentScreen instanceof ChatScreen;
        int renderY = attachment.type() == RichChatAttachmentType.FILE && !chatOpen ? y + PINNED_FILE_Y_OFFSET : y;
        context.getMatrices().push();
        context.getMatrices().translate(x, 0.0F, 0.0F);
        try {
            int background = pinnedPanelBackground(client);
            context.fill(0, renderY, width, renderY + height, background);
            context.fill(0, renderY, 2, renderY + height, 0xFF6F8FBD);
            if (attachment.type() == RichChatAttachmentType.AUDIO) {
                renderPinnedAudio(context, client, attachment, width, renderY, height);
            } else if (attachment.type() == RichChatAttachmentType.FILE) {
                renderPinnedFile(context, client, attachment, width, renderY, height);
            } else {
                renderPinnedVisual(context, client, attachment, width, renderY, height);
            }
            int menuX = width - MENU_BUTTON_SIZE - 3;
            int menuY = renderY + 2;
            drawMediaMoreButton(context, client.textRenderer, menuX, menuY, actionMenuAttachment != null && sameAttachment(actionMenuAttachment, attachment), 230, MENU_BUTTON_SIZE);
            putButton(context, new ButtonKey("pinned:" + attachment.attachmentId(), ButtonAction.MENU), attachment, menuX, menuY, MENU_BUTTON_SIZE, MENU_BUTTON_SIZE);
        } finally {
            context.getMatrices().pop();
        }
    }

    private static void renderPinnedVisual(DrawContext context, MinecraftClient client, RichChatAttachment attachment, int panelWidth, int y, int panelHeight) {
        Identifier textureId = null;
        VisualPlaybackSession session = null;
        int sourceWidth = Math.max(1, attachment.width());
        int sourceHeight = Math.max(1, attachment.height());
        if (attachment.type() == RichChatAttachmentType.VIDEO) {
            session = pinnedVisualSession(attachment, Math.max(1, panelWidth), Math.max(1, panelHeight));
            if (session != null) {
                session.update(System.currentTimeMillis());
                textureId = session.currentFrameTexture();
                sourceWidth = Math.max(1, session.frameWidth());
                sourceHeight = Math.max(1, session.frameHeight());
            }
            if (textureId == null) {
                File file = attachmentFile(attachment);
                VideoPreviewSnapshot preview = file == null ? null : VideoService.requestPreview(file, panelWidth, panelHeight);
                ImageTexture thumbnail = preview == null ? null : preview.thumbnail();
                if (thumbnail != null) {
                    textureId = thumbnail.textureId();
                    sourceWidth = Math.max(1, thumbnail.width());
                    sourceHeight = Math.max(1, thumbnail.height());
                }
            }
        } else if (attachment.type() == RichChatAttachmentType.GIF) {
            session = pinnedVisualSession(attachment, Math.max(1, panelWidth), Math.max(1, panelHeight));
            if (session != null) {
                if (session.state() != VisualPlaybackState.PLAYING) {
                    session.play();
                }
                session.update(System.currentTimeMillis());
                textureId = session.currentFrameTexture();
                sourceWidth = Math.max(1, session.frameWidth());
                sourceHeight = Math.max(1, session.frameHeight());
            }
        }
        if (textureId == null) {
            ImageTexture texture = thumbnail(attachment.attachmentId(), attachment);
            if (texture != null) {
                textureId = texture.textureId();
                sourceWidth = Math.max(1, texture.width());
                sourceHeight = Math.max(1, texture.height());
            }
        }
        if (textureId == null) {
            context.drawTextWithShadow(client.textRenderer, Text.literal("[media loading]"), 7, y + Math.max(3, (panelHeight - client.textRenderer.fontHeight) / 2), 0xFFE0E0E0);
            return;
        }
        int availableWidth = Math.max(1, panelWidth - 2);
        float scale = Math.min(availableWidth / (float) sourceWidth, panelHeight / (float) sourceHeight);
        int drawWidth = Math.max(1, Math.min(availableWidth, Math.round(sourceWidth * scale)));
        int drawHeight = Math.max(1, Math.min(panelHeight, Math.round(sourceHeight * scale)));
        int drawX = Math.max(2, (panelWidth - drawWidth) / 2);
        int drawY = y + Math.max(0, (panelHeight - drawHeight) / 2);
        context.drawTexture(textureId, drawX, drawY, drawWidth, drawHeight, 0, 0, sourceWidth, sourceHeight, sourceWidth, sourceHeight);
        BOUNDS.put("pinned:" + attachment.attachmentId(), screenBounds(context, attachment.attachmentId(), attachment, drawX, drawY, drawWidth, drawHeight));
        if (attachment.type() == RichChatAttachmentType.VIDEO && session != null) {
            VisualTransportControls.RenderResult layout = VisualTransportControls.renderPreviewFooter(
                    context,
                    client.textRenderer,
                    session,
                    VisualTransportControls.koilPreviewFooterSpec(
                            drawX, drawY, drawWidth, drawHeight, false, true, false,
                            FileExplorerScreen.PLAY_BUTTON,
                            FileExplorerScreen.PAUSE_BUTTON,
                            FileExplorerScreen.STOP_BUTTON
                    )
            );
            String key = "pinned:" + attachment.attachmentId();
            if (layout.primaryBounds() != null) {
                putButton(context, new ButtonKey(key, ButtonAction.PINNED_VIDEO_PLAY_PAUSE), attachment,
                        layout.primaryBounds()[0], layout.primaryBounds()[1], layout.primaryBounds()[2], layout.primaryBounds()[3]);
            }
            if (layout.secondaryBounds() != null) {
                putButton(context, new ButtonKey(key, ButtonAction.PINNED_VIDEO_STOP), attachment,
                        layout.secondaryBounds()[0], layout.secondaryBounds()[1], layout.secondaryBounds()[2], layout.secondaryBounds()[3]);
            }
            if (layout.timelineBounds() != null) {
                putButton(context, new ButtonKey(key, ButtonAction.PINNED_VIDEO_SEEK), attachment,
                        layout.timelineBounds()[0], layout.timelineBounds()[1], layout.timelineBounds()[2], layout.timelineBounds()[3]);
            }
        }
    }

    private static void renderPinnedFile(DrawContext context, MinecraftClient client, RichChatAttachment attachment, int width, int y, int height) {
        Identifier icon = FIleIconHelper.resolve(attachment.fileName());
        drawTextureWithAlpha(context, icon, 6, y + 3, 0, 0, 16, 16, 16, 16, 255);
        int textWidth = Math.max(24, width - 48);
        context.drawTextWithShadow(client.textRenderer, Text.literal(trimWithEllipsis(client.textRenderer, attachment.fileName(), textWidth)), 26, y + 3, 0xFFF5F7FA);
        context.drawTextWithShadow(client.textRenderer, Text.literal(trimWithEllipsis(client.textRenderer, fileDescription(attachment), textWidth)), 26, y + 14, CARD_TEXT_SECONDARY);
    }

    private static void renderPinnedAudio(DrawContext context, MinecraftClient client, RichChatAttachment attachment, int width, int y, int height) {
        renderPinnedFile(context, client, attachment, width, y, height);
        File file = attachmentFile(attachment);
        boolean current = file != null && AudioManager.isCurrentAudioFile(file) && sameAttachmentId(activeAudioAttachmentId, attachment.attachmentId());
        boolean playing = current && AudioManager.isAudioPlaying();
        int playX = 26;
        int stopX = 46;
        int controlY = y + 23;
        drawIconButton(context, playX, controlY, playing ? FileExplorerScreen.PAUSE_BUTTON : FileExplorerScreen.PLAY_BUTTON);
        drawIconButton(context, stopX, controlY, FileExplorerScreen.STOP_BUTTON);
        putButton(context, new ButtonKey("pinned:" + attachment.attachmentId(), ButtonAction.PLAY_PAUSE), attachment, playX, controlY, 16, 16);
        putButton(context, new ButtonKey("pinned:" + attachment.attachmentId(), ButtonAction.STOP), attachment, stopX, controlY, 16, 16);
        String totalTime = current ? formatMicros(AudioManager.getPlaybackLengthMicros(file)) : "0:00";
        String currentTime = current ? formatMicros(AudioManager.getPlaybackPositionMicros(file)) : "0:00";
        int totalWidth = client.textRenderer.getWidth(totalTime);
        int barX = stopX + 20;
        int barY = controlY + 2;
        int barWidth = Math.max(56, width - barX - totalWidth - 6);
        float progress = current ? AudioManager.getPlaybackProgress(file) : 0.0F;
        int filled = Math.max(0, Math.min(barWidth, Math.round(barWidth * progress)));
        context.fill(barX, barY, barX + filled, barY + TIMELINE_HEIGHT, uiColorVal.uiColorIDEAudioTimestampBarFill);
        context.drawBorder(barX, barY, barWidth, TIMELINE_HEIGHT, uiColorVal.uiColorIDEAudioTimestampBarBorder);
        if (filled > 0) {
            context.fill(barX + filled - 1, barY - 1, barX + filled + 1, barY + TIMELINE_HEIGHT + 1, uiColorVal.uiColorIDEAudioTimestampBarLine);
        }
        VisualTransportControls.renderThumbTimestamp(context, client.textRenderer, currentTime, barX + filled, barX, barWidth,
                barY + TIMELINE_HEIGHT + 3, uiColorVal.uiColorIDEAudioTimestampText);
        context.drawText(client.textRenderer, totalTime, barX + barWidth + 4, barY + 1, uiColorVal.uiColorIDEAudioTimestampText, true);
        putButton(context, new ButtonKey("pinned:" + attachment.attachmentId(), ButtonAction.SEEK), attachment, barX, barY, barWidth, TIMELINE_HEIGHT);
    }

    private static PreviewSize pinnedVisualSize(MinecraftClient client, RichChatAttachment attachment, int availableWidth) {
        int width = pinnedPanelWidth(client, availableWidth);
        int sourceWidth = attachment == null ? width : Math.max(1, attachment.width());
        int sourceHeight = attachment == null ? 90 : Math.max(1, attachment.height());
        if (sourceWidth <= 1 || sourceHeight <= 1) {
            sourceWidth = 16;
            sourceHeight = 9;
        }
        float scale = Math.min(width / (float) sourceWidth, 148.0F / sourceHeight);
        return new PreviewSize(width, Math.max(MIN_THUMB_HEIGHT, Math.min(148, Math.round(sourceHeight * scale))));
    }

    public static int pinnedPanelWidth(MinecraftClient client, int availableWidth) {
        int chatWidth = client == null || client.inGameHud == null ? 0 : client.inGameHud.getChatHud().getWidth();
        int screenWidth = client == null || client.getWindow() == null ? 320 : client.getWindow().getScaledWidth();
        return availableWidth > 0
                ? Math.min(screenWidth, availableWidth)
                : Math.min(screenWidth, Math.max(190, chatWidth + 12));
    }

    private static int pinnedPanelBackground(MinecraftClient client) {
        double opacity = client == null ? 0.5D : Math.max(0.48D, client.options.getTextBackgroundOpacity().getValue());
        return (Math.max(0, Math.min(220, (int) (255.0D * opacity))) << 24) | 0x050607;
    }

    private static boolean sameAttachment(RichChatAttachment first, RichChatAttachment second) {
        return first != null && second != null && sameAttachmentId(first.attachmentId(), second.attachmentId());
    }

    private static boolean sameAttachmentId(UUID first, UUID second) {
        return first != null && first.equals(second);
    }

    public static int renderOrDrawText(DrawContext context, TextRenderer renderer, OrderedText orderedText, int x, int y, int color) {
        return renderOrDrawText(context, renderer, orderedText, x, y, color, false);
    }

    public static int renderPreviewOrDrawText(DrawContext context, TextRenderer renderer, OrderedText orderedText, int x, int y, int color) {
        return renderOrDrawText(context, renderer, orderedText, x, y, color, true);
    }

    private static int renderOrDrawText(DrawContext context, TextRenderer renderer, OrderedText orderedText, int x, int y, int color, boolean previewMode) {
        String text = RichChatLatexTextureRenderer.plainText(orderedText);
        String displayText = RichChatPrivateMessageBridge.displayText(text);
        RichChatPrivateMessageBridge.VisualStyle style = previewMode
                ? RichChatPrivateMessageBridge.VisualStyle.NORMAL
                : overrideStyleForSelectedPrivateAttachment(
                        RichChatPrivateMessageBridge.visualStyle(text),
                        displayText
                );
        if (style == RichChatPrivateMessageBridge.VisualStyle.HIDE) {
            return x;
        }
        // Continuation indentation and private-message metadata can surround
        // this invisible reserve token. It must never escape as a glyph.
        if (displayText.strip().length() == 1 && displayText.strip().charAt(0) == RichChatCodeBlockBridge.SPACER_MARKER) {
            return x;
        }
        int effectiveColor = style == RichChatPrivateMessageBridge.VisualStyle.DIM ? RichChatPrivateMessageBridge.dimColor(color) : color;
        int chatAlpha = lineAlpha(color);
        recordUsernameHover(context, renderer, displayText, x, y);
        RichChatCodeBlockBridge.Marker codeMarker = RichChatCodeBlockBridge.nextMarker(displayText, 0);
        if (codeMarker != null && codeMarker.start() == 0 && codeMarker.end() == displayText.length()) {
            return renderCodeBlockLine(context, renderer, codeMarker, x, y, style, chatAlpha);
        }
        boolean italicizeDimmed = style == RichChatPrivateMessageBridge.VisualStyle.DIM
                && RichChatPrivateMessageBridge.shouldItalicizeDimmedLine(text);
        String codePrefix = codeBlockPrefix(displayText);
        if (!codePrefix.isEmpty()) {
            String codeBody = displayText.substring(codePrefix.length());
            RichChatCodeBlockBridge.Marker prefixedCodeMarker = RichChatCodeBlockBridge.nextMarker(codeBody, 0);
            if (prefixedCodeMarker != null && prefixedCodeMarker.start() == 0 && prefixedCodeMarker.end() == codeBody.length()) {
                OrderedText prefixText = italicizeDimmed
                        ? Text.literal(codePrefix).formatted(Formatting.ITALIC).asOrderedText()
                        : Text.literal(codePrefix).asOrderedText();
                int blockX = RichChatLatexTextureRenderer.renderOrDrawText(context, renderer, prefixText, x, y, effectiveColor);
                int right = renderCodeBlockLine(context, renderer, prefixedCodeMarker, blockX, y, style, chatAlpha);
                RichChatTimestampBridge.render(context, renderer, text, x, y);
                return Math.max(blockX, right);
            }
        }
        OrderedText effectiveOrderedText;
        if (displayText.equals(text) && !italicizeDimmed) {
            effectiveOrderedText = orderedText;
        } else if (italicizeDimmed && !LocalRichAttachmentBridge.containsMarker(displayText)) {
            effectiveOrderedText = Text.literal(displayText).formatted(Formatting.ITALIC).asOrderedText();
        } else {
            effectiveOrderedText = Text.literal(displayText).asOrderedText();
        }
        if (!LocalRichAttachmentBridge.containsMarker(displayText) && containsRichFormatting(displayText)) {
            int right = renderFormattedText(context, renderer, displayText, x, y, effectiveColor, style);
            RichChatTimestampBridge.render(context, renderer, text, x, y);
            return right;
        }
        if (!LocalRichAttachmentBridge.containsMarker(displayText)) {
            int right = RichChatLatexTextureRenderer.renderOrDrawText(context, renderer, effectiveOrderedText, x, y, effectiveColor);
            RichChatTimestampBridge.render(context, renderer, text, x, y);
            return right;
        }

        int cursor = x;
        int maxRight = x;
        int index = 0;
        LocalRichAttachmentBridge.Marker marker;
        while ((marker = LocalRichAttachmentBridge.nextMarker(displayText, index)) != null) {
            if (marker.start() > index) {
                String before = stripInlineAttachmentMarkers(displayText.substring(index, marker.start()));
                if (!attachmentSeparator(before)) {
                    OrderedText beforeText = italicizeDimmed
                            ? Text.literal(before).formatted(Formatting.ITALIC).asOrderedText()
                            : Text.literal(before).asOrderedText();
                    cursor = RichChatLatexTextureRenderer.renderOrDrawText(context, renderer, beforeText, cursor, y, effectiveColor);
                    maxRight = Math.max(maxRight, cursor);
                } else {
                    cursor += renderer.getWidth(before);
                    maxRight = Math.max(maxRight, cursor);
                }
            }
            int renderedWidth = renderMarker(context, renderer, marker, x, cursor, y, effectiveColor, style, chatAlpha);
            cursor += renderedWidth;
            maxRight = Math.max(maxRight, cursor);
            index = marker.end();
        }
        if (index < displayText.length()) {
            String after = stripInlineAttachmentMarkers(displayText.substring(index));
            if (!attachmentSeparator(after)) {
                OrderedText afterText = italicizeDimmed
                        ? Text.literal(after).formatted(Formatting.ITALIC).asOrderedText()
                        : Text.literal(after).asOrderedText();
                cursor = RichChatLatexTextureRenderer.renderOrDrawText(context, renderer, afterText, cursor, y, effectiveColor);
                maxRight = Math.max(maxRight, cursor);
            }
        }
        RichChatTimestampBridge.render(context, renderer, text, x, y);
        return maxRight;
    }

    public static boolean containsLiveFormatting(String text) {
        return containsRichFormatting(text);
    }

    public static int renderLiveFormattedText(DrawContext context, TextRenderer renderer, String text, int x, int y, int color) {
        return renderFormattedText(context, renderer, text, x, y, color, RichChatPrivateMessageBridge.VisualStyle.NORMAL);
    }

    private static boolean attachmentSeparator(String text) {
        return text != null && stripInlineAttachmentMarkers(text).replace("\\n", "").trim().isEmpty();
    }

    private static String stripInlineAttachmentMarkers(String text) {
        if (text == null || text.isEmpty() || text.indexOf(LocalRichAttachmentBridge.MARKER_START) < 0) {
            return text == null ? "" : text;
        }
        StringBuilder builder = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            int start = text.indexOf(LocalRichAttachmentBridge.MARKER_START, index);
            if (start < 0) {
                builder.append(text, index, text.length());
                break;
            }
            int end = text.indexOf(LocalRichAttachmentBridge.MARKER_END, start + 1);
            if (end < 0) {
                builder.append(text, index, text.length());
                break;
            }
            builder.append(text, index, start);
            index = end + 1;
        }
        return builder.toString();
    }

    private static int renderFormattedText(DrawContext context, TextRenderer renderer, String text, int x, int y, int color, RichChatPrivateMessageBridge.VisualStyle style) {
        if (context == null || renderer == null || text == null || text.isEmpty()) {
            return x;
        }
        String visiblePrefix = detectVisibleTextPrefix(text);
        String content = visiblePrefix.isEmpty() ? text : text.substring(visiblePrefix.length());
        Style baseStyle = style == RichChatPrivateMessageBridge.VisualStyle.DIM ? Style.EMPTY.withItalic(true) : Style.EMPTY;
        int cursor = x;
        float contentScale = 1.0F;
        int contentYOffset = 0;
        if (!visiblePrefix.isEmpty()) {
            cursor = RichChatLatexTextureRenderer.renderOrDrawText(
                    context,
                    renderer,
                    Text.literal(visiblePrefix).setStyle(baseStyle).asOrderedText(),
                    cursor,
                    y,
                    color
            );
        }
        QuoteStyle quoteStyle = detectQuoteStyle(content);
        if (quoteStyle != null) {
            if (!quoteStyle.leadingWhitespace().isEmpty()) {
                cursor = RichChatLatexTextureRenderer.renderOrDrawText(context, renderer, Text.literal(quoteStyle.leadingWhitespace()).setStyle(baseStyle).asOrderedText(), cursor, y, color);
            }
            int quoteColor = withMultipliedAlpha(style == RichChatPrivateMessageBridge.VisualStyle.DIM ? 0xFF59616C : 0xFF7B8794, lineAlpha(color));
            context.fill(cursor, y - 1, cursor + 2, y + renderer.fontHeight, quoteColor);
            cursor += 5;
            content = quoteStyle.content();
        }
        SubtextStyle subtextStyle = detectSubtextStyle(content);
        if (subtextStyle != null) {
            content = subtextStyle.content();
            baseStyle = mergeStyles(baseStyle, Style.EMPTY.withColor(Formatting.DARK_GRAY));
            contentScale = 0.82F;
            contentYOffset = 1;
        }
        HeaderStyle headerStyle = detectHeaderStyle(content);
        content = headerStyle == null ? content : headerStyle.content();
        if (headerStyle != null) {
            if (!headerStyle.leadingWhitespace().isEmpty()) {
                cursor = RichChatLatexTextureRenderer.renderOrDrawText(
                        context,
                        renderer,
                        Text.literal(headerStyle.leadingWhitespace()).setStyle(baseStyle).asOrderedText(),
                        cursor,
                        y,
                        color
                );
            }
            baseStyle = mergeStyles(baseStyle, headerStyle.style());
            contentScale = headerStyle.scale();
            contentYOffset = headerStyle.yOffset();
        }
        int[] spoilerCounter = new int[]{0};
        java.util.List<RenderedSegment> segments = new java.util.ArrayList<>();
        collectFormattedSegments(content, baseStyle, firstLineKey(text), spoilerCounter, segments);
        for (RenderedSegment segment : segments) {
            cursor = drawFormattedSegment(context, renderer, segment, x, y + contentYOffset, cursor, color, contentScale);
        }
        return cursor;
    }

    private static int drawFormattedSegment(DrawContext context, TextRenderer renderer, RenderedSegment segment, int lineX, int y, int cursorX, int color, float scale) {
        if (segment == null || segment.text() == null || segment.text().isEmpty()) {
            return cursorX;
        }
        OrderedText orderedText = Text.literal(segment.text()).setStyle(segment.style()).asOrderedText();
        int segmentColor = color;
        if (RichChatSettings.chatColorsEnabled() && segment.style() != null && segment.style().getColor() != null) {
            segmentColor = ((color >>> 24) << 24) | (segment.style().getColor().getRgb() & 0x00FFFFFF);
        }
        int width = Math.max(1, Math.round(renderer.getWidth(segment.text()) * Math.max(0.01F, scale)));
        int height = Math.max(1, Math.round((renderer.fontHeight + 2) * Math.max(0.01F, scale)));
        if (segment.inlineCode()) {
            // Keep the inline card inside the current text row; the old extra
            // lower padding painted into the following chat message.
            int cardY = y - 1;
            context.fill(cursorX - 1, cardY, cursorX + width + 1, cardY + renderer.fontHeight, withMultipliedAlpha(0x9E1B2028, (segmentColor >>> 24) & 0xFF));
            context.drawBorder(cursorX - 1, cardY, width + 2, renderer.fontHeight, withMultipliedAlpha(0x9E333C49, (segmentColor >>> 24) & 0xFF));
        }
        int right;
        if (Math.abs(scale - 1.0F) < 0.01F) {
            right = RichChatLatexTextureRenderer.renderOrDrawText(context, renderer, orderedText, cursorX, y, segmentColor);
        } else {
            context.getMatrices().push();
            context.getMatrices().translate(cursorX, y, 0.0F);
            context.getMatrices().scale(scale, scale, 1.0F);
            int rawRight = RichChatLatexTextureRenderer.renderOrDrawText(context, renderer, orderedText, 0, 0, segmentColor);
            context.getMatrices().pop();
            right = cursorX + Math.max(1, Math.round(rawRight * scale));
        }
        if (segment.spoilerKey() != null && segment.obfuscated()) {
            rememberSpoilerBounds(context, renderer, segment.spoilerKey(), lineX, cursorX, y, segment.text(), scale);
        }
        if (segment.linkTarget() != null && !segment.linkTarget().isBlank()) {
            rememberLinkBounds(context, renderer, segment.linkTarget(), cursorX, y, width, height);
        }
        return right;
    }

    private static void collectFormattedSegments(String text, Style baseStyle, String lineKey, int[] spoilerCounter, java.util.List<RenderedSegment> out) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int index = 0;
        while (index < text.length()) {
            MarkerMatch match = nextFormattingMarker(text, index);
            if (match == null) {
                appendPlainAndLinks(text.substring(index), baseStyle, out);
                return;
            }
            if (match.start() > index) {
                appendPlainAndLinks(text.substring(index, match.start()), baseStyle, out);
            }
            int close = text.indexOf(match.marker(), match.start() + match.marker().length());
            if (close < 0) {
                out.add(new RenderedSegment(match.marker(), baseStyle, null, false, false, null));
                index = match.start() + match.marker().length();
                continue;
            }
            String inner = text.substring(match.start() + match.marker().length(), close);
            if (inner.isEmpty()) {
                out.add(new RenderedSegment(match.marker(), baseStyle, null, false, false, null));
                index = match.start() + match.marker().length();
                continue;
            }
            if ("||".equals(match.marker())) {
                String spoilerKey = lineKey + ":spoiler:" + spoilerCounter[0]++;
                VISIBLE_SPOILERS.add(spoilerKey);
                if (REVEALED_SPOILERS.contains(spoilerKey)) {
                    collectFormattedSegments(inner, baseStyle, lineKey, spoilerCounter, out);
                } else {
                    out.add(new RenderedSegment(inner, baseStyle.withObfuscated(true), spoilerKey, true, false, null));
                }
            } else if ("`".equals(match.marker())) {
                out.add(new RenderedSegment(inner, baseStyle.withColor(Formatting.GRAY), null, false, true, null));
            } else {
                collectFormattedSegments(inner, applyStyle(baseStyle, match.marker()), lineKey, spoilerCounter, out);
            }
            index = close + match.marker().length();
        }
    }

    private static void appendPlainAndLinks(String text, Style baseStyle, java.util.List<RenderedSegment> out) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Matcher matcher = MASKED_LINK.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                out.add(new RenderedSegment(text.substring(cursor, matcher.start()), baseStyle, null, false, false, null));
            }
            String label = matcher.group(1);
            String target = matcher.group(2).trim();
            Formatting linkColor = isCommandLink(target) ? Formatting.GOLD : Formatting.BLUE;
            out.add(new RenderedSegment(label, baseStyle.withUnderline(true).withColor(linkColor), null, false, false, target));
            cursor = matcher.end();
        }
        if (cursor < text.length()) {
            out.add(new RenderedSegment(text.substring(cursor), baseStyle, null, false, false, null));
        }
    }

    private static boolean isCommandLink(String target) {
        if (target == null) {
            return false;
        }
        String value = target.trim();
        return value.startsWith("/") || value.regionMatches(true, 0, "command:/", 0, "command:/".length());
    }

    private static void rememberSpoilerBounds(DrawContext context, TextRenderer renderer, String spoilerKey, int lineX, int x, int y, String rawText, float scale) {
        if (context == null || renderer == null || spoilerKey == null || rawText == null) {
            return;
        }
        int width = Math.max(1, Math.round(renderer.getWidth(rawText) * Math.max(0.01F, scale)));
        int height = Math.max(1, Math.round((renderer.fontHeight + 2) * Math.max(0.01F, scale)));
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        Vector4f topLeft = new Vector4f(x, y, 0.0F, 1.0F);
        Vector4f bottomRight = new Vector4f(x + width, y + height, 0.0F, 1.0F);
        topLeft.mul(matrix);
        bottomRight.mul(matrix);
        VISIBLE_SPOILERS.add(spoilerKey);
        SPOILER_BOUNDS.put(
                spoilerKey,
                new SpoilerBounds(
                        spoilerKey,
                        Math.round(Math.min(topLeft.x(), bottomRight.x())) - 4,
                        Math.round(Math.min(topLeft.y(), bottomRight.y())) - 2,
                        Math.max(1, Math.round(Math.abs(bottomRight.x() - topLeft.x()))) + 8,
                        Math.max(1, Math.round(Math.abs(bottomRight.y() - topLeft.y()))) + 4,
                        lineX
                )
        );
    }

    private static boolean containsRichFormatting(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String visiblePrefix = detectVisibleTextPrefix(text);
        String content = visiblePrefix.isEmpty() ? text : text.substring(visiblePrefix.length());
        if (content.contains("***") || content.contains("**") || content.contains("__") || content.contains("*") || content.contains("--") || content.contains("||") || content.contains("`") || MASKED_LINK.matcher(content).find()) {
            return true;
        }
        return detectHeaderStyle(content) != null || detectSubtextStyle(content) != null || detectQuoteStyle(content) != null;
    }

    private static HeaderStyle detectHeaderStyle(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int leadingWhitespace = 0;
        while (leadingWhitespace < text.length() && Character.isWhitespace(text.charAt(leadingWhitespace))) {
            leadingWhitespace++;
        }
        int hashes = 0;
        while (leadingWhitespace + hashes < text.length() && hashes < 6 && text.charAt(leadingWhitespace + hashes) == '#') {
            hashes++;
        }
        if (hashes <= 0 || leadingWhitespace + hashes >= text.length() || text.charAt(leadingWhitespace + hashes) != ' ') {
            return null;
        }
        Style style = Style.EMPTY.withBold(true).withUnderline(true).withColor(Formatting.WHITE);
        float scale = switch (hashes) {
            case 1 -> 2.0F;
            case 2 -> 1.66F;
            case 3 -> 1.33F;
            case 4 -> 1.20F;
            case 5 -> 1.10F;
            default -> 1.0F;
        };
        int yOffset = switch (hashes) {
            case 1, 2 -> 2;
            case 3, 4, 5, 6 -> 1;
            default -> 0;
        };
        return new HeaderStyle(text.substring(leadingWhitespace + hashes + 1), style, scale, yOffset, text.substring(0, leadingWhitespace));
    }

    private static SubtextStyle detectSubtextStyle(String text) {
        if (text == null || !text.startsWith("-# ")) {
            return null;
        }
        return new SubtextStyle(text.substring(3));
    }

    private static QuoteStyle detectQuoteStyle(String text) {
        if (text == null) {
            return null;
        }
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        String leading = text.substring(0, start);
        String body = text.substring(start);
        if (body.startsWith(">>>")) {
            return new QuoteStyle(leading, body.substring(3).stripLeading());
        }
        if (body.startsWith(">")) {
            return new QuoteStyle(leading, body.substring(1).stripLeading());
        }
        return null;
    }

    private static String detectVisibleTextPrefix(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.startsWith("<")) {
            int end = text.indexOf("> ");
            if (end > 1) {
                return text.substring(0, end + 2);
            }
        }
        return "";
    }

    private static Style mergeStyles(Style base, Style extra) {
        Style merged = base == null ? Style.EMPTY : base;
        if (extra == null) {
            return merged;
        }
        if (extra.getColor() != null) {
            merged = merged.withColor(extra.getColor());
        }
        if (Boolean.TRUE.equals(extra.isBold())) {
            merged = merged.withBold(true);
        }
        if (Boolean.TRUE.equals(extra.isItalic())) {
            merged = merged.withItalic(true);
        }
        if (Boolean.TRUE.equals(extra.isStrikethrough())) {
            merged = merged.withStrikethrough(true);
        }
        if (Boolean.TRUE.equals(extra.isUnderlined())) {
            merged = merged.withUnderline(true);
        }
        if (Boolean.TRUE.equals(extra.isObfuscated())) {
            merged = merged.withObfuscated(true);
        }
        return merged;
    }

    private static Style applyStyle(Style baseStyle, String marker) {
        return switch (marker) {
            case "***" -> baseStyle.withBold(true).withItalic(true);
            case "**" -> baseStyle.withBold(true);
            case "__" -> baseStyle.withUnderline(true);
            case "*" -> baseStyle.withItalic(true);
            case "--" -> baseStyle.withStrikethrough(true);
            default -> baseStyle;
        };
    }

    private static MarkerMatch nextFormattingMarker(String text, int from) {
        int best = Integer.MAX_VALUE;
        String chosen = null;
        for (String marker : new String[]{"***", "**", "__", "--", "||", "`", "*"}) {
            int at = text.indexOf(marker, from);
            if (at >= 0 && at < best) {
                best = at;
                chosen = marker;
            }
        }
        return chosen == null ? null : new MarkerMatch(best, chosen);
    }

    private static void rememberLinkBounds(DrawContext context, TextRenderer renderer, String target, int x, int y, int width, int height) {
        if (context == null || renderer == null || target == null || target.isBlank()) {
            return;
        }
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        Vector4f topLeft = new Vector4f(x, y, 0.0F, 1.0F);
        Vector4f bottomRight = new Vector4f(x + width, y + height, 0.0F, 1.0F);
        topLeft.mul(matrix);
        bottomRight.mul(matrix);
        String key = target + "@" + x + ":" + y;
        LINK_BOUNDS.put(key, new LinkBounds(target, Math.round(Math.min(topLeft.x(), bottomRight.x())), Math.round(Math.min(topLeft.y(), bottomRight.y())), Math.max(1, Math.round(Math.abs(bottomRight.x() - topLeft.x()))), Math.max(1, Math.round(Math.abs(bottomRight.y() - topLeft.y())))));
    }

    private static int anchoredMarkerX(TextRenderer renderer, int lineX, LocalRichAttachmentBridge.Marker marker, RichChatAttachment attachment, int fallbackX) {
        if (marker == null || marker.occurrenceId() == null || marker.occurrenceId().isBlank()) {
            return fallbackX + MEDIA_START_NUDGE;
        }
        String prefix = LocalRichAttachmentBridge.prefix(marker);
        int alignedX = bodyAlignedMarkerX(lineX, renderer == null ? 0 : renderer.getWidth(prefix == null ? "" : prefix)) + MEDIA_START_NUDGE;
        alignedX = selectedPrivateAlignedX(renderer, lineX, attachment, alignedX);
        OCCURRENCE_DRAW_X.put(marker.occurrenceId(), alignedX);
        return alignedX;
    }

    private static int bodyAlignedMarkerX(int lineX, int prefixWidth) {
        return lineX + Math.max(0, prefixWidth);
    }

    private static int renderMarker(DrawContext context, TextRenderer renderer, LocalRichAttachmentBridge.Marker marker, int lineX, int x, int y, int color, RichChatPrivateMessageBridge.VisualStyle style, int chatAlpha) {
        RichChatAttachment attachment = LocalRichAttachmentBridge.attachment(marker).orElse(null);
        if (attachment == null) {
            String fallback = "[attachment]";
            context.drawTextWithShadow(renderer, fallback, x, y, color);
            return renderer.getWidth(fallback);
        }
        if (!attachmentTypeEnabled(attachment.type())) {
            if (marker.row() > 0) {
                return 0;
            }
            String fallback = "[" + attachment.type().name().toLowerCase(java.util.Locale.ROOT) + " hidden]";
            context.drawTextWithShadow(renderer, fallback, x, y, color);
            return renderer.getWidth(fallback);
        }
        if (attachment.type() == RichChatAttachmentType.AUDIO) {
            return renderAudioMarker(context, renderer, marker, attachment, lineX, x, y, color, style, chatAlpha);
        }
        if (attachment.type() == RichChatAttachmentType.VIDEO) {
            return renderVideoMarker(context, renderer, marker, attachment, lineX, x, y, color, style, chatAlpha);
        }
        if (!(attachment.type() == RichChatAttachmentType.IMAGE || attachment.type() == RichChatAttachmentType.GIF)) {
            return renderFileMarker(context, renderer, marker, attachment, lineX, x, y, color, style, chatAlpha);
        }

        boolean focusedAttachment = isFocusedAttachment(attachment);
        boolean activeTimedMedia = !privateTimedMediaInactive(attachment);
        if (!activeTimedMedia && attachment.attachmentId() != null) {
            closeGifSession(attachment.attachmentId());
        }
        VisualPlaybackSession gifSession = attachment.type() == RichChatAttachmentType.GIF && !focusedAttachment && activeTimedMedia ? gifSession(attachment, MAX_THUMB_WIDTH, MAX_THUMB_HEIGHT) : null;
        if (gifSession != null && gifSession.state() != VisualPlaybackState.PLAYING) {
            gifSession.play();
        }
        if (gifSession != null) {
            gifSession.update(System.currentTimeMillis());
        }
        Identifier animatedFrame = gifSession == null ? null : gifSession.currentFrameTexture();
        ImageTexture texture = thumbnail(marker.attachmentId(), attachment);
        if ((animatedFrame == null && (texture == null || texture.textureId() == null))) {
            if (marker.row() > 0) {
                return 0;
            }
            String fallback = FAILURES.containsKey(marker.attachmentId()) ? "[image unavailable]" : "[image loading]";
            context.drawTextWithShadow(renderer, fallback, x, y, color);
            return renderer.getWidth(fallback);
        }

        int lineHeight = Math.max(1, RichChatLatexTextureCache.currentChatLineHeight());
        int row = Math.max(0, marker.row());
        int sourceWidth = animatedFrame != null && gifSession != null ? Math.max(1, gifSession.frameWidth()) : Math.max(1, texture.width());
        int sourceHeight = animatedFrame != null && gifSession != null ? Math.max(1, gifSession.frameHeight()) : Math.max(1, texture.height());
        int drawX = anchoredMarkerX(renderer, lineX, marker, attachment, x);
        int chatWidth = remainingVisualChatWidth(lineX, drawX, attachment);
        PreviewSize size = previewSize(sourceWidth, sourceHeight, chatWidth);
        int drawY = y - row * lineHeight + markerTopOffset(marker) + markerVerticalAdjustment(attachment);
        String drawKey = marker.occurrenceId() + ":image";
        if (!DRAWN_THIS_FRAME.add(drawKey)) {
            return 0;
        }

        // remainingVisualChatWidth(...) is already measured from drawX. The
        // old lineX-based edge subtracted the sender/prefix width a second
        // time, clipping roughly one username's width from the media right.
        int chatRight = drawX + chatWidth;
        int visibleWidth = Math.min(size.width(), Math.max(0, chatRight - drawX));
        if (visibleWidth <= 0) {
            return 0;
        }

        int viewportTop = RichChatRenderContext.currentChatViewportTop();
        int viewportBottom = RichChatRenderContext.currentChatViewportBottom();
        int clippedTop = Math.max(drawY, viewportTop);
        int clippedBottom = Math.min(drawY + size.height(), viewportBottom + EXTRA_RENDER_BOTTOM);
        int visibleHeight = clippedBottom - clippedTop;
        if (visibleHeight <= 0) {
            return 0;
        }
        int sourceY = Math.max(0, Math.round((clippedTop - drawY) * (sourceHeight / (float)size.height())));
        int clippedSourceHeight = Math.min(sourceHeight - sourceY, Math.max(1, Math.round(visibleHeight * (sourceHeight / (float)size.height()))));
        int clippedSourceWidth = Math.min(sourceWidth, Math.max(1, Math.round(visibleWidth * (sourceWidth / (float)size.width()))));
        drawTextureWithAlpha(
                context,
                animatedFrame != null ? animatedFrame : texture.textureId(),
                drawX,
                clippedTop,
                visibleWidth,
                visibleHeight,
                0,
                sourceY,
                clippedSourceWidth,
                clippedSourceHeight,
                sourceWidth,
                sourceHeight,
                chatAlpha
        );
        if (style == RichChatPrivateMessageBridge.VisualStyle.DIM) {
            context.fill(drawX, clippedTop, drawX + visibleWidth, clippedTop + visibleHeight, withMultipliedAlpha(RichChatPrivateMessageBridge.dimOverlayColor(), chatAlpha));
        }
        putInlineBounds(context, marker.occurrenceId() + ":image", marker.attachmentId(), attachment, drawX, drawY, visibleWidth, size.height());
        return 0;
    }

    private static boolean attachmentTypeEnabled(RichChatAttachmentType type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case IMAGE -> RichChatSettings.imagesEnabled();
            case AUDIO -> RichChatSettings.audioEnabled();
            case VIDEO -> RichChatSettings.videoEnabled();
            case GIF -> RichChatSettings.gifsEnabled();
            default -> RichChatSettings.filesEnabled();
        };
    }

    private static void enableAttachmentType(RichChatAttachmentType type) {
        if (type == null) return;
        switch (type) {
            case IMAGE -> RichChatSettings.set(RichChatSettings.IMAGES, true);
            case AUDIO -> RichChatSettings.set(RichChatSettings.AUDIO, true);
            case VIDEO -> RichChatSettings.set(RichChatSettings.VIDEO, true);
            case GIF -> RichChatSettings.set(RichChatSettings.GIFS, true);
            default -> RichChatSettings.set(RichChatSettings.FILES, true);
        }
        RichChatSettings.set(RichChatSettings.MEDIA, true);
    }

    private static int renderFileMarker(DrawContext context, TextRenderer renderer, LocalRichAttachmentBridge.Marker marker, RichChatAttachment attachment, int lineX, int x, int y, int color, RichChatPrivateMessageBridge.VisualStyle style, int chatAlpha) {
        int row = Math.max(0, marker.row());
        int lineHeight = Math.max(1, RichChatLatexTextureCache.currentChatLineHeight());
        int chatWidth = Math.max(1, RichChatLatexTextureCache.currentChatContentWidth());
        int drawX = anchoredMarkerX(renderer, lineX, marker, attachment, x);
        int drawY = y - row * lineHeight + markerTopOffset(marker) + markerVerticalAdjustment(attachment);
        String drawKey = marker.occurrenceId() + ":file";
        if (!DRAWN_THIS_FRAME.add(drawKey)) {
            return 0;
        }
        int width = Math.min(338, Math.max(168, chatWidth + EXTRA_FILE_AUDIO_WIDTH - Math.max(0, drawX - lineX) - 2));
        int height = 34;
        int viewportTop = RichChatRenderContext.currentChatViewportTop();
        int viewportBottom = RichChatRenderContext.currentChatViewportBottom();
        int clippedTop = Math.max(drawY, viewportTop);
        int clippedBottom = Math.min(drawY + height, viewportBottom + EXTRA_RENDER_BOTTOM);
        if (clippedBottom <= clippedTop || width <= 0) {
            return 0;
        }

        enableTransformedScissor(context, drawX, clippedTop, drawX + width + EXTRA_RENDER_RIGHT, clippedBottom);
        Identifier icon = FIleIconHelper.resolve(attachment.fileName());
        int iconX = drawX;
        int iconY = drawY + 1;
        int menuX = drawX + width - MENU_BUTTON_SIZE - 4;
        int textX = iconX + 18;
        int textWidth = Math.max(36, menuX - textX - 4);
        drawTextureWithAlpha(context, icon, iconX, iconY, 0, 0, 16, 16, 16, 16, chatAlpha);
        boolean canView = canViewTextInEditor(attachment);
        int viewWidth = canView ? smallActionButtonWidth(renderer, "View") : 0;
        int viewX = canView ? menuX - viewWidth - 5 : menuX;
        int contentWidth = Math.max(36, viewX - textX - 4);
        String name = trimWithEllipsis(renderer, attachment.fileName(), contentWidth);
        String description = trimWithEllipsis(renderer, fileDescription(attachment), contentWidth);
        int primaryColor = style == RichChatPrivateMessageBridge.VisualStyle.DIM
                ? RichChatPrivateMessageBridge.dimColor(color)
                : color;
        context.drawTextWithShadow(renderer, Text.literal(name), textX, drawY + 1, primaryColor);
        rememberHoverTooltip(context, renderer, attachment.fileName(), name, textX, drawY + 1, contentWidth);
        int secondaryColor = withMultipliedAlpha(style == RichChatPrivateMessageBridge.VisualStyle.DIM ? RichChatPrivateMessageBridge.dimColor(CARD_TEXT_SECONDARY) : CARD_TEXT_SECONDARY, chatAlpha);
        context.drawTextWithShadow(renderer, Text.literal(description), textX, drawY + 10, secondaryColor);
        if (canView) {
            drawFileActionButton(context, renderer, viewX, drawY + 1, viewWidth, MENU_BUTTON_SIZE, "View", false, chatAlpha);
        }
        drawFileActionButton(context, renderer, menuX - 1, drawY + 1, MENU_BUTTON_SIZE, MENU_BUTTON_SIZE, "...", actionMenuAttachment != null && attachment.attachmentId().equals(actionMenuAttachment.attachmentId()), chatAlpha);
        context.disableScissor();

        if (canView) {
            putInlineButton(context, new ButtonKey(marker.occurrenceId(), ButtonAction.VIEW), attachment, viewX, drawY + 1, viewWidth, MENU_BUTTON_SIZE);
        }
        putInlineButton(context, new ButtonKey(marker.occurrenceId(), ButtonAction.MENU), attachment, menuX - 1, drawY + 1, MENU_BUTTON_SIZE, MENU_BUTTON_SIZE);
        return 0;
    }

    private static int renderAudioMarker(DrawContext context, TextRenderer renderer, LocalRichAttachmentBridge.Marker marker, RichChatAttachment attachment, int lineX, int x, int y, int color, RichChatPrivateMessageBridge.VisualStyle style, int chatAlpha) {
        boolean interactive = !privateTimedMediaInactive(attachment);
        if (!interactive && attachment != null && attachment.attachmentId() != null && attachment.attachmentId().equals(activeAudioAttachmentId)) {
            activeAudioAttachmentId = null;
            AudioManager.stopAllAudio();
        }
        int row = Math.max(0, marker.row());
        int lineHeight = Math.max(1, RichChatLatexTextureCache.currentChatLineHeight());
        int chatWidth = Math.max(1, RichChatLatexTextureCache.currentChatContentWidth() + EXTRA_FILE_AUDIO_WIDTH);
        int drawX = anchoredMarkerX(renderer, lineX, marker, attachment, x);
        int drawY = y - row * lineHeight + markerTopOffset(marker) + markerVerticalAdjustment(attachment);
        String drawKey = marker.occurrenceId() + ":audio";
        if (!DRAWN_THIS_FRAME.add(drawKey)) {
            return 0;
        }
        int width = Math.min(370, Math.max(232, chatWidth - Math.max(0, drawX - lineX) - 2));
        int height = 39;
        int viewportTop = RichChatRenderContext.currentChatViewportTop();
        int viewportBottom = RichChatRenderContext.currentChatViewportBottom();
        int clippedTop = Math.max(drawY, viewportTop);
        int clippedBottom = Math.min(drawY + height, viewportBottom + EXTRA_RENDER_BOTTOM);
        if (clippedBottom <= clippedTop || width <= 0) {
            return 0;
        }

        File file = attachmentFile(attachment);
        boolean current = file != null
                && AudioManager.isCurrentAudioFile(file)
                && attachment.attachmentId() != null
                && attachment.attachmentId().equals(activeAudioAttachmentId);
        boolean playing = current && AudioManager.isAudioPlaying();
        float progress = current ? AudioManager.getPlaybackProgress(file) : 0.0F;
        long position = current ? AudioManager.getPlaybackPositionMicros(file) : 0L;
        long length = current ? AudioManager.getPlaybackLengthMicros(file) : 0L;
        String totalTime = length > 0 ? formatMicros(length) : "--:--";
        int totalTimeWidth = renderer.getWidth(totalTime);
        int fillColor = withMultipliedAlpha(new Color(uiColorVal.uiColorIDEAudioTimestampBarFill, true).getRGB(), chatAlpha);
        int borderColor = withMultipliedAlpha(new Color(uiColorVal.uiColorIDEAudioTimestampBarBorder, true).getRGB(), chatAlpha);
        int lineColor = withMultipliedAlpha(new Color(uiColorVal.uiColorIDEAudioTimestampBarLine, true).getRGB(), chatAlpha);
        int textColor = withMultipliedAlpha(new Color(uiColorVal.uiColorIDEAudioTimestampText, true).getRGB(), chatAlpha);
        if (style == RichChatPrivateMessageBridge.VisualStyle.DIM) {
            fillColor = RichChatPrivateMessageBridge.dimColor(fillColor);
            borderColor = RichChatPrivateMessageBridge.dimColor(borderColor);
            lineColor = RichChatPrivateMessageBridge.dimColor(lineColor);
            textColor = RichChatPrivateMessageBridge.dimColor(textColor);
        }

        int audioMenuY = drawY + AUDIO_MENU_Y_OFFSET;
        int audioScissorTop = Math.max(viewportTop, Math.min(clippedTop, audioMenuY));
        enableTransformedScissor(context, drawX, audioScissorTop, drawX + width + EXTRA_RENDER_RIGHT, clippedBottom);
        Identifier icon = FIleIconHelper.resolve(attachment.fileName());
        int iconX = drawX;
        int iconY = drawY + 1;
        int menuX = drawX + width - MENU_BUTTON_SIZE;
        int textX = iconX + 18;
        int textWidth = Math.max(52, menuX - textX - 4);
        drawTextureWithAlpha(context, icon, iconX, iconY, 0, 0, 16, 16, 16, 16, chatAlpha);
        String name = trimWithEllipsis(renderer, attachment.fileName(), textWidth);
        String description = trimWithEllipsis(renderer, fileDescription(attachment), textWidth);
        int primaryColor = style == RichChatPrivateMessageBridge.VisualStyle.DIM
                ? RichChatPrivateMessageBridge.dimColor(color)
                : color;
        context.drawTextWithShadow(renderer, Text.literal(name), textX, drawY + 1, primaryColor);
        rememberHoverTooltip(context, renderer, attachment.fileName(), name, textX, drawY + 1, textWidth);
        int secondaryColor = withMultipliedAlpha(style == RichChatPrivateMessageBridge.VisualStyle.DIM ? RichChatPrivateMessageBridge.dimColor(CARD_TEXT_SECONDARY) : CARD_TEXT_SECONDARY, chatAlpha);
        context.drawTextWithShadow(renderer, Text.literal(description), textX, drawY + 10, secondaryColor);

        int controlY = drawY + 20;
        int playY = controlY - 1;
        int stopY = controlY - 1;
        int playX = textX;
        int stopX = playX + 18;
        int barX = stopX + 20;
        int barY = controlY + 2;
        int barWidth = Math.max(56, drawX + width - barX - totalTimeWidth - 6);
        int barHeight = TIMELINE_HEIGHT;
        int filled = Math.max(0, Math.min(barWidth, Math.round(barWidth * progress)));
        drawIconButton(context, playX, playY, playing ? FileExplorerScreen.PAUSE_BUTTON : FileExplorerScreen.PLAY_BUTTON, chatAlpha);
        drawIconButton(context, stopX, stopY, FileExplorerScreen.STOP_BUTTON, chatAlpha);
        context.fill(barX, barY, barX + filled, barY + barHeight, fillColor);
        context.drawBorder(barX, barY, barWidth, barHeight, borderColor);
        if (filled > 0) {
            context.fill(barX + filled - 1, barY - 1, barX + filled + 1, barY + barHeight + 1, lineColor);
        }
        String currentTime = formatMicros(position);
        VisualTransportControls.renderThumbTimestamp(context, renderer, currentTime, barX + filled, barX, barWidth, barY + 13, textColor);
        context.drawText(renderer, totalTime, barX + barWidth + 4, barY + 1, textColor, true);
        drawMediaMoreButton(context, renderer, menuX - 1, audioMenuY, actionMenuAttachment != null && attachment.attachmentId().equals(actionMenuAttachment.attachmentId()), chatAlpha, MENU_BUTTON_SIZE);
        context.disableScissor();

        if (interactive) {
            putInlineButton(context, new ButtonKey(marker.occurrenceId(), ButtonAction.PLAY_PAUSE), attachment, playX, playY, 16, 16);
            putInlineButton(context, new ButtonKey(marker.occurrenceId(), ButtonAction.STOP), attachment, stopX, stopY, 16, 16);
            putInlineButton(context, new ButtonKey(marker.occurrenceId(), ButtonAction.SEEK), attachment, barX, barY, barWidth, barHeight);
        }
        putInlineButton(context, new ButtonKey(marker.occurrenceId(), ButtonAction.MENU), attachment, menuX - 1, audioMenuY, MENU_BUTTON_SIZE, MENU_BUTTON_SIZE);
        return 0;
    }

    private static int renderVideoMarker(DrawContext context, TextRenderer renderer, LocalRichAttachmentBridge.Marker marker, RichChatAttachment attachment, int lineX, int x, int y, int color, RichChatPrivateMessageBridge.VisualStyle style, int chatAlpha) {
        int row = Math.max(0, marker.row());
        int lineHeight = Math.max(1, RichChatLatexTextureCache.currentChatLineHeight());
        int drawX = anchoredMarkerX(renderer, lineX, marker, attachment, x);
        int chatWidth = remainingVisualChatWidth(lineX, drawX, attachment);
        int drawY = y - row * lineHeight + markerTopOffset(marker) + markerVerticalAdjustment(attachment);
        String drawKey = marker.occurrenceId() + ":video";
        if (!DRAWN_THIS_FRAME.add(drawKey)) {
            return 0;
        }

        File file = attachmentFile(attachment);
        if (file == null || !file.isFile()) {
            if (marker.row() == 0) {
                String fallback = "[video unavailable]";
                context.drawTextWithShadow(renderer, fallback, x, y, color);
                return renderer.getWidth(fallback);
            }
            return 0;
        }

        VideoPreviewSnapshot preview = VideoService.requestPreview(file, MAX_THUMB_WIDTH, VIDEO_MAX_THUMB_HEIGHT);
        boolean focusedAttachment = isFocusedAttachment(attachment);
        boolean interactive = !privateTimedMediaInactive(attachment);
        if (!interactive && attachment.attachmentId() != null) {
            closeVideoSession(attachment.attachmentId());
        }
        VisualPlaybackSession session = focusedAttachment || !interactive ? null : videoSession(attachment, MAX_THUMB_WIDTH, VIDEO_MAX_THUMB_HEIGHT);
        if (session != null) {
            session.update(System.currentTimeMillis());
        }
        Identifier activeFrame = session == null ? null : session.currentFrameTexture();
        ImageTexture texture = preview == null ? null : preview.thumbnail();
        int metadataWidth = preview != null && preview.probe() != null && preview.probe().metadata() != null ? Math.max(0, preview.probe().metadata().width()) : 0;
        int metadataHeight = preview != null && preview.probe() != null && preview.probe().metadata() != null ? Math.max(0, preview.probe().metadata().height()) : 0;
        int sourceWidth = activeFrame != null && session != null ? Math.max(1, session.frameWidth()) : (texture == null ? Math.max(1, metadataWidth) : texture.width());
        int sourceHeight = activeFrame != null && session != null ? Math.max(1, session.frameHeight()) : (texture == null ? Math.max(1, metadataHeight) : texture.height());
        if ((activeFrame == null && (texture == null || texture.textureId() == null)) || sourceWidth <= 0 || sourceHeight <= 0) {
            if (marker.row() == 0) {
                String fallback = preview != null && !preview.loading() ? "[video unavailable]" : "[video loading]";
                context.drawTextWithShadow(renderer, fallback, x, y, color);
                return renderer.getWidth(fallback);
            }
            return 0;
        }

        PreviewSize mediaSize = previewSize(
                sourceWidth,
                sourceHeight,
                chatWidth,
                MAX_THUMB_WIDTH,
                VIDEO_MAX_THUMB_HEIGHT,
                MIN_THUMB_WIDTH,
                MIN_THUMB_HEIGHT
        );
        int mediaHeight = mediaSize.height();
        int renderHeight = mediaHeight;
        int viewportTop = RichChatRenderContext.currentChatViewportTop();
        int viewportBottom = RichChatRenderContext.currentChatViewportBottom();
        int renderClippedTop = Math.max(drawY, viewportTop);
        int renderClippedBottom = Math.min(drawY + renderHeight, viewportBottom + EXTRA_RENDER_BOTTOM);
        if (renderClippedBottom <= renderClippedTop) {
            return 0;
        }
        int mediaClippedTop = Math.max(drawY, viewportTop);
        int mediaClippedBottom = Math.min(drawY + mediaHeight, viewportBottom + EXTRA_RENDER_BOTTOM);
        int mediaVisibleHeight = mediaClippedBottom - mediaClippedTop;

        int chatRight = drawX + chatWidth;
        int visibleWidth = Math.min(mediaSize.width(), Math.max(0, chatRight - drawX));
        if (visibleWidth <= 0) {
            return 0;
        }
        enableTransformedScissor(context, drawX, renderClippedTop, drawX + visibleWidth, renderClippedBottom);

        if (mediaVisibleHeight > 0) {
            int sourceY = Math.max(0, Math.round((mediaClippedTop - drawY) * (sourceHeight / (float)mediaHeight)));
            int clippedSourceHeight = Math.min(sourceHeight - sourceY, Math.max(1, Math.round(mediaVisibleHeight * (sourceHeight / (float)mediaHeight))));
            int clippedSourceWidth = Math.min(sourceWidth, Math.max(1, Math.round(visibleWidth * (sourceWidth / (float)mediaSize.width()))));
            if (activeFrame != null) {
                drawTextureWithAlpha(context, activeFrame, drawX, mediaClippedTop, visibleWidth, mediaVisibleHeight, 0, sourceY, clippedSourceWidth, clippedSourceHeight, sourceWidth, sourceHeight, chatAlpha);
            } else {
                drawTextureWithAlpha(context, texture.textureId(), drawX, mediaClippedTop, visibleWidth, mediaVisibleHeight, 0, sourceY, clippedSourceWidth, clippedSourceHeight, sourceWidth, sourceHeight, chatAlpha);
            }
        }
        boolean hovered = frameMouseX >= drawX && frameMouseX <= drawX + visibleWidth && frameMouseY >= drawY && frameMouseY <= drawY + renderHeight;
        VisualTransportControls.RenderResult layout = session == null ? VisualTransportControls.RenderResult.empty() : VisualTransportControls.renderPreviewFooter(
                context,
                renderer,
                session,
                new VisualTransportControls.PreviewFooterSpec(
                        drawX,
                        drawY,
                        visibleWidth,
                        mediaHeight,
                        VIDEO_FOOTER_HEIGHT,
                        false,
                        false,
                        hovered,
                        true,
                        FileExplorerScreen.PLAY_BUTTON,
                        FileExplorerScreen.PAUSE_BUTTON,
                        FileExplorerScreen.STOP_BUTTON,
                        withMultipliedAlpha(uiColorVal.uiColorBackgroundBorder, chatAlpha),
                        withMultipliedAlpha(0xE0101319, chatAlpha),
                        withMultipliedAlpha(0xEE141923, chatAlpha),
                        withMultipliedAlpha(uiColorVal.uiColorIDEAudioTimestampBarFill, chatAlpha),
                        withMultipliedAlpha(uiColorVal.uiColorIDEAudioTimestampBarBorder, chatAlpha),
                        withMultipliedAlpha(uiColorVal.uiColorIDEAudioTimestampBarLine, chatAlpha),
                        withMultipliedAlpha(uiColorVal.uiColorIDEAudioTimestampText, chatAlpha)
                )
        );
        if (style == RichChatPrivateMessageBridge.VisualStyle.DIM) {
            context.fill(drawX, renderClippedTop, drawX + visibleWidth, renderClippedBottom, withMultipliedAlpha(RichChatPrivateMessageBridge.dimOverlayColor(), chatAlpha));
        }
        context.disableScissor();
        putInlineBounds(context, marker.occurrenceId() + ":video", marker.attachmentId(), attachment, drawX, drawY, visibleWidth, renderHeight);
        if (layout.primaryBounds() != null) {
            putInlineButton(context, new ButtonKey(marker.occurrenceId(), ButtonAction.PLAY_PAUSE), attachment, layout.primaryBounds()[0], layout.primaryBounds()[1], layout.primaryBounds()[2], layout.primaryBounds()[3]);
        }
        if (layout.secondaryBounds() != null) {
            putInlineButton(context, new ButtonKey(marker.occurrenceId(), ButtonAction.STOP), attachment, layout.secondaryBounds()[0], layout.secondaryBounds()[1], layout.secondaryBounds()[2], layout.secondaryBounds()[3]);
        }
        if (layout.timelineBounds() != null) {
            putInlineButton(context, new ButtonKey(marker.occurrenceId(), ButtonAction.SEEK), attachment, layout.timelineBounds()[0], layout.timelineBounds()[1], layout.timelineBounds()[2], layout.timelineBounds()[3]);
        }
        if (layout.overlayBounds() != null) {
            putInlineButton(context, new ButtonKey(marker.occurrenceId() + ":overlay", ButtonAction.PLAY_PAUSE), attachment, layout.overlayBounds()[0], layout.overlayBounds()[1], layout.overlayBounds()[2], layout.overlayBounds()[3]);
        }
        return 0;
    }

    private static int renderCodeBlockLine(DrawContext context, TextRenderer renderer, RichChatCodeBlockBridge.Marker marker, int lineX, int y, RichChatPrivateMessageBridge.VisualStyle style, int chatAlpha) {
        RichChatCodeBlockBridge.CodeBlock block = RichChatCodeBlockBridge.block(marker);
        if (context == null || renderer == null || marker == null || block == null || marker.row() < 0 || marker.row() >= block.displayLines().size()) {
            return lineX;
        }
        int drawX = lineX + 1 + renderer.getWidth(block.chatIndent());
        int width = Math.min(273, Math.max(24, RichChatLatexTextureCache.currentChatContentWidth() - Math.max(0, drawX) - 2));
        int rowHeight = Math.max(renderer.fontHeight, RichChatLatexTextureCache.currentChatLineHeight());
        int drawY = y + 2;
        int viewportTop = RichChatRenderContext.currentChatViewportTop();
        int viewportBottom = RichChatRenderContext.currentChatViewportBottom();
        int clippedTop = Math.max(drawY, viewportTop);
        // The first code row carries a 14px menu while a text row is shorter.
        // Include its full paint/hit area so the scissor cannot cut its edge.
        int cardHeight = marker.row() == 0 ? Math.max(rowHeight, MENU_BUTTON_SIZE + 2) : rowHeight;
        int scissorBottom = Math.min(drawY + cardHeight, viewportBottom + EXTRA_RENDER_BOTTOM);
        int paintBottom = Math.min(drawY + rowHeight, viewportBottom + EXTRA_RENDER_BOTTOM);
        if (paintBottom <= clippedTop || width <= 0) {
            return lineX;
        }

        // Hide the per-row vanilla chat surface so it cannot show through as
        // horizontal dark bands inside a multi-line code card.
        int fillBase = 0xD814171C;
        int borderBase = 0xB81E242D;
        int fill = withMultipliedAlpha(style == RichChatPrivateMessageBridge.VisualStyle.DIM ? RichChatPrivateMessageBridge.dimColor(fillBase) : fillBase, chatAlpha);
        int border = withMultipliedAlpha(style == RichChatPrivateMessageBridge.VisualStyle.DIM ? RichChatPrivateMessageBridge.dimColor(borderBase) : borderBase, chatAlpha);
        enableTransformedScissor(context, drawX, clippedTop, drawX + width + 1, scissorBottom);
        context.fill(drawX, clippedTop, drawX + width, paintBottom, fill);
        if (marker.row() == 0) {
            context.fill(drawX, drawY, drawX + width, drawY + 1, border);
        }
        if (marker.row() == block.displayLines().size() - 1) {
            context.fill(drawX, drawY + rowHeight - 1, drawX + width, drawY + rowHeight, border);
        }
        context.fill(drawX, clippedTop, drawX + 1, paintBottom, border);
        context.fill(drawX + width - 1, clippedTop, drawX + width, paintBottom, border);

        int menuX = drawX + width - MENU_BUTTON_SIZE - 4;
        int contentX = drawX + CODE_BLOCK_PADDING;
        int contentWidth = Math.max(24, menuX - contentX - (marker.row() == 0 ? 4 : 0));
        renderCodeSyntaxLine(context, renderer, RichChatCodeBlockBridge.syntaxFileName(block), block.displayLines().get(marker.row()), contentX, drawY + 1, contentWidth, style, chatAlpha);
        if (marker.row() == 0) {
            drawMoreButton(context, renderer, menuX, drawY + 1, actionMenuCodeBlockId != null && actionMenuCodeBlockId.equals(block.id()), chatAlpha);
        }
        if (style == RichChatPrivateMessageBridge.VisualStyle.DIM) {
            context.fill(drawX, clippedTop, drawX + width, paintBottom, withMultipliedAlpha(RichChatPrivateMessageBridge.dimOverlayColor(), chatAlpha));
        }
        context.disableScissor();
        if (marker.row() == 0) {
            putInlineCodeButton(context, block.id(), CodeButtonAction.MENU, menuX, drawY + 1, MENU_BUTTON_SIZE, MENU_BUTTON_SIZE);
        }
        return drawX + width;
    }

    private static void renderCodeSyntaxLine(DrawContext context, TextRenderer renderer, String fileName, String line, int x, int y, int maxWidth, RichChatPrivateMessageBridge.VisualStyle style, int chatAlpha) {
        int cursor = x;
        int right = x + Math.max(8, maxWidth);
        for (EditorSyntaxHighlighter.StyledSpan span : EditorSyntaxHighlighter.highlight(fileName, line == null ? "" : line)) {
            if (span == null || span.text() == null || span.text().isEmpty() || cursor >= right) {
                continue;
            }
            String visible = renderer.trimToWidth(span.text(), Math.max(1, right - cursor));
            if (visible.isEmpty()) {
                continue;
            }
            int syntaxColor = RichChatSettings.chatColorsEnabled() ? span.color() : 0xFFF5F7FA;
            int color = withMultipliedAlpha(style == RichChatPrivateMessageBridge.VisualStyle.DIM ? RichChatPrivateMessageBridge.dimColor(syntaxColor) : syntaxColor, chatAlpha);
            cursor = RichChatLatexTextureRenderer.renderOrDrawText(context, renderer, Text.literal(visible).asOrderedText(), cursor, y, color);
        }
    }

    private static void drawIconButton(DrawContext context, int x, int y, Identifier icon) {
        drawIconButton(context, x, y, icon, 255);
    }

    private static void drawIconButton(DrawContext context, int x, int y, Identifier icon, int alpha) {
        drawTextureWithAlpha(context, icon, x, y, 0, 0, 16, 16, 16, 16, alpha);
    }

    private static void drawMoreButton(DrawContext context, TextRenderer renderer, int x, int y, boolean hovered) {
        drawMoreButton(context, renderer, x, y, hovered, 255);
    }

    private static void drawMoreButton(DrawContext context, TextRenderer renderer, int x, int y, boolean hovered, int alpha) {
        drawMoreButton(context, renderer, x, y, hovered, alpha, 14);
    }

    private static void drawMoreButton(DrawContext context, TextRenderer renderer, int x, int y, boolean hovered, int alpha, int height) {
        PopupMenu.renderTriggerButton(context, renderer, x, y, hovered, "...", Math.min(alpha, 220), height);
    }

    private static void drawMediaMoreButton(DrawContext context, TextRenderer renderer, int x, int y, boolean hovered, int alpha, int height) {
        drawFileActionButton(context, renderer, x, y, MENU_BUTTON_SIZE, height, "...", hovered, alpha);
    }

    private static void drawSmallActionButton(DrawContext context, TextRenderer renderer, int x, int y, int width, String label) {
        drawSmallActionButton(context, renderer, x, y, width, label, 255);
    }

    private static void drawSmallActionButton(DrawContext context, TextRenderer renderer, int x, int y, int width, String label, int alpha) {
        drawSmallActionButton(context, renderer, x, y, width, label, alpha, MENU_BUTTON_SIZE);
    }

    private static void drawSmallActionButton(DrawContext context, TextRenderer renderer, int x, int y, int width, String label, int alpha, int height) {
        context.fill(x, y, x + width, y + height, withMultipliedAlpha(0x6C2A303A, alpha));
        context.drawBorder(x, y, width, height, withMultipliedAlpha(0x886B7485, alpha));
        context.drawText(renderer, label, x + Math.max(3, (width - renderer.getWidth(label)) / 2), y + Math.max(2, (height - renderer.fontHeight) / 2), withMultipliedAlpha(0xB9F5F7FA, alpha), false);
    }

    private static void drawFileActionButton(DrawContext context, TextRenderer renderer, int x, int y, int width, int height, String label, boolean hovered, int alpha) {
        if (context == null || renderer == null || label == null) {
            return;
        }
        int fill = hovered ? 0xD84D5563 : 0xD02A303A;
        int border = hovered ? 0xE89AA5B7 : 0xDA6B7485;
        int text = hovered ? 0xF0F5F7FA : 0xD6F5F7FA;
        int right = x + width;
        int bottom = y + height;
        int fillColor = withMultipliedAlpha(fill, alpha);
        int borderColor = withMultipliedAlpha(border, alpha);
        context.fill(x, y, right, bottom, borderColor);
        context.fill(x + 1, y + 1, right - 1, bottom - 1, fillColor);
        context.drawText(renderer, label, x + Math.max(3, (width - renderer.getWidth(label)) / 2), y + Math.max(1, (height - renderer.fontHeight) / 2), withMultipliedAlpha(text, alpha), false);
    }

    private static int smallActionButtonWidth(TextRenderer renderer, String label) {
        return Math.max(28, renderer.getWidth(label) + 8);
    }

    private static void rememberHoverTooltip(DrawContext context, TextRenderer renderer, String fullText, String renderedText, int x, int y, int width) {
        if (context == null || renderer == null || fullText == null || renderedText == null || fullText.equals(renderedText)) {
            return;
        }
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        Vector4f topLeft = new Vector4f(x, y, 0.0F, 1.0F);
        Vector4f bottomRight = new Vector4f(x + Math.min(width, renderer.getWidth(renderedText)), y + renderer.fontHeight + 2, 0.0F, 1.0F);
        topLeft.mul(matrix);
        bottomRight.mul(matrix);
        HOVER_TOOLTIPS.put(
                fullText + ":" + x + ":" + y,
                new HoverTooltip(
                        Math.round(Math.min(topLeft.x(), bottomRight.x())),
                        Math.round(Math.min(topLeft.y(), bottomRight.y())),
                        Math.max(1, Math.round(Math.abs(bottomRight.x() - topLeft.x()))),
                        Math.max(1, Math.round(Math.abs(bottomRight.y() - topLeft.y()))),
                        fullText
                )
        );
    }

    private static String trimWithEllipsis(TextRenderer renderer, String text, int width) {
        if (renderer == null || text == null || text.isEmpty() || width <= 0) {
            return text == null ? "" : text;
        }
        if (renderer.getWidth(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int ellipsisWidth = renderer.getWidth(ellipsis);
        String trimmed = renderer.trimToWidth(text, Math.max(1, width - ellipsisWidth));
        while (!trimmed.isEmpty() && renderer.getWidth(trimmed + ellipsis) > width) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + ellipsis;
    }

    private static String fileDescription(RichChatAttachment attachment) {
        String scope = scopeLabel(attachment);
        return scope.isBlank() ? RichChatUploadStorage.attachmentDescription(attachment) : scope + " | " + RichChatUploadStorage.attachmentDescription(attachment);
    }

    public static boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && ACTION_MENU.isOpen()) {
            PopupMenu.MenuEntry selected = ACTION_MENU.click(mouseX, mouseY);
            if (selected != null) {
                if (actionMenuAttachment != null) {
                    runMenuAction(selected.id(), actionMenuAttachment, mouseX, mouseY);
                } else if (actionMenuCodeBlockId != null) {
                    runCodeMenuAction(selected.id(), actionMenuCodeBlockId);
                }
                return true;
            }
            if (!ACTION_MENU.isOpen()) {
                return true;
            }
        }
        if (button == 0 && INFO_POPUP.isOpen()) {
            INFO_POPUP.close();
            return true;
        }
        Optional<ButtonBounds> buttonHit = BUTTONS.values().stream()
                .filter(bounds -> mouseX >= bounds.x() && mouseX <= bounds.x() + bounds.width() && mouseY >= bounds.y() && mouseY <= bounds.y() + bounds.height())
                .findFirst();
        if (buttonHit.isPresent()) {
            runButtonAction(buttonHit.get(), mouseX, mouseY);
            return true;
        }
        Optional<CodeButtonBounds> codeButtonHit = CODE_BUTTONS.values().stream()
                .filter(bounds -> mouseX >= bounds.x() && mouseX <= bounds.x() + bounds.width() && mouseY >= bounds.y() && mouseY <= bounds.y() + bounds.height())
                .findFirst();
        if (codeButtonHit.isPresent()) {
            runCodeButtonAction(codeButtonHit.get(), mouseX, mouseY);
            return true;
        }
        if (button == 0) {
            Optional<LinkBounds> linkHit = LINK_BOUNDS.values().stream()
                    .filter(bounds -> mouseX >= bounds.x() && mouseX <= bounds.x() + bounds.width() && mouseY >= bounds.y() && mouseY <= bounds.y() + bounds.height())
                    .findFirst();
            if (linkHit.isPresent()) {
                openMaskedLink(linkHit.get().target());
                return true;
            }
            Optional<SpoilerBounds> spoilerHit = SPOILER_BOUNDS.values().stream()
                    .filter(bounds -> mouseX >= bounds.x() && mouseX <= bounds.x() + bounds.width() && mouseY >= bounds.y() && mouseY <= bounds.y() + bounds.height())
                    .findFirst();
            if (spoilerHit.isPresent()) {
                VISIBLE_SPOILERS.add(spoilerHit.get().key());
                REVEALED_SPOILERS.add(spoilerHit.get().key());
                return true;
            }
        }
        if (focusState != null) {
            if (button == 0) {
                if (!focusState.containsMedia(mouseX, mouseY)) {
                    closeFocusState();
                    return true;
                }
                focusState.dragging = true;
                focusState.pendingClickClose = true;
                focusState.lastMouseX = mouseX;
                focusState.lastMouseY = mouseY;
                focusState.dragDistance = 0.0D;
            } else {
                closeFocusState();
            }
            return true;
        }
        if (button != 0) {
            return false;
        }
        Optional<MediaBounds> hit = BOUNDS.values().stream()
                .filter(bounds -> mouseX >= bounds.x() && mouseX <= bounds.x() + bounds.width() && mouseY >= bounds.y() && mouseY <= bounds.y() + bounds.height())
                .findFirst();
        if (hit.isEmpty()) {
            return false;
        }
        if (privateTimedMediaInactive(hit.get().attachment())) {
            return true;
        }
        if (hit.get().attachment() != null && hit.get().attachment().type() == RichChatAttachmentType.VIDEO) {
            long now = System.currentTimeMillis();
            UUID attachmentId = hit.get().attachment().attachmentId();
            boolean doubleClick = attachmentId != null
                    && attachmentId.equals(lastVideoClickAttachmentId)
                    && now - lastVideoClickTime <= 325L;
            lastVideoClickTime = now;
            lastVideoClickAttachmentId = attachmentId;
            if (!doubleClick) {
                return false;
            }
        }
        focusState = new FocusState(hit.get().attachment());
        if (focusState.attachment() != null && focusState.attachment().type() == RichChatAttachmentType.VIDEO) {
            VisualPlaybackSession session = videoSession(focusState.attachment(), Math.max(240, MinecraftClient.getInstance() == null ? 320 : MinecraftClient.getInstance().getWindow().getScaledWidth() - 32), Math.max(160, MinecraftClient.getInstance() == null ? 240 : MinecraftClient.getInstance().getWindow().getScaledHeight() - 64));
            if (session != null && session.state() != VisualPlaybackState.PLAYING) {
                playVideoSession(session, focusState.attachment());
            }
        } else if (focusState.attachment() != null && focusState.attachment().type() == RichChatAttachmentType.GIF) {
            VisualPlaybackSession session = gifSession(focusState.attachment(), Math.max(240, MinecraftClient.getInstance() == null ? 320 : MinecraftClient.getInstance().getWindow().getScaledWidth() - 32), Math.max(160, MinecraftClient.getInstance() == null ? 240 : MinecraftClient.getInstance().getWindow().getScaledHeight() - 32));
            if (session != null && session.state() != VisualPlaybackState.PLAYING) {
                session.play();
            }
        }
        return true;
    }

    public static boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button != 0 || seekDrag == null) {
            return false;
        }
        SeekDrag drag = seekDrag;
        seekDrag = null;
        float releaseProgress = progressForMouse(drag.x(), drag.width(), mouseX);
        if (drag.applied && Math.abs(releaseProgress - drag.lastAppliedProgress) <= 0.003F) {
            return true;
        }
        seekToMouse(drag.attachment(), drag.x(), drag.width(), mouseX, true);
        return true;
    }

    public static boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (focusState == null) {
            return false;
        }
        FocusDimensions dimensions = focusedDimensions(focusState);
        if (dimensions == null) {
            return false;
        }
        float before = focusState.zoom;
        focusState.zoom = Math.max(0.05F, Math.min(8.0F, focusState.zoom + (amount > 0 ? 0.15F : -0.15F)));
        if (focusState.fitZoom > 0.0F && focusState.zoom <= focusState.fitZoom + 0.02F) {
            focusState.zoom = focusState.fitZoom;
            focusState.center(dimensions.width(), dimensions.height(), focusState.screenWidth, focusState.screenHeight);
        } else {
            float ratio = focusState.zoom / before;
            focusState.offsetX = (float)(mouseX - (mouseX - focusState.offsetX) * ratio);
            focusState.offsetY = (float)(mouseY - (mouseY - focusState.offsetY) * ratio);
        }
        return true;
    }

    public static boolean keyPressed(int keyCode) {
        if (focusState == null) {
            return false;
        }
        if (focusState.attachment() != null && focusState.attachment().type() == RichChatAttachmentType.VIDEO && keyCode == GLFW.GLFW_KEY_SPACE) {
            VisualPlaybackSession session = videoSession(focusState.attachment(), Math.max(240, MinecraftClient.getInstance() == null ? 320 : MinecraftClient.getInstance().getWindow().getScaledWidth() - 32), Math.max(160, MinecraftClient.getInstance() == null ? 240 : MinecraftClient.getInstance().getWindow().getScaledHeight() - 64));
            if (session != null) {
                if (session.state() == VisualPlaybackState.PLAYING) {
                    session.pause();
                } else {
                    playVideoSession(session, focusState.attachment());
                }
            }
            return true;
        }
        if (keyCode == 256 || keyCode > 0) {
            closeFocusState();
            return true;
        }
        return false;
    }

    public static void renderFocused(DrawContext context, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        updateSeekDrag(mouseX);
        FocusState state = focusState;
        if (state == null) {
            renderAttachmentOverlays(context, screenWidth, screenHeight, mouseX, mouseY);
            return;
        }
        if (state.attachment() != null && state.attachment().type() == RichChatAttachmentType.VIDEO) {
            renderFocusedVideo(context, state, screenWidth, screenHeight, mouseX, mouseY);
            renderAttachmentOverlays(context, screenWidth, screenHeight, mouseX, mouseY);
            return;
        }
        if (state.attachment() != null && state.attachment().type() == RichChatAttachmentType.GIF) {
            renderFocusedGif(context, state, screenWidth, screenHeight, mouseX, mouseY);
            renderAttachmentOverlays(context, screenWidth, screenHeight, mouseX, mouseY);
            return;
        }
        ImageTexture texture = fullTexture(state.attachment());
        if (texture == null || texture.textureId() == null) {
            context.fill(0, 0, screenWidth, screenHeight, 0xAA000000);
            return;
        }
        context.fill(0, 0, screenWidth, screenHeight, 0xAA000000);
        if (!state.initialized) {
            float fit = Math.min((screenWidth - 32) / (float)Math.max(1, texture.width()), (screenHeight - 32) / (float)Math.max(1, texture.height()));
            state.fitZoom = Math.max(0.05F, Math.min(1.0F, fit));
            state.zoom = state.fitZoom;
            state.center(texture, screenWidth, screenHeight);
            state.initialized = true;
        }
        state.screenWidth = screenWidth;
        state.screenHeight = screenHeight;
        updateDragState();
        state = focusState;
        if (state == null) {
            return;
        }
        int drawX = Math.round(state.offsetX);
        int drawY = Math.round(state.offsetY);
        int drawWidth = Math.max(1, Math.round(texture.width() * state.zoom));
        int drawHeight = Math.max(1, Math.round(texture.height() * state.zoom));
        state.mediaX = drawX;
        state.mediaY = drawY;
        state.mediaWidth = drawWidth;
        state.mediaHeight = drawHeight;
        context.drawTexture(texture.textureId(), drawX, drawY, drawWidth, drawHeight, 0, 0, texture.width(), texture.height(), texture.width(), texture.height());
        renderFocusButtons(context, MinecraftClient.getInstance().textRenderer, state.attachment(), screenWidth, screenHeight);
        renderAttachmentOverlays(context, screenWidth, screenHeight, mouseX, mouseY);
    }

    private static void renderFocusButtons(DrawContext context, TextRenderer renderer, RichChatAttachment attachment, int screenWidth, int screenHeight) {
        if (renderer == null || attachment == null || focusState == null) {
            return;
        }
        if (attachment.type() == RichChatAttachmentType.VIDEO) {
            return;
        }
        renderFocusMenuButton(context, renderer, focusState);
    }

    private static ImageTexture thumbnail(UUID attachmentId, RichChatAttachment attachment) {
        ImageTexture cached = THUMBNAILS.get(attachmentId);
        if (cached != null) {
            return cached;
        }
        String path = imagePath(attachment);
        if (path == null || path.isBlank()) {
            if (!isRemoteAttachment(attachment)) {
                FAILURES.put(attachmentId, "missing path");
            }
            return null;
        }
        FAILURES.remove(attachmentId);
        try {
            ImageTexture texture = ImageTextureService.loadScaledTexture(new File(path), "koil", "rich_chat_" + attachment.sha256(), MAX_THUMB_WIDTH, MAX_THUMB_HEIGHT);
            if (texture != null) {
                THUMBNAILS.put(attachmentId, texture);
                FAILURES.remove(attachmentId);
            }
            return texture;
        } catch (Exception exception) {
            if (!isRemoteAttachment(attachment)) {
                FAILURES.put(attachmentId, exception.getMessage() == null ? "decode failed" : exception.getMessage());
            }
            return null;
        }
    }

    private static ImageTexture fullTexture(RichChatAttachment attachment) {
        if (attachment == null) {
            return null;
        }
        String path = imagePath(attachment);
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return ImageTextureService.loadTexture(new File(path), "koil", "rich_chat_full_" + attachment.sha256());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String imagePath(RichChatAttachment attachment) {
        if (attachment == null || attachment.metadata() == null) {
            return null;
        }
        String clientCache = attachment.metadata().get("client_cache_path");
        if (clientCache != null && !clientCache.isBlank()) {
            return clientCache;
        }
        String path = attachment.metadata().get("server_path");
        if (path != null && !path.isBlank()) {
            return path;
        }
        return RichChatRemoteImageCache.localPath(attachment).map(File::getAbsolutePath).orElse(null);
    }

    private static String attachmentPath(RichChatAttachment attachment) {
        if (attachment == null || attachment.metadata() == null) {
            return null;
        }
        String clientCache = attachment.metadata().get("client_cache_path");
        if (clientCache != null && !clientCache.isBlank()) {
            return clientCache;
        }
        String path = attachment.metadata().get("server_path");
        if (path != null && !path.isBlank()) {
            return path;
        }
        return RichChatRemoteImageCache.localPath(attachment).map(File::getAbsolutePath).orElse(null);
    }

    private static VisualPlaybackSession videoActionSession(RichChatAttachment attachment) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (focusState != null && focusState.attachment() != null && attachment != null
                && attachment.attachmentId() != null
                && attachment.attachmentId().equals(focusState.attachment().attachmentId())) {
            int screenWidth = client == null || client.getWindow() == null ? 320 : client.getWindow().getScaledWidth();
            int screenHeight = client == null || client.getWindow() == null ? 240 : client.getWindow().getScaledHeight();
            return videoSession(attachment, Math.max(240, screenWidth - 32), Math.max(160, screenHeight - 64));
        }
        return videoSession(attachment, MAX_THUMB_WIDTH, VIDEO_MAX_THUMB_HEIGHT);
    }

    private static boolean isRemoteAttachment(RichChatAttachment attachment) {
        return attachment != null && "remote_link".equalsIgnoreCase(metadata(attachment, "upload_state"));
    }

    private static void runButtonAction(ButtonBounds bounds, double mouseX, double mouseY) {
        if (bounds == null || bounds.attachment() == null) {
            return;
        }
        if (bounds.action() == ButtonAction.PINNED_VIDEO_PLAY_PAUSE) {
            VisualPlaybackSession session = pinnedVisualSession;
            if (session != null && sameAttachmentId(pinnedVisualAttachmentId, bounds.attachment().attachmentId())) {
                if (session.state() == VisualPlaybackState.PLAYING) {
                    session.pause();
                } else {
                    playVideoSession(session, bounds.attachment());
                }
            }
            return;
        }
        if (bounds.action() == ButtonAction.PINNED_VIDEO_STOP) {
            VisualPlaybackSession session = pinnedVisualSession;
            if (session != null && sameAttachmentId(pinnedVisualAttachmentId, bounds.attachment().attachmentId())) {
                session.stop();
                ActiveVisualPlaybackRegistry.clear(session);
            }
            return;
        }
        if (bounds.action() == ButtonAction.PINNED_VIDEO_SEEK) {
            VisualPlaybackSession session = pinnedVisualSession;
            if (session != null && session.canSeek() && sameAttachmentId(pinnedVisualAttachmentId, bounds.attachment().attachmentId())) {
                float progress = progressForMouse(bounds.x(), bounds.width(), mouseX);
                session.seekTo(Math.round(session.durationMillis() * progress));
                if (session.state() != VisualPlaybackState.PLAYING) {
                    playVideoSession(session, bounds.attachment());
                }
            }
            return;
        }
        if (bounds.action() == ButtonAction.PLAY_PAUSE) {
            if (bounds.attachment().type() == RichChatAttachmentType.VIDEO) {
                VisualPlaybackSession session = videoActionSession(bounds.attachment());
                if (session == null) {
                    return;
                }
                if (session.state() == VisualPlaybackState.PLAYING) {
                    session.pause();
                } else {
                    playVideoSession(session, bounds.attachment());
                }
                return;
            }
            File file = attachmentFile(bounds.attachment());
            if (file == null) {
                return;
            }
            if (!AudioManager.isCurrentAudioFile(file)) {
                activeAudioAttachmentId = bounds.attachment().attachmentId();
                AudioManager.playAudio(file, false, 1.0F);
            } else if (AudioManager.isAudioPlaying()) {
                AudioManager.pauseAudio();
            } else {
                activeAudioAttachmentId = bounds.attachment().attachmentId();
                AudioManager.playAudio(file, false, 1.0F);
            }
            return;
        }
        if (bounds.action() == ButtonAction.STOP) {
            if (bounds.attachment().type() == RichChatAttachmentType.VIDEO) {
                VisualPlaybackSession session = videoActionSession(bounds.attachment());
                if (session != null) {
                    session.stop();
                    ActiveVisualPlaybackRegistry.clear(session);
                }
                return;
            }
            activeAudioAttachmentId = null;
            AudioManager.stopAllAudio();
            return;
        }
        if (bounds.action() == ButtonAction.SEEK) {
            seekDrag = null;
            seekToMouse(bounds.attachment(), bounds.x(), bounds.width(), mouseX, true);
            return;
        }
        if (bounds.action() == ButtonAction.VIEW) {
            openInReadOnlyEditor(bounds.attachment());
            return;
        }
        if (bounds.action() == ButtonAction.MENU) {
            actionMenuAttachment = bounds.attachment();
            actionMenuCodeBlockId = null;
            INFO_POPUP.close();
            MinecraftClient client = MinecraftClient.getInstance();
            int width = client == null || client.getWindow() == null ? 320 : client.getWindow().getScaledWidth();
            int height = client == null || client.getWindow() == null ? 240 : client.getWindow().getScaledHeight();
            ACTION_MENU.toggleAtPointer(mouseX, mouseY, width, height, attachmentMenuEntries(bounds.attachment()));
        }
    }

    private static List<PopupMenu.MenuEntry> attachmentMenuEntries(RichChatAttachment attachment) {
        boolean pinned = sameAttachment(pinnedAttachment, attachment);
        return List.of(
                new PopupMenu.MenuEntry(pinned ? "unpin" : "pin", pinned ? "Unpin" : "Pin"),
                new PopupMenu.MenuEntry("download", "Download"),
                new PopupMenu.MenuEntry("file_info", "File Info")
        );
    }

    private static void runCodeButtonAction(CodeButtonBounds bounds, double mouseX, double mouseY) {
        if (bounds == null || bounds.action() != CodeButtonAction.MENU) {
            return;
        }
        actionMenuAttachment = null;
        actionMenuCodeBlockId = bounds.blockId();
        INFO_POPUP.close();
        MinecraftClient client = MinecraftClient.getInstance();
        int width = client == null || client.getWindow() == null ? 320 : client.getWindow().getScaledWidth();
        int height = client == null || client.getWindow() == null ? 240 : client.getWindow().getScaledHeight();
        ACTION_MENU.toggleAtPointer(mouseX, mouseY, width, height, List.of(
                new PopupMenu.MenuEntry("code_copy", "Copy"),
                new PopupMenu.MenuEntry("code_view", "View full"),
                new PopupMenu.MenuEntry("code_download", "Download")
        ));
    }

    private static void runMenuAction(String action, RichChatAttachment attachment, double mouseX, double mouseY) {
        if (attachment == null || action == null) {
            return;
        }
        if ("pin".equals(action)) {
            if (!sameAttachment(pinnedAttachment, attachment)) {
                closePinnedVisualSession();
            }
            pinnedAttachment = attachment;
        } else if ("unpin".equals(action)) {
            if (sameAttachment(pinnedAttachment, attachment)) {
                closePinnedVisualSession();
                pinnedAttachment = null;
            }
        } else if ("download".equals(action)) {
            downloadAttachment(attachment);
        } else if ("file_info".equals(action)) {
            MinecraftClient client = MinecraftClient.getInstance();
            int width = client == null || client.getWindow() == null ? 320 : client.getWindow().getScaledWidth();
            int height = client == null || client.getWindow() == null ? 240 : client.getWindow().getScaledHeight();
            INFO_POPUP.openAtPointer(mouseX, mouseY, width, height, buildInfoLines(attachment));
        }
    }

    private static void runCodeMenuAction(String action, String blockId) {
        RichChatCodeBlockBridge.CodeBlock block = RichChatCodeBlockBridge.block(blockId);
        if (block == null || action == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if ("code_copy".equals(action)) {
            if (client != null) {
                client.keyboard.setClipboard(String.join("\n", block.originalLines()));
            }
            return;
        }
        if ("code_download".equals(action)) {
            try {
                Path directory = Path.of("koil", "downloads", "chat");
                Files.createDirectories(directory);
                String extension = codeBlockExtension(RichChatCodeBlockBridge.syntaxFileName(block));
                Path target = uniqueDownloadTarget(directory.resolve("code-" + System.currentTimeMillis() + extension));
                Files.writeString(target, String.join("\n", block.originalLines()), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                if (client != null && client.inGameHud != null) {
                    client.inGameHud.getChatHud().addMessage(Text.literal("Downloaded code block to " + target.getFileName()));
                }
            } catch (Exception exception) {
                if (client != null && client.inGameHud != null) {
                    client.inGameHud.getChatHud().addMessage(Text.literal("Code block download failed."));
                }
            }
            return;
        }
        if ("code_view".equals(action)) {
            openCodeBlockInReadOnlyEditor(block);
        }
    }

    private static void openCodeBlockInReadOnlyEditor(RichChatCodeBlockBridge.CodeBlock block) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || block == null) {
            return;
        }
        try {
            Path directory = Path.of("koil", "cache", "chat_code");
            Files.createDirectories(directory);
            String extension = codeBlockExtension(RichChatCodeBlockBridge.syntaxFileName(block));
            Path source = directory.resolve("code-" + block.id() + extension);
            Files.writeString(source, String.join("\n", block.originalLines()), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            TextFileViewSupport.openReadOnlyEditor(client.currentScreen, source.toFile());
        } catch (Exception ignored) {
            if (client.inGameHud != null) {
                client.inGameHud.getChatHud().addMessage(Text.literal("Could not open the full code block."));
            }
        }
    }

    /** A command link only fills chat; it never silently executes server commands. */
    private static void openMaskedLink(String target) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || target == null || target.isBlank()) {
            return;
        }
        String normalized = target.trim();
        if (normalized.startsWith("command:")) {
            normalized = normalized.substring("command:".length()).trim();
        }
        if (normalized.startsWith("/")) {
            client.setScreen(new ChatScreen(normalized));
            return;
        }
        if (normalized.regionMatches(true, 0, "https://", 0, 8) || normalized.regionMatches(true, 0, "http://", 0, 7)) {
            ConfirmLinkScreen.open(normalized, client.currentScreen, false);
        }
    }

    private static void updateSeekDrag(double mouseX) {
        if (seekDrag != null) {
            seekDrag.lastMouseX = mouseX;
            seekToMouse(seekDrag.attachment(), seekDrag.x(), seekDrag.width(), mouseX, false);
        }
    }

    private static void seekToMouse(RichChatAttachment attachment, int x, int width, double mouseX, boolean playOnRelease) {
        float progress = progressForMouse(x, width, mouseX);
        if (seekDrag != null && seekDrag.attachment() == attachment && seekDrag.x() == x && seekDrag.width() == width) {
            seekDrag.applied = true;
            seekDrag.lastAppliedProgress = progress;
        }
        if (attachment != null && attachment.type() == RichChatAttachmentType.VIDEO) {
            VisualPlaybackSession session = videoActionSession(attachment);
            if (session == null || !session.canSeek()) {
                return;
            }
            session.seekTo(Math.round(session.durationMillis() * progress));
            if (playOnRelease && session.state() != VisualPlaybackState.PLAYING) {
                playVideoSession(session, attachment);
            }
            return;
        }
        File file = attachmentFile(attachment);
        if (file == null) {
            return;
        }
        activeAudioAttachmentId = attachment == null ? null : attachment.attachmentId();
        boolean current = AudioManager.isCurrentAudioFile(file);
        boolean playing = current && AudioManager.isAudioPlaying();
        if (!current) {
            if (!playOnRelease) {
                return;
            }
            AudioManager.playAudio(file, false, 1.0F);
            AudioManager.seekToProgress(file, progress, 1.0F);
            return;
        }
        AudioManager.seekToProgress(file, progress, 1.0F);
        if (playOnRelease && !playing) {
            AudioManager.playAudio(file, false, 1.0F);
        }
    }

    private static float progressForMouse(int x, int width, double mouseX) {
        return (float)Math.max(0.0D, Math.min(1.0D, (mouseX - x) / Math.max(1.0D, width)));
    }

    private static void renderAttachmentOverlays(DrawContext context, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        updateSeekDrag(mouseX);
        pollSeekRelease(mouseX);
        ACTION_MENU.render(context, mouseX, mouseY);
        INFO_POPUP.render(context);
    }

    private static void pollSeekRelease(double mouseX) {
        MinecraftClient client = MinecraftClient.getInstance();
        long handle = client != null && client.getWindow() != null ? client.getWindow().getHandle() : 0L;
        boolean leftDown = handle != 0L && GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (seekDrag != null && seekMouseDownLastFrame && !leftDown) {
            SeekDrag drag = seekDrag;
            seekDrag = null;
            float releaseProgress = progressForMouse(drag.x(), drag.width(), mouseX);
            if (drag.applied && Math.abs(releaseProgress - drag.lastAppliedProgress) <= 0.003F) {
                seekMouseDownLastFrame = leftDown;
                return;
            }
            seekToMouse(drag.attachment(), drag.x(), drag.width(), mouseX, true);
        }
        seekMouseDownLastFrame = leftDown;
    }

    private static String metadata(RichChatAttachment attachment, String key) {
        return attachment == null || attachment.metadata() == null ? "" : attachment.metadata().getOrDefault(key, "");
    }

    private static String emptyAs(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String expiryLabel(RichChatAttachment attachment) {
        String uploaded = metadata(attachment, "uploaded_at");
        try {
            if (!uploaded.isBlank()) {
                long millis = Long.parseLong(uploaded);
                return INFO_TIME_FORMAT.format(Instant.ofEpochMilli(millis).plusSeconds(30L * 24L * 60L * 60L));
            }
        } catch (Exception ignored) {
        }
        return attachment != null && metadata(attachment, "upload_state").equals("remote_link") ? "remote cache only" : "30 days after upload";
    }

    private static List<String> buildInfoLines(RichChatAttachment attachment) {
        return List.of(
                attachment.fileName(),
                "Type: " + RichChatUploadStorage.attachmentDescription(attachment),
                "Scope: " + scopeLabel(attachment),
                "Path: " + emptyAs(safeInfoPath(attachmentPath(attachment)), "remote/cache pending"),
                "Sender: " + emptyAs(metadata(attachment, "uploader_name"), "unknown"),
                "Expires: " + expiryLabel(attachment)
        );
    }

    private static String safeInfoPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "";
        }
        try {
            Path instanceRoot = Path.of(".").toAbsolutePath().normalize();
            Path absolute = Path.of(rawPath).toAbsolutePath().normalize();
            if (absolute.startsWith(instanceRoot)) {
                Path relative = instanceRoot.relativize(absolute);
                String normalized = relative.toString().replace("\\", "/");
                return normalized.isBlank() ? "." : "./" + normalized;
            }
            Path fileName = absolute.getFileName();
            return fileName == null ? absolute.toString().replace("\\", "/") : "./" + fileName;
        } catch (Exception ignored) {
            String normalized = rawPath.replace("\\", "/");
            int slash = normalized.lastIndexOf('/');
            String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
            return fileName.isBlank() ? "./unknown" : "./" + fileName;
        }
    }

    private static void downloadAttachment(RichChatAttachment attachment) {
        String source = attachmentPath(attachment);
        if (source == null || source.isBlank()) {
            return;
        }
        try {
            Path sourcePath = Path.of(source);
            if (!Files.isRegularFile(sourcePath)) {
                return;
            }
            String defaultDirectory = Path.of("koil", "downloads", "chat").toAbsolutePath().normalize().toString();
            String chosen = TinyFileDialogs.tinyfd_selectFolderDialog("Save Chat Attachment", defaultDirectory);
            Path directory = chosen == null || chosen.isBlank() ? Path.of(defaultDirectory) : Path.of(chosen);
            Files.createDirectories(directory);
            Path target = uniqueDownloadTarget(directory.resolve(safeFileName(attachment.fileName())));
            Files.copy(sourcePath, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    private static File attachmentFile(RichChatAttachment attachment) {
        String path = attachmentPath(attachment);
        if (path == null || path.isBlank()) {
            return null;
        }
        File file = new File(path);
        return file.isFile() ? file : null;
    }

    private static boolean canViewTextInEditor(RichChatAttachment attachment) {
        return TextFileViewSupport.isEditableTextFile(attachmentFile(attachment));
    }

    private static void openInReadOnlyEditor(RichChatAttachment attachment) {
        File file = attachmentFile(attachment);
        if (file == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        Screen parent = client == null ? null : client.currentScreen;
        TextFileViewSupport.openReadOnlyEditor(parent, file);
    }

    private static String scopeLabel(RichChatAttachment attachment) {
        String scope = metadata(attachment, "chat_scope");
        if ("private".equalsIgnoreCase(scope)) {
            return "Private";
        }
        if ("public".equalsIgnoreCase(scope) || "global".equalsIgnoreCase(scope)) {
            return "Public";
        }
        return "";
    }

    private static String formatMicros(long micros) {
        long seconds = Math.max(0L, micros / 1_000_000L);
        long minutes = seconds / 60L;
        long remaining = seconds % 60L;
        return minutes + ":" + (remaining < 10L ? "0" : "") + remaining;
    }

    private static Path uniqueDownloadTarget(Path requested) {
        if (!Files.exists(requested)) {
            return requested;
        }
        String name = requested.getFileName() == null ? "attachment" : requested.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        Path parent = requested.getParent() == null ? Path.of(".") : requested.getParent();
        for (int i = 1; i < 1000; i++) {
            Path candidate = parent.resolve(base + " (" + i + ")" + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return parent.resolve(base + " (" + System.currentTimeMillis() + ")" + extension);
    }

    private static String safeFileName(String name) {
        String clean = name == null || name.isBlank() ? "attachment" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return clean.isBlank() ? "attachment" : clean;
    }

    private static String codeBlockExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? ".txt" : fileName.substring(dot);
    }

    private static String codeBlockPrefix(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        int whitespace = 0;
        while (whitespace < line.length() && Character.isWhitespace(line.charAt(whitespace))) {
            whitespace++;
        }
        if (whitespace > 0) {
            return line.substring(0, whitespace);
        }
        if (line.startsWith("<")) {
            int end = line.indexOf("> ");
            if (end > 1) {
                return line.substring(0, end + 2);
            }
        }
        String[] prefixes = {"You whisper to ", "To ", "From "};
        for (String prefix : prefixes) {
            if (line.startsWith(prefix)) {
                int end = line.indexOf(": ");
                if (end > 0) {
                    return line.substring(0, end + 2);
                }
            }
        }
        int whisper = line.indexOf(" whispers to you: ");
        if (whisper > 0) {
            int end = line.indexOf(": ");
            if (end > 0) {
                return line.substring(0, end + 2);
            }
        }
        if (line.startsWith("[To ") || line.startsWith("[From ")) {
            int end = line.indexOf("] ");
            if (end > 0) {
                return line.substring(0, end + 2);
            }
        }
        return "";
    }

    private static void putButton(DrawContext context, ButtonKey key, RichChatAttachment attachment, int x, int y, int width, int height) {
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        Vector4f topLeft = new Vector4f(x, y, 0.0F, 1.0F);
        Vector4f bottomRight = new Vector4f(x + width, y + height, 0.0F, 1.0F);
        topLeft.mul(matrix);
        bottomRight.mul(matrix);
        BUTTONS.put(key, new ButtonBounds(
                key.action(),
                attachment,
                Math.round(Math.min(topLeft.x(), bottomRight.x())),
                Math.round(Math.min(topLeft.y(), bottomRight.y())),
                Math.max(1, Math.round(Math.abs(bottomRight.x() - topLeft.x()))),
                Math.max(1, Math.round(Math.abs(bottomRight.y() - topLeft.y())))
        ));
    }

    private static void putInlineButton(DrawContext context, ButtonKey key, RichChatAttachment attachment, int x, int y, int width, int height) {
        int[] rect = clippedInlineRect(context, x, y, width, height);
        if (rect == null) {
            return;
        }
        BUTTONS.put(key, new ButtonBounds(key.action(), attachment, rect[0], rect[1], rect[2], rect[3]));
    }

    private static void putCodeButton(DrawContext context, String blockId, CodeButtonAction action, int x, int y, int width, int height) {
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        Vector4f topLeft = new Vector4f(x, y, 0.0F, 1.0F);
        Vector4f bottomRight = new Vector4f(x + width, y + height, 0.0F, 1.0F);
        topLeft.mul(matrix);
        bottomRight.mul(matrix);
        CODE_BUTTONS.put(
                blockId + ":" + action.name(),
                new CodeButtonBounds(
                        action,
                        blockId,
                        Math.round(Math.min(topLeft.x(), bottomRight.x())),
                        Math.round(Math.min(topLeft.y(), bottomRight.y())),
                        Math.max(1, Math.round(Math.abs(bottomRight.x() - topLeft.x()))),
                        Math.max(1, Math.round(Math.abs(bottomRight.y() - topLeft.y())))
                )
        );
    }

    private static void putInlineCodeButton(DrawContext context, String blockId, CodeButtonAction action, int x, int y, int width, int height) {
        int[] rect = clippedInlineRect(context, x, y, width, height);
        if (rect == null) {
            return;
        }
        CODE_BUTTONS.put(
                blockId + ":" + action.name(),
                new CodeButtonBounds(action, blockId, rect[0], rect[1], rect[2], rect[3])
        );
    }

    private static PreviewSize previewSize(ImageTexture texture, int chatWidth) {
        return texture == null ? new PreviewSize(MIN_THUMB_WIDTH, MIN_THUMB_HEIGHT) : previewSize(texture.width(), texture.height(), chatWidth);
    }

    private static PreviewSize previewSize(int textureWidth, int textureHeight, int chatWidth) {
        return previewSize(textureWidth, textureHeight, chatWidth, MAX_THUMB_WIDTH, MAX_THUMB_HEIGHT, MIN_THUMB_WIDTH, MIN_THUMB_HEIGHT);
    }

    private static PreviewSize previewSize(int textureWidth, int textureHeight, int chatWidth, int maxWidthLimit, int maxHeightLimit, int minWidthLimit, int minHeightLimit) {
        // Narrow private-message prefixes can leave less than the historical
        // 32px floor. Scale the whole visual into the real remainder instead
        // of rendering at 32px and clipping its right side.
        int availableWidth = Math.max(1, chatWidth - 8);
        int maxWidth = Math.max(1, Math.min(Math.max(1, maxWidthLimit), availableWidth));
        int maxHeight = maxHeightLimit;
        textureWidth = Math.max(1, textureWidth);
        textureHeight = Math.max(1, textureHeight);
        float scale = Math.min(maxWidth / (float)textureWidth, maxHeight / (float)textureHeight);
        if (scale <= 0.0F) {
            scale = 1.0F;
        }
        int width = Math.max(1, Math.round(textureWidth * scale));
        int height = Math.max(1, Math.round(textureHeight * scale));
        if (width < minWidthLimit && textureWidth >= textureHeight) {
            float grow = Math.min(maxWidth / (float)width, minWidthLimit / (float)width);
            width = Math.min(maxWidth, Math.round(width * grow));
            height = Math.min(maxHeight, Math.round(height * grow));
        } else if (height < minHeightLimit && textureHeight > textureWidth) {
            // A portrait minimum may only enlarge while both dimensions fit.
            // Clamping width after growing height independently distorted very
            // narrow PM previews and kept them taller than their real scale.
            float grow = Math.min(
                    maxWidth / (float)width,
                    Math.min(maxHeight / (float)height, minHeightLimit / (float)height)
            );
            width = Math.max(1, Math.round(width * grow));
            height = Math.max(1, Math.round(height * grow));
        }
        return new PreviewSize(Math.max(1, width), Math.max(1, height));
    }

    private static int visualReservedRows(RichChatAttachment attachment, boolean video, int leadingWidth) {
        int lineHeight = Math.max(1, RichChatLatexTextureCache.currentChatLineHeight());
        int fallbackRows = video
                ? Math.max(14, (VIDEO_MAX_THUMB_HEIGHT + VIDEO_FOOTER_HEIGHT + lineHeight - 1) / lineHeight)
                : 15;
        if (attachment == null || attachment.width() <= 0 || attachment.height() <= 0) {
            return fallbackRows;
        }
        int chatWidth = inlineMediaWidth(Math.max(0, leadingWidth) + MEDIA_START_NUDGE);
        PreviewSize size = video
                ? previewSize(attachment.width(), attachment.height(), chatWidth, MAX_THUMB_WIDTH, VIDEO_MAX_THUMB_HEIGHT, MIN_THUMB_WIDTH, MIN_THUMB_HEIGHT)
                : previewSize(attachment.width(), attachment.height(), chatWidth);
        int bottomSlack = switch (attachment.type()) {
            case IMAGE -> -2;
            case GIF -> 1;
            case VIDEO -> 2;
            default -> 2;
        };
        int reservedHeight = Math.max(1, size.height() + EXTRA_RENDER_BOTTOM + bottomSlack);
        int rows = Math.max(2, (reservedHeight + lineHeight - 1) / lineHeight);
        int maxRows = video ? 80 : 20;
        int filteredReserve = RichChatPrivateMessageBridge.filterEnabled() ? FILTERED_VISUAL_RESERVE_ROWS : 0;
        return Math.min(maxRows, rows) + filteredReserve;
    }

    private static int markerTopOffset(LocalRichAttachmentBridge.Marker marker) {
        if (marker == null || marker.row() != 0) {
            return 0;
        }
        String prefix = LocalRichAttachmentBridge.prefix(marker);
        return hasUsernamePrefix(prefix) ? MARKER_TIMESTAMP_CLEARANCE : 0;
    }

    private static int markerVerticalAdjustment(RichChatAttachment attachment) {
        if (attachment == null || attachment.type() == null) {
            return 0;
        }
        return switch (attachment.type()) {
            case GIF -> -1;
            case IMAGE -> 1;
            case FILE -> -1;
            case AUDIO -> 2;
            case VIDEO -> 1;
            default -> 0;
        };
    }

    private static int fixedReservedRows(int height) {
        int lineHeight = Math.max(1, RichChatLatexTextureCache.currentChatLineHeight());
        return Math.max(2, (Math.max(1, height) + lineHeight - 1) / lineHeight);
    }

    private static int remainingVisualChatWidth(int lineX, int drawX, RichChatAttachment attachment) {
        int used = Math.max(0, drawX - lineX);
        return inlineMediaWidth(used);
    }

    private static int inlineMediaWidth(int usedWidth) {
        MinecraftClient client = MinecraftClient.getInstance();
        return Math.max(1, ChatHudPanelRegistry.localPanelWidth(client) - Math.max(0, usedWidth));
    }

    private static boolean hasUsernamePrefix(String prefix) {
        return prefix != null && prefix.startsWith("<") && prefix.indexOf("> ") > 1;
    }

    private static FocusDimensions focusedDimensions(FocusState state) {
        if (state == null || state.attachment() == null) {
            return null;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        int screenWidth = client == null || client.getWindow() == null ? 320 : client.getWindow().getScaledWidth();
        int screenHeight = client == null || client.getWindow() == null ? 240 : client.getWindow().getScaledHeight();
        if (state.attachment().type() == RichChatAttachmentType.VIDEO) {
            VisualPlaybackSession session = videoSession(state.attachment(), Math.max(240, screenWidth - 32), Math.max(160, screenHeight - 64));
            if (session == null) {
                return null;
            }
            return new FocusDimensions(Math.max(1, session.frameWidth()), Math.max(1, session.frameHeight()));
        }
        if (state.attachment().type() == RichChatAttachmentType.GIF) {
            VisualPlaybackSession session = gifSession(state.attachment(), Math.max(240, screenWidth - 32), Math.max(160, screenHeight - 32));
            if (session == null) {
                return null;
            }
            return new FocusDimensions(Math.max(1, session.frameWidth()), Math.max(1, session.frameHeight()));
        }
        ImageTexture texture = fullTexture(state.attachment());
        if (texture == null) {
            return null;
        }
        return new FocusDimensions(Math.max(1, texture.width()), Math.max(1, texture.height()));
    }

    private static RichChatPrivateMessageBridge.VisualStyle overrideStyleForSelectedPrivateAttachment(RichChatPrivateMessageBridge.VisualStyle style, String displayText) {
        if (style != RichChatPrivateMessageBridge.VisualStyle.DIM || displayText == null || !LocalRichAttachmentBridge.containsMarker(displayText)) {
            return style;
        }
        LocalRichAttachmentBridge.Marker marker = LocalRichAttachmentBridge.nextMarker(displayText, 0);
        RichChatAttachment attachment = LocalRichAttachmentBridge.attachment(marker).orElse(null);
        return privateSelectedAttachment(attachment) ? RichChatPrivateMessageBridge.VisualStyle.NORMAL : style;
    }

    private static int selectedPrivateAlignedX(TextRenderer renderer, int lineX, RichChatAttachment attachment, int fallbackX) {
        if (!privateSelectedAttachment(attachment) || renderer == null) {
            return fallbackX;
        }
        String sender = metadata(attachment, "chat_sender");
        if (sender == null || sender.isBlank()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.player != null && client.player.getGameProfile() != null) {
                sender = client.player.getGameProfile().getName();
            }
        }
        if (sender == null || sender.isBlank()) {
            return fallbackX;
        }
        return bodyAlignedMarkerX(lineX, renderer.getWidth("<" + sender.trim() + "> ")) + MEDIA_START_NUDGE;
    }

    private static boolean privateSelectedAttachment(RichChatAttachment attachment) {
        if (attachment == null || !RichChatPrivateMessageBridge.filterEnabled()) {
            return false;
        }
        String scope = metadata(attachment, "chat_scope");
        String partner = metadata(attachment, "chat_partner");
        if (partner == null || partner.isBlank()) {
            partner = metadata(attachment, "chat_target");
        }
        String target = RichChatPrivateMessageBridge.targetPlayer();
        return "private".equalsIgnoreCase(scope)
                && partner != null
                && !partner.isBlank()
                && target != null
                && !target.isBlank()
                && partner.trim().equalsIgnoreCase(target.trim());
    }

    private static boolean privateTimedMediaInactive(RichChatAttachment attachment) {
        if (attachment == null || attachment.type() == null) {
            return false;
        }
        return switch (attachment.type()) {
            case AUDIO, GIF, VIDEO -> isPrivateAttachment(attachment) && !privateSelectedAttachment(attachment);
            default -> false;
        };
    }

    private static boolean isPrivateAttachment(RichChatAttachment attachment) {
        return "private".equalsIgnoreCase(metadata(attachment, "chat_scope"));
    }

    private static void renderFocusMenuButton(DrawContext context, TextRenderer renderer, FocusState state) {
        if (context == null || renderer == null || state == null || state.attachment() == null || state.attachment().attachmentId() == null) {
            return;
        }
        int menuX = Math.max(8, state.screenWidth - MENU_BUTTON_SIZE - 10);
        int menuY = 9;
        drawMediaMoreButton(context, renderer, menuX, menuY, actionMenuAttachment != null && state.attachment().attachmentId().equals(actionMenuAttachment.attachmentId()), 220, MENU_BUTTON_SIZE);
        putButton(context, new ButtonKey(state.attachment().attachmentId().toString(), ButtonAction.MENU), state.attachment(), menuX, menuY, MENU_BUTTON_SIZE, MENU_BUTTON_SIZE);
    }

    private static void updateDragState() {
        if (focusState == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        long handle = client != null && client.getWindow() != null ? client.getWindow().getHandle() : 0L;
        boolean leftDown = handle != 0L && GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (focusState.dragging && leftDown) {
            double deltaX = frameMouseX - focusState.lastMouseX;
            double deltaY = frameMouseY - focusState.lastMouseY;
            focusState.offsetX += (float)deltaX;
            focusState.offsetY += (float)deltaY;
            focusState.dragDistance += Math.abs(deltaX) + Math.abs(deltaY);
            focusState.lastMouseX = frameMouseX;
            focusState.lastMouseY = frameMouseY;
            if (focusState.dragDistance > 3.0D) {
                focusState.pendingClickClose = false;
            }
        } else if (focusState.dragging) {
            boolean close = focusState.pendingClickClose && focusState.dragDistance <= 3.0D;
            focusState.dragging = false;
            focusState.pendingClickClose = false;
            if (close) {
                closeFocusState();
            }
        }
    }

    private static void renderFocusedVideo(DrawContext context, FocusState state, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        context.fill(0, 0, screenWidth, screenHeight, 0xAA000000);
        VisualPlaybackSession session = videoSession(state.attachment(), Math.max(240, screenWidth - 32), Math.max(160, screenHeight - 64));
        if (session == null) {
            return;
        }
        session.update(System.currentTimeMillis());
        Identifier frame = session.currentFrameTexture();
        if (frame == null) {
            return;
        }
        int sourceWidth = Math.max(1, session.frameWidth());
        int sourceHeight = Math.max(1, session.frameHeight());
        if (!state.initialized) {
            float fit = Math.min((screenWidth - 32) / (float)sourceWidth, (screenHeight - 64) / (float)sourceHeight);
            state.fitZoom = Math.max(0.05F, Math.min(1.0F, fit));
            state.zoom = state.fitZoom;
            state.center(sourceWidth, sourceHeight, screenWidth, screenHeight);
            state.initialized = true;
        }
        state.screenWidth = screenWidth;
        state.screenHeight = screenHeight;
        updateDragState();
        int drawWidth = Math.max(1, Math.round(sourceWidth * state.zoom));
        int drawHeight = Math.max(1, Math.round(sourceHeight * state.zoom));
        int drawX = Math.round(state.offsetX);
        int drawY = Math.round(state.offsetY);
        state.mediaX = drawX;
        state.mediaY = drawY;
        state.mediaWidth = drawWidth;
        state.mediaHeight = drawHeight;
        context.drawTexture(frame, drawX, drawY, drawWidth, drawHeight, 0, 0, sourceWidth, sourceHeight, sourceWidth, sourceHeight);
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        boolean hovered = mouseX >= drawX && mouseX <= drawX + drawWidth && mouseY >= drawY && mouseY <= drawY + drawHeight;
        VisualTransportControls.RenderResult layout = VisualTransportControls.renderPreviewFooter(
                context,
                renderer,
                session,
                VisualTransportControls.koilPreviewFooterSpec(
                        drawX,
                        drawY,
                        drawWidth,
                        drawHeight,
                        false,
                        hovered,
                        true,
                        FileExplorerScreen.PLAY_BUTTON,
                        FileExplorerScreen.PAUSE_BUTTON,
                        FileExplorerScreen.STOP_BUTTON
                )
        );
        if (layout.primaryBounds() != null) {
            putButton(context, new ButtonKey(state.attachment().attachmentId().toString() + ":focus", ButtonAction.PLAY_PAUSE), state.attachment(), layout.primaryBounds()[0], layout.primaryBounds()[1], layout.primaryBounds()[2], layout.primaryBounds()[3]);
        }
        if (layout.secondaryBounds() != null) {
            putButton(context, new ButtonKey(state.attachment().attachmentId().toString() + ":focus", ButtonAction.STOP), state.attachment(), layout.secondaryBounds()[0], layout.secondaryBounds()[1], layout.secondaryBounds()[2], layout.secondaryBounds()[3]);
        }
        if (layout.timelineBounds() != null) {
            putButton(context, new ButtonKey(state.attachment().attachmentId().toString() + ":focus", ButtonAction.SEEK), state.attachment(), layout.timelineBounds()[0], layout.timelineBounds()[1], layout.timelineBounds()[2], layout.timelineBounds()[3]);
        }
        renderFocusMenuButton(context, renderer, state);
    }

    private static void renderFocusedGif(DrawContext context, FocusState state, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        context.fill(0, 0, screenWidth, screenHeight, 0xAA000000);
        VisualPlaybackSession session = gifSession(state.attachment(), Math.max(240, screenWidth - 32), Math.max(160, screenHeight - 32));
        if (session == null) {
            return;
        }
        if (session.state() != VisualPlaybackState.PLAYING) {
            session.play();
        }
        session.update(System.currentTimeMillis());
        Identifier frame = session.currentFrameTexture();
        if (frame == null) {
            return;
        }
        int sourceWidth = Math.max(1, session.frameWidth());
        int sourceHeight = Math.max(1, session.frameHeight());
        if (!state.initialized) {
            float fit = Math.min((screenWidth - 32) / (float)sourceWidth, (screenHeight - 32) / (float)sourceHeight);
            state.fitZoom = Math.max(0.05F, Math.min(1.0F, fit));
            state.zoom = state.fitZoom;
            state.center(sourceWidth, sourceHeight, screenWidth, screenHeight);
            state.initialized = true;
        }
        state.screenWidth = screenWidth;
        state.screenHeight = screenHeight;
        updateDragState();
        int drawWidth = Math.max(1, Math.round(sourceWidth * state.zoom));
        int drawHeight = Math.max(1, Math.round(sourceHeight * state.zoom));
        int drawX = Math.round(state.offsetX);
        int drawY = Math.round(state.offsetY);
        state.mediaX = drawX;
        state.mediaY = drawY;
        state.mediaWidth = drawWidth;
        state.mediaHeight = drawHeight;
        context.drawTexture(frame, drawX, drawY, drawWidth, drawHeight, 0, 0, sourceWidth, sourceHeight, sourceWidth, sourceHeight);
        renderFocusButtons(context, MinecraftClient.getInstance().textRenderer, state.attachment(), screenWidth, screenHeight);
    }

    private static VisualPlaybackSession videoSession(RichChatAttachment attachment, int maxWidth, int maxHeight) {
        if (attachment == null || attachment.attachmentId() == null || attachment.type() != RichChatAttachmentType.VIDEO) {
            return null;
        }
        File file = attachmentFile(attachment);
        if (file == null || !file.isFile()) {
            return null;
        }
        String sessionKey = file.getAbsolutePath() + "|" + maxWidth + "x" + maxHeight;
        VisualPlaybackSession existing = VIDEO_SESSIONS.get(attachment.attachmentId());
        if (existing != null && sessionKey.equals(VIDEO_SESSION_KEYS.get(attachment.attachmentId()))) {
            return existing;
        }
        closeVideoSession(attachment.attachmentId());
        VisualPlaybackSession created = VideoService.createSession(file, maxWidth, maxHeight);
        if (created != null) {
            VIDEO_SESSIONS.put(attachment.attachmentId(), created);
            VIDEO_SESSION_KEYS.put(attachment.attachmentId(), sessionKey);
        }
        return created;
    }

    private static VisualPlaybackSession pinnedVisualSession(RichChatAttachment attachment, int maxWidth, int maxHeight) {
        if (attachment == null || attachment.attachmentId() == null
                || (attachment.type() != RichChatAttachmentType.VIDEO && attachment.type() != RichChatAttachmentType.GIF)) {
            return null;
        }
        File file = attachmentFile(attachment);
        if (file == null || !file.isFile()) {
            return null;
        }
        String key = attachment.type().name() + "|" + file.getAbsolutePath() + "|" + maxWidth + "x" + maxHeight;
        if (pinnedVisualSession != null && attachment.attachmentId().equals(pinnedVisualAttachmentId) && key.equals(pinnedVisualSessionKey)) {
            return pinnedVisualSession;
        }
        closePinnedVisualSession();
        try {
            pinnedVisualSession = attachment.type() == RichChatAttachmentType.VIDEO
                    ? VideoService.createSession(file, maxWidth, maxHeight)
                    : AnimatedGifPlaybackSession.createIfAnimated(file, maxWidth, maxHeight);
            if (pinnedVisualSession != null) {
                pinnedVisualAttachmentId = attachment.attachmentId();
                pinnedVisualSessionKey = key;
            }
            return pinnedVisualSession;
        } catch (Exception ignored) {
            closePinnedVisualSession();
            return null;
        }
    }

    private static void closePinnedVisualSession() {
        VisualPlaybackSession session = pinnedVisualSession;
        pinnedVisualSession = null;
        pinnedVisualSessionKey = null;
        pinnedVisualAttachmentId = null;
        if (session != null) {
            ActiveVisualPlaybackRegistry.clear(session);
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static VisualPlaybackSession gifSession(RichChatAttachment attachment, int maxWidth, int maxHeight) {
        if (attachment == null || attachment.attachmentId() == null || attachment.type() != RichChatAttachmentType.GIF) {
            return null;
        }
        File file = attachmentFile(attachment);
        if (file == null || !file.isFile()) {
            return null;
        }
        String sessionKey = file.getAbsolutePath() + "|" + maxWidth + "x" + maxHeight;
        VisualPlaybackSession existing = GIF_SESSIONS.get(attachment.attachmentId());
        if (existing != null && sessionKey.equals(GIF_SESSION_KEYS.get(attachment.attachmentId()))) {
            return existing;
        }
        closeGifSession(attachment.attachmentId());
        try {
            VisualPlaybackSession created = AnimatedGifPlaybackSession.createIfAnimated(file, maxWidth, maxHeight);
            if (created != null) {
                GIF_SESSIONS.put(attachment.attachmentId(), created);
                GIF_SESSION_KEYS.put(attachment.attachmentId(), sessionKey);
            }
            return created;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void closeVideoSession(UUID attachmentId) {
        if (attachmentId == null) {
            return;
        }
        VisualPlaybackSession session = VIDEO_SESSIONS.remove(attachmentId);
        if (session != null) {
            ActiveVisualPlaybackRegistry.clear(session);
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
        VIDEO_SESSION_KEYS.remove(attachmentId);
    }

    private static void closeGifSession(UUID attachmentId) {
        if (attachmentId == null) {
            return;
        }
        VisualPlaybackSession session = GIF_SESSIONS.remove(attachmentId);
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
        GIF_SESSION_KEYS.remove(attachmentId);
    }

    private static void closeFocusState() {
        if (focusState != null && focusState.attachment() != null && focusState.attachment().attachmentId() != null) {
            if (focusState.attachment().type() == RichChatAttachmentType.VIDEO) {
                closeVideoSession(focusState.attachment().attachmentId());
            } else if (focusState.attachment().type() == RichChatAttachmentType.GIF) {
                closeGifSession(focusState.attachment().attachmentId());
            }
        }
        focusState = null;
    }

    private static boolean isFocusedAttachment(RichChatAttachment attachment) {
        return attachment != null
                && attachment.attachmentId() != null
                && focusState != null
                && focusState.attachment() != null
                && attachment.attachmentId().equals(focusState.attachment().attachmentId());
    }

    private static void playVideoSession(VisualPlaybackSession session, RichChatAttachment attachment) {
        if (session == null) {
            return;
        }
        stopOtherVideoSessions(session);
        session.play();
        ActiveVisualPlaybackRegistry.activate(session, attachment == null ? "Video" : attachment.fileName());
    }

    private static void stopOtherVideoSessions(VisualPlaybackSession keepSession) {
        for (Map.Entry<UUID, VisualPlaybackSession> entry : VIDEO_SESSIONS.entrySet()) {
            VisualPlaybackSession session = entry.getValue();
            if (session == null || session == keepSession) {
                continue;
            }
            try {
                session.pause();
                session.stop();
            } catch (Exception ignored) {
            }
        }
        VisualPlaybackSession pinnedSession = pinnedVisualSession;
        if (pinnedSession != null && pinnedSession != keepSession) {
            try {
                pinnedSession.pause();
                pinnedSession.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private static MediaBounds screenBounds(DrawContext context, UUID attachmentId, RichChatAttachment attachment, int x, int y, int width, int height) {
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        Vector4f topLeft = new Vector4f(x, y, 0.0F, 1.0F);
        Vector4f bottomRight = new Vector4f(x + width, y + height, 0.0F, 1.0F);
        topLeft.mul(matrix);
        bottomRight.mul(matrix);
        int screenX = Math.round(Math.min(topLeft.x(), bottomRight.x()));
        int screenY = Math.round(Math.min(topLeft.y(), bottomRight.y()));
        int screenWidth = Math.max(1, Math.round(Math.abs(bottomRight.x() - topLeft.x())));
        int screenHeight = Math.max(1, Math.round(Math.abs(bottomRight.y() - topLeft.y())));
        return new MediaBounds(attachmentId, attachment, screenX, screenY, screenWidth, screenHeight);
    }

    /** DrawContext scissor coordinates are screen-space and do not follow the
     * current matrix, unlike textures and fills. Convert the local chat rect
     * explicitly so HUD panel translations cannot leave clipping behind. */
    private static void enableTransformedScissor(DrawContext context, int left, int top, int right, int bottom) {
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        Vector4f topLeft = new Vector4f(left, top, 0.0F, 1.0F);
        Vector4f bottomRight = new Vector4f(right, bottom, 0.0F, 1.0F);
        topLeft.mul(matrix);
        bottomRight.mul(matrix);
        int screenLeft = (int) Math.floor(Math.min(topLeft.x(), bottomRight.x()));
        int screenTop = (int) Math.floor(Math.min(topLeft.y(), bottomRight.y()));
        int screenRight = (int) Math.ceil(Math.max(topLeft.x(), bottomRight.x()));
        int screenBottom = (int) Math.ceil(Math.max(topLeft.y(), bottomRight.y()));
        context.enableScissor(screenLeft, screenTop, Math.max(screenLeft + 1, screenRight), Math.max(screenTop + 1, screenBottom));
    }

    private static void putInlineBounds(DrawContext context, String key, UUID attachmentId, RichChatAttachment attachment, int x, int y, int width, int height) {
        int[] rect = clippedInlineRect(context, x, y, width, height);
        if (rect == null) {
            return;
        }
        BOUNDS.put(key, new MediaBounds(attachmentId, attachment, rect[0], rect[1], rect[2], rect[3]));
    }

    private static int[] clippedInlineRect(DrawContext context, int x, int y, int width, int height) {
        if (context == null || width <= 0 || height <= 0) {
            return null;
        }
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        Vector4f topLeft = new Vector4f(x, y, 0.0F, 1.0F);
        Vector4f bottomRight = new Vector4f(x + width, y + height, 0.0F, 1.0F);
        topLeft.mul(matrix);
        bottomRight.mul(matrix);
        int screenX = Math.round(Math.min(topLeft.x(), bottomRight.x()));
        int screenY = Math.round(Math.min(topLeft.y(), bottomRight.y()));
        int screenRight = Math.round(Math.max(topLeft.x(), bottomRight.x()));
        int screenBottom = Math.round(Math.max(topLeft.y(), bottomRight.y()));
        int viewportTop = RichChatRenderContext.currentScreenChatViewportTop();
        int viewportBottom = RichChatRenderContext.currentScreenChatViewportBottom() + EXTRA_RENDER_BOTTOM;
        int clippedTop = Math.max(screenY, viewportTop);
        int clippedBottom = Math.min(screenBottom, viewportBottom);
        if (clippedBottom <= clippedTop) {
            return null;
        }
        return new int[]{
                screenX,
                clippedTop,
                Math.max(1, screenRight - screenX),
                Math.max(1, clippedBottom - clippedTop)
        };
    }

    private record PreviewSize(int width, int height) {
    }

    private record FocusDimensions(int width, int height) {
    }

    private record MediaBounds(UUID attachmentId, RichChatAttachment attachment, int x, int y, int width, int height) {
    }

    private record SpoilerBounds(String key, int x, int y, int width, int height, int lineX) {
    }

    private record MarkerMatch(int start, String marker) {
    }

    private record HeaderStyle(String content, Style style, float scale, int yOffset, String leadingWhitespace) {
    }

    private record SubtextStyle(String content) {
    }

    private record QuoteStyle(String leadingWhitespace, String content) {
    }

    private record LinkBounds(String target, int x, int y, int width, int height) {
    }

    private record HiddenAttachmentBounds(String occurrenceId, RichChatAttachmentType type, int x, int y, int width, int height) {
        private boolean contains(double mouseX, double mouseY) { return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height + 2; }
    }

    private record RenderedSegment(String text, Style style, String spoilerKey, boolean obfuscated, boolean inlineCode, String linkTarget) {
    }

    private static void recordUsernameHover(DrawContext context, TextRenderer renderer, String visibleLine, int x, int y) {
        if (context == null || renderer == null || visibleLine == null || visibleLine.isBlank()) {
            return;
        }
        int end = visibleLine.indexOf("> ");
        if (!visibleLine.startsWith("<") || end <= 1) {
            return;
        }
        String timestamp = RichChatTimestampBridge.timestampForLine(visibleLine);
        if (timestamp.isBlank()) {
            return;
        }
        String prefix = visibleLine.substring(0, end + 2);
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        Vector4f topLeft = new Vector4f(x, y, 0.0F, 1.0F);
        Vector4f bottomRight = new Vector4f(x + renderer.getWidth(prefix), y + renderer.fontHeight + 2, 0.0F, 1.0F);
        topLeft.mul(matrix);
        bottomRight.mul(matrix);
        USERNAME_HOVERS.put(
                firstLineKey(visibleLine),
                new UsernameHover(
                        Math.round(Math.min(topLeft.x(), bottomRight.x())),
                        Math.round(Math.min(topLeft.y(), bottomRight.y())),
                        Math.max(1, Math.round(Math.abs(bottomRight.x() - topLeft.x()))),
                        Math.max(1, Math.round(Math.abs(bottomRight.y() - topLeft.y()))),
                        timestamp,
                        RichChatTimestampBridge.inlineAllowed(visibleLine)
                )
        );
    }

    public static void renderChatHoverTooltip(DrawContext context, int mouseX, int mouseY) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (context == null || client == null || client.textRenderer == null) {
            return;
        }
        for (HoverTooltip hover : HOVER_TOOLTIPS.values()) {
            if (mouseX >= hover.x() && mouseX <= hover.x() + hover.width() && mouseY >= hover.y() && mouseY <= hover.y() + hover.height()) {
                context.drawTooltip(client.textRenderer, Text.literal(hover.text()), mouseX, mouseY);
                return;
            }
        }
        for (LinkBounds link : LINK_BOUNDS.values()) {
            if (mouseX >= link.x() && mouseX <= link.x() + link.width() && mouseY >= link.y() && mouseY <= link.y() + link.height()) {
                String target = link.target();
                if (isCommandLink(target)) {
                    String command = target.startsWith("command:") ? target.substring("command:".length()).trim() : target;
                    java.util.List<Text> lines = new java.util.ArrayList<>();
                    lines.add(Text.literal("Masked command"));
                    for (com.spirit.koil.api.chat.input.KoilCommandAnalysisService.StyledChunk chunk : com.spirit.koil.api.chat.input.KoilCommandAnalysisService.highlightLine(client, command)) {
                        lines.add(Text.literal(chunk.text()).setStyle(chunk.style().withColor(chunk.color() & 0x00FFFFFF)));
                    }
                    context.drawTooltip(client.textRenderer, lines, mouseX, mouseY);
                } else {
                    context.drawTooltip(client.textRenderer, java.util.List.of(Text.literal("Masked link"), Text.literal(target)), mouseX, mouseY);
                }
                return;
            }
        }
        for (UsernameHover hover : USERNAME_HOVERS.values()) {
            if (hover.inlineShown()) {
                continue;
            }
            if (mouseX >= hover.x() && mouseX <= hover.x() + hover.width() && mouseY >= hover.y() && mouseY <= hover.y() + hover.height()) {
                context.drawTooltip(client.textRenderer, List.of(Text.literal("Sent: " + hover.timestamp())), mouseX, mouseY);
                return;
            }
        }
    }

    private static String firstLineKey(String visibleLine) {
        int newline = visibleLine.indexOf('\n');
        return newline >= 0 ? visibleLine.substring(0, newline) : visibleLine;
    }

    private static int lineAlpha(int color) {
        return Math.max(0, Math.min(255, (color >>> 24) & 0xFF));
    }

    private static int withMultipliedAlpha(int argbColor, int alpha) {
        int red = (argbColor >>> 16) & 0xFF;
        int green = (argbColor >>> 8) & 0xFF;
        int blue = argbColor & 0xFF;
        int baseAlpha = (argbColor >>> 24) & 0xFF;
        int multiplied = Math.max(0, Math.min(255, Math.round(baseAlpha * (alpha / 255.0F))));
        return (multiplied << 24) | (red << 16) | (green << 8) | blue;
    }

    private static void drawTextureWithAlpha(DrawContext context, Identifier texture, int x, int y, int u, int v, int width, int height, int textureWidth, int textureHeight, int alpha) {
        float normalizedAlpha = Math.max(0.0F, Math.min(1.0F, alpha / 255.0F));
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, normalizedAlpha);
        context.drawTexture(texture, x, y, u, v, width, height, textureWidth, textureHeight);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawTextureWithAlpha(DrawContext context, Identifier texture, int x, int y, int width, int height, int u, int v, int regionWidth, int regionHeight, int textureWidth, int textureHeight, int alpha) {
        float normalizedAlpha = Math.max(0.0F, Math.min(1.0F, alpha / 255.0F));
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, normalizedAlpha);
        context.drawTexture(texture, x, y, width, height, u, v, regionWidth, regionHeight, textureWidth, textureHeight);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private enum ButtonAction {
        MENU,
        VIEW,
        PLAY_PAUSE,
        STOP,
        SEEK,
        PINNED_VIDEO_PLAY_PAUSE,
        PINNED_VIDEO_STOP,
        PINNED_VIDEO_SEEK
    }

    private enum CodeButtonAction {
        MENU
    }

    private record ButtonKey(String occurrenceId, ButtonAction action) {
    }

    private record ButtonBounds(ButtonAction action, RichChatAttachment attachment, int x, int y, int width, int height) {
    }

    private record CodeButtonBounds(CodeButtonAction action, String blockId, int x, int y, int width, int height) {
    }

    private record UsernameHover(int x, int y, int width, int height, String timestamp, boolean inlineShown) {
    }

    private record HoverTooltip(int x, int y, int width, int height, String text) {
    }

    private static final class SeekDrag {
        private final RichChatAttachment attachment;
        private final int x;
        private final int width;
        private double lastMouseX;
        private boolean applied;
        private float lastAppliedProgress;

        private SeekDrag(RichChatAttachment attachment, int x, int width) {
            this.attachment = attachment;
            this.x = x;
            this.width = width;
        }

        private RichChatAttachment attachment() {
            return attachment;
        }

        private int x() {
            return x;
        }

        private int width() {
            return width;
        }
    }

    private static final class FocusState {
        private final RichChatAttachment attachment;
        private float zoom = 1.0F;
        private float fitZoom = 1.0F;
        private float offsetX;
        private float offsetY;
        private boolean initialized;
        private int screenWidth;
        private int screenHeight;
        private boolean dragging;
        private boolean pendingClickClose;
        private double lastMouseX;
        private double lastMouseY;
        private double dragDistance;
        private int mediaX;
        private int mediaY;
        private int mediaWidth;
        private int mediaHeight;

        private FocusState(RichChatAttachment attachment) {
            this.attachment = attachment;
        }

        private RichChatAttachment attachment() {
            return attachment;
        }

        private void center(ImageTexture texture, int screenWidth, int screenHeight) {
            this.offsetX = (screenWidth - texture.width() * this.zoom) / 2.0F;
            this.offsetY = (screenHeight - texture.height() * this.zoom) / 2.0F;
        }

        private void center(int sourceWidth, int sourceHeight, int screenWidth, int screenHeight) {
            this.offsetX = (screenWidth - sourceWidth * this.zoom) / 2.0F;
            this.offsetY = (screenHeight - sourceHeight * this.zoom) / 2.0F;
        }

        private boolean containsMedia(double mouseX, double mouseY) {
            return mouseX >= this.mediaX && mouseX <= this.mediaX + Math.max(1, this.mediaWidth)
                    && mouseY >= this.mediaY && mouseY <= this.mediaY + Math.max(1, this.mediaHeight);
        }
    }
}
