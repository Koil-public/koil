package com.spirit.koil.chat.internal;

import com.spirit.client.gui.PopupMenu;
import com.spirit.koil.chat.internal.latex.RichChatLatexTextureCache;
import com.spirit.koil.chat.internal.upload.RichChatAttachmentRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RichChatPrivateMessageBridge {
    private static final int MAX_CONVERSATION = 256;
    private static final int MAX_TAGGED_LINES = 2048;
    private static final char MARKER_START = '\uE350';
    private static final char MARKER_END = '\uE351';
    private static final int UNREAD_COLOR = 0xFFE06A6A;
    private static final int HINT_COLOR = 0xE0E0E0;
    private static final int PRIVATE_TEXT_COLOR = 0xFFFFFF;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern OUTGOING_WHISPER = Pattern.compile("^You whisper to (.+?):\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INCOMING_WHISPER = Pattern.compile("^(.+?) whispers to you:\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTGOING_TO = Pattern.compile("^To (.+?):\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INCOMING_FROM = Pattern.compile("^From (.+?):\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKET_FROM = Pattern.compile("^\\[From (.+?)]\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BRACKET_TO = Pattern.compile("^\\[To (.+?)]\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTENTION_MESSAGE = Pattern.compile("^\\[Attention]\\s*<([^>]+)>\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    private static final Deque<ConversationEntry> CONVERSATION = new ArrayDeque<>();
    private static final Map<String, Long> UNREAD_PARTNERS = new ConcurrentHashMap<>();
    private static final Map<String, TaggedLine> TAGGED_LINES = new LinkedHashMap<String, TaggedLine>(MAX_TAGGED_LINES, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TaggedLine> eldest) {
            return size() > MAX_TAGGED_LINES;
        }
    };
    private static final AtomicLong TAG_COUNTER = new AtomicLong();
    private static volatile boolean filterEnabled;
    private static volatile boolean showMainWhenFiltering = true;
    private static volatile String targetPlayer = "";
    private static volatile boolean overlayRender;

    private RichChatPrivateMessageBridge() {
    }

    public static Text observeAndRewrite(Text message) {
        if (message == null) {
            return null;
        }
        String visible = message.getString();
        if (visible == null || visible.isBlank() || containsMarker(visible)) {
            return message;
        }
        String sourceVisible = canonicalPrivateSource(visible);
        ParsedAttentionMessage attention = parseAttention(sourceVisible);
        if (attention != null) {
            String normalizedDisplay = normalizedAttentionDisplay(attention);
            String timestamp = RichChatTimestampBridge.timestampForLine(normalizedDisplay);
            if (timestamp.isBlank()) {
                timestamp = LocalTime.now().format(TIME_FORMAT);
            }
            rememberConversation(attention.partner(), normalizedDisplay, timestamp, true);
            RichChatTimestampBridge.rememberVisibleLine(normalizedDisplay, timestamp);
            if (!isTarget(attention.partner()) || !filterEnabled) {
                UNREAD_PARTNERS.put(normalizeName(attention.partner()), System.currentTimeMillis());
            } else {
                UNREAD_PARTNERS.remove(normalizeName(attention.partner()));
            }
            String wrapped = RichChatBodyWrapFormatter.format(
                    Text.literal(normalizedDisplay),
                    RichChatRowType.PRIVATE_MESSAGE
            ).getString();
            return Text.literal(tagLines(wrapped, sourceVisible, attention.partner(), true));
        }
        ParsedPrivateMessage parsed = parse(sourceVisible);
        if (parsed == null) {
            return message;
        }
        String normalizedDisplay = normalizedDisplay(parsed, sourceVisible);
        String timestamp = RichChatTimestampBridge.timestampForLine(normalizedDisplay);
        if (timestamp.isBlank()) {
            timestamp = LocalTime.now().format(TIME_FORMAT);
        }
        rememberConversation(parsed.partner(), normalizedDisplay, timestamp, parsed.incoming());
        RichChatTimestampBridge.rememberVisibleLine(normalizedDisplay, timestamp);
        if (parsed.incoming()) {
            if (!isTarget(parsed.partner()) || !filterEnabled) {
                UNREAD_PARTNERS.put(normalizeName(parsed.partner()), System.currentTimeMillis());
            } else {
                UNREAD_PARTNERS.remove(normalizeName(parsed.partner()));
            }
        }
        String currentView = rebuildPrivateMessageVisibleText(splitLines(sourceVisible), parsed, filterEnabled && isTarget(parsed.partner()));
        return Text.literal(tagLines(currentView, sourceVisible, parsed.partner()));
    }

    public static VisualStyle visualStyle(String visibleLine) {
        if (overlayRender) {
            return VisualStyle.NORMAL;
        }
        if (isAttentionMessage(visibleLine)) {
            return VisualStyle.NORMAL;
        }
        TaggedLookup tagged = tagged(visibleLine);
        if (tagged != null) {
            if (!filterEnabled) {
                return VisualStyle.DIM;
            }
            if (!showMainWhenFiltering) {
                return isTarget(tagged.taggedLine().partner()) ? VisualStyle.NORMAL : VisualStyle.HIDE;
            }
            return isTarget(tagged.taggedLine().partner()) ? VisualStyle.NORMAL : VisualStyle.DIM;
        }
        ParsedPrivateMessage parsed = parse(visibleLine);
        if (parsed != null) {
            if (!filterEnabled) {
                return VisualStyle.DIM;
            }
            if (!showMainWhenFiltering) {
                return isTarget(parsed.partner()) ? VisualStyle.NORMAL : VisualStyle.HIDE;
            }
            return isTarget(parsed.partner()) ? VisualStyle.NORMAL : VisualStyle.DIM;
        }
        if (!filterEnabled) {
            return VisualStyle.NORMAL;
        }
        if (!showMainWhenFiltering) {
            return VisualStyle.HIDE;
        }
        return VisualStyle.DIM;
    }

    public static String displayText(String visibleLine) {
        if (visibleLine == null || visibleLine.isBlank() || overlayRender) {
            return stripMarker(visibleLine);
        }
        TaggedLookup tagged = tagged(visibleLine);
        if (tagged != null) {
            if (tagged.taggedLine().attention()) {
                return tagged.strippedLine();
            }
            ParsedPrivateMessage parsed = parse(tagged.taggedLine().sourceMessage());
            if (filterEnabled && isTarget(tagged.taggedLine().partner())) {
                UNREAD_PARTNERS.remove(normalizeName(tagged.taggedLine().partner()));
            }
            if (parsed != null) {
                String rebuilt = rebuildPrivateMessageVisibleText(
                        splitLines(tagged.taggedLine().sourceMessage()),
                        parsed,
                        filterEnabled && isTarget(parsed.partner())
                );
                return rebuiltLineAt(rebuilt, tagged.taggedLine().lineIndex(), tagged.strippedLine());
            }
            return stripMarker(tagged.strippedLine());
        }
        ParsedAttentionMessage attention = parseAttention(stripMarker(visibleLine));
        if (attention != null) {
            return normalizedAttentionDisplay(attention);
        }
        if (!filterEnabled) {
            return stripMarker(visibleLine);
        }
        ParsedPrivateMessage parsed = parse(canonicalPrivateSource(stripMarker(visibleLine)));
        if (parsed == null || !isTarget(parsed.partner())) {
            return stripMarker(visibleLine);
        }
        UNREAD_PARTNERS.remove(normalizeName(parsed.partner()));
        return normalizedDisplay(parsed, canonicalPrivateSource(stripMarker(visibleLine)));
    }

    public static String rebuildVisibleText(String visible) {
        if (visible == null || visible.isBlank()) {
            return visible == null ? "" : visible;
        }
        TaggedLookup tagged = tagged(visible);
        if (tagged != null) {
            if (tagged.taggedLine().attention()) {
                ParsedAttentionMessage attention = parseAttention(tagged.taggedLine().sourceMessage());
                return attention == null ? stripMarker(visible) : normalizedAttentionDisplay(attention);
            }
            ParsedPrivateMessage parsed = parse(tagged.taggedLine().sourceMessage());
            if (parsed != null) {
                return rebuildPrivateMessageVisibleText(splitLines(tagged.taggedLine().sourceMessage()), parsed, filterEnabled && isTarget(parsed.partner()));
            }
        }
        String[] rawLines = splitLines(visible);
        String[] strippedLines = new String[rawLines.length];
        for (int i = 0; i < rawLines.length; i++) {
            strippedLines[i] = stripMarker(rawLines[i]);
        }
        String strippedVisible = canonicalPrivateSource(String.join("\n", strippedLines));
        ParsedPrivateMessage parsed = parse(strippedVisible);
        if (parsed != null) {
            return rebuildPrivateMessageVisibleText(splitLines(strippedVisible), parsed);
        }
        StringBuilder builder = new StringBuilder(visible.length());
        for (int i = 0; i < rawLines.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(displayText(rawLines[i]));
        }
        return builder.toString();
    }

    public static Text rebuildMessageForRefresh(Text original) {
        if (original == null) {
            return null;
        }
        String visible = original.getString();
        if (visible == null || visible.isBlank()) {
            return original;
        }
        TaggedLookup tagged = tagged(visible);
        if (tagged != null) {
            if (tagged.taggedLine().attention()) {
                ParsedAttentionMessage attention = parseAttention(tagged.taggedLine().sourceMessage());
                if (attention == null) {
                    return original;
                }
                String wrapped = RichChatBodyWrapFormatter.format(
                        Text.literal(normalizedAttentionDisplay(attention)),
                        RichChatRowType.PRIVATE_MESSAGE
                ).getString();
                return Text.literal(tagLines(wrapped, tagged.taggedLine().sourceMessage(), attention.partner(), true));
            }
            ParsedPrivateMessage parsed = parse(tagged.taggedLine().sourceMessage());
            if (parsed != null) {
                String rebuilt = rebuildPrivateMessageVisibleText(splitLines(tagged.taggedLine().sourceMessage()), parsed, filterEnabled && isTarget(parsed.partner()));
                return Text.literal(tagLines(rebuilt, tagged.taggedLine().sourceMessage(), parsed.partner()));
            }
        }
        String sourceVisible = canonicalPrivateSource(stripMarker(visible));
        ParsedPrivateMessage parsed = parse(sourceVisible);
        if (parsed != null) {
            String rebuilt = rebuildPrivateMessageVisibleText(splitLines(sourceVisible), parsed, filterEnabled && isTarget(parsed.partner()));
            return Text.literal(tagLines(rebuilt, sourceVisible, parsed.partner()));
        }
        String rebuilt = rebuildVisibleText(visible);
        return rebuilt.equals(visible) ? original : Text.literal(rebuilt);
    }

    public static String stripVisibleMarkersForLayout(String visible) {
        return stripMarker(visible);
    }

    public static int dimColor(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        red = Math.max(18, Math.round(red * 0.34F));
        green = Math.max(18, Math.round(green * 0.34F));
        blue = Math.max(18, Math.round(blue * 0.34F));
        alpha = Math.max(82, Math.round(alpha * 0.74F));
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    public static int dimOverlayColor() {
        return 0x8A000000;
    }

    public static boolean filterEnabled() {
        return filterEnabled;
    }

    public static boolean showMainWhenFiltering() {
        return showMainWhenFiltering;
    }

    public static boolean isAttentionMessage(String visible) {
        if (visible == null || visible.isBlank()) {
            return false;
        }
        TaggedLookup tagged = tagged(visible);
        if (tagged != null && tagged.taggedLine().attention()) {
            return true;
        }
        return parseAttention(canonicalPrivateSource(stripMarker(visible))) != null;
    }

    public static boolean shouldIncludeInNativePrivateView(Text message) {
        if (!filterEnabled || showMainWhenFiltering || message == null) {
            return true;
        }
        if (!hasTarget()) {
            return false;
        }
        String visible = message.getString();
        TaggedLookup tagged = tagged(visible);
        if (tagged != null) {
            return isTarget(tagged.taggedLine().partner());
        }
        ParsedAttentionMessage attention = parseAttention(canonicalPrivateSource(stripMarker(visible)));
        if (attention != null) {
            return isTarget(attention.partner());
        }
        ParsedPrivateMessage parsed = parse(canonicalPrivateSource(stripMarker(visible)));
        return parsed != null && isTarget(parsed.partner());
    }

    public static String targetPlayer() {
        return targetPlayer;
    }

    public static boolean hasTarget() {
        return targetPlayer != null && !targetPlayer.isBlank();
    }

    public static void toggleFilter() {
        if (!filterEnabled && !hasTarget()) {
            selectFallbackTarget();
        }
        filterEnabled = !filterEnabled;
        if (filterEnabled && hasTarget()) {
            UNREAD_PARTNERS.remove(normalizeName(targetPlayer));
        }
        refreshChatHud();
    }

    public static void toggleShowMainWhenFiltering() {
        showMainWhenFiltering = !showMainWhenFiltering;
        if (!showMainWhenFiltering) {
            if (!hasTarget()) {
                selectFallbackTarget();
            }
            filterEnabled = true;
            if (hasTarget()) {
                UNREAD_PARTNERS.remove(normalizeName(targetPlayer));
            }
        }
        refreshChatHud();
    }

    public static void selectTarget(String playerName) {
        targetPlayer = playerName == null ? "" : playerName.trim();
        if (!targetPlayer.isBlank()) {
            UNREAD_PARTNERS.remove(normalizeName(targetPlayer));
        }
        refreshChatHud();
    }

    public static boolean shouldRouteOutgoing(String chatText) {
        if (!filterEnabled) {
            return false;
        }
        String trimmed = chatText == null ? "" : chatText.trim();
        return !trimmed.isEmpty() && !trimmed.startsWith("/");
    }

    public static String routeOutgoing(String chatText) {
        String trimmed = chatText == null ? "" : chatText.trim();
        return "msg " + targetPlayer + " " + trimmed;
    }

    public static List<PopupMenu.MenuEntry> menuEntries(MinecraftClient client) {
        List<PopupMenu.MenuEntry> entries = new ArrayList<>();
        entries.add(new PopupMenu.MenuEntry("pm_filter_toggle", "Private filter: " + (filterEnabled ? "On" : "Off")));
        entries.add(new PopupMenu.MenuEntry("pm_show_main_toggle", "Show global chat: " + (showMainWhenFiltering ? "On" : "Off")));
        String currentTarget = hasTarget() ? targetPlayer : "none";
        entries.add(new PopupMenu.MenuEntry("pm_target_header", "with: " + currentTarget));
        return entries;
    }

    public static List<PopupMenu.MenuEntry> targetMenuEntries(MinecraftClient client) {
        List<PopupMenu.MenuEntry> entries = new ArrayList<>();
        for (String player : availableTargets(client)) {
            String normalized = normalizeName(player);
            boolean selected = isTarget(player);
            boolean unread = UNREAD_PARTNERS.containsKey(normalized) && !selected;
            if (unread) {
                entries.add(new PopupMenu.MenuEntry("pm_target:" + player, player, 0, "", UNREAD_COLOR, "•"));
            } else {
                entries.add(new PopupMenu.MenuEntry("pm_target:" + player, player));
            }
        }
        return entries;
    }

    public static boolean handleMenuAction(String actionId) {
        if (actionId == null || actionId.isBlank() || "pm_target_header".equals(actionId)) {
            return false;
        }
        if ("pm_filter_toggle".equals(actionId)) {
            toggleFilter();
            return true;
        }
        if ("pm_show_main_toggle".equals(actionId)) {
            toggleShowMainWhenFiltering();
            return true;
        }
        if (actionId.startsWith("pm_target:")) {
            selectTarget(actionId.substring("pm_target:".length()));
            if (!filterEnabled) {
                filterEnabled = true;
                refreshChatHud();
            }
            return true;
        }
        return false;
    }

    public static List<Text> buttonTooltip() {
        List<Text> lines = new ArrayList<>();
        lines.add(Text.literal("Private messages"));
        lines.add(Text.literal("Filter: " + (filterEnabled ? "On" : "Off")));
        lines.add(Text.literal("Show global: " + (showMainWhenFiltering ? "On" : "Off")));
        lines.add(Text.literal("Target: " + (hasTarget() ? targetPlayer : "none")));
        long unreadCount = UNREAD_PARTNERS.keySet().stream().filter(name -> !name.equals(normalizeName(targetPlayer))).count();
        if (unreadCount > 0) {
            lines.add(Text.literal("Unread PM players: " + unreadCount));
        }
        lines.add(Text.literal("Left-click to open PM controls."));
        return lines;
    }

    public static void renderPrivateOnlyOverlay(DrawContext context, TextRenderer renderer) {
        if (!filterEnabled || showMainWhenFiltering || !hasTarget() || context == null || renderer == null) {
            return;
        }
        List<String> renderLines = conversationLines(renderer);
        int lineHeight = Math.max(1, RichChatLatexTextureCache.currentChatLineHeight());
        int x = 4;
        int y = RichChatLatexTextureCache.currentChatViewportBottom() - lineHeight;
        int top = RichChatLatexTextureCache.currentChatViewportTop();
        int bottom = RichChatLatexTextureCache.currentChatViewportBottom();
        int width = Math.max(1, RichChatLatexTextureCache.currentChatContentWidth());
        int background = vanillaChatBackgroundColor();
        overlayRender = true;
        try {
            context.getMatrices().push();
            context.getMatrices().translate(0.0F, 0.0F, 9000.0F);
            // ChatHud keeps its own native translucent background. Painting a
            // second rectangle here made private-only mode visibly darker and
            // narrower on the right than normal chat.
            if (renderLines.isEmpty()) {
                RichChatAttachmentRenderer.renderOrDrawText(context, renderer, Text.literal("<PM> Select a player or wait for a message.").asOrderedText(), x, y, HINT_COLOR);
                return;
            }
            for (int i = renderLines.size() - 1; i >= 0 && y >= top; i--) {
                RichChatAttachmentRenderer.renderOrDrawText(context, renderer, Text.literal(renderLines.get(i)).asOrderedText(), x, y, PRIVATE_TEXT_COLOR);
                y -= lineHeight;
            }
        } finally {
            context.getMatrices().pop();
            overlayRender = false;
        }
    }

    private static int vanillaChatBackgroundColor() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) {
            return 0x55000000;
        }
        try {
            double opacity = client.options.getTextBackgroundOpacity().getValue();
            int alpha = Math.max(0, Math.min(255, (int) Math.round(opacity * 255.0D)));
            return alpha << 24;
        } catch (RuntimeException ignored) {
            return 0x55000000;
        }
    }

    private static List<String> conversationLines(TextRenderer renderer) {
        List<String> lines = new ArrayList<>();
        String target = normalizeName(targetPlayer);
        synchronized (CONVERSATION) {
            for (ConversationEntry entry : CONVERSATION) {
                if (!normalizeName(entry.partner()).equals(target)) {
                    continue;
                }
                RichChatTimestampBridge.rememberVisibleLine(entry.displayLine(), entry.timestamp());
                String formatted = RichChatBodyWrapFormatter.format(Text.literal(entry.displayLine()), RichChatRowType.PRIVATE_MESSAGE).getString();
                for (String line : formatted.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
                    lines.add(line);
                }
            }
        }
        int maxLines = Math.max(1, (RichChatLatexTextureCache.currentChatViewportBottom() - RichChatLatexTextureCache.currentChatViewportTop()) / Math.max(1, RichChatLatexTextureCache.currentChatLineHeight()));
        if (lines.size() <= maxLines) {
            return lines;
        }
        return new ArrayList<>(lines.subList(lines.size() - maxLines, lines.size()));
    }

    private static void rememberConversation(String partner, String displayLine, String timestamp, boolean incoming) {
        synchronized (CONVERSATION) {
            CONVERSATION.addLast(new ConversationEntry(partner, displayLine, timestamp, incoming));
            while (CONVERSATION.size() > MAX_CONVERSATION) {
                CONVERSATION.removeFirst();
            }
        }
    }

    private static boolean isTarget(String partner) {
        return normalizeName(partner).equals(normalizeName(targetPlayer));
    }

    private static String normalizedDisplay(ParsedPrivateMessage parsed, String originalVisible) {
        if (parsed == null) {
            return "";
        }
        return rebuildPrivateMessageVisibleText(splitLines(originalVisible == null ? "" : originalVisible), parsed, true);
    }

    private static String tagLines(String visible, String sourceVisible, String partner) {
        return tagLines(visible, sourceVisible, partner, false);
    }

    private static String tagLines(String visible, String sourceVisible, String partner, boolean attention) {
        String[] visibleLines = splitLines(visible);
        StringBuilder builder = new StringBuilder(visible.length() + visibleLines.length * 20);
        for (int i = 0; i < visibleLines.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(marker(storeTaggedLine(sourceVisible, partner, i, attention)));
            builder.append(visibleLines[i]);
        }
        return builder.toString();
    }

    public static boolean isPrivateMessageLine(String visibleLine) {
        if (visibleLine == null || visibleLine.isBlank()) {
            return false;
        }
        TaggedLookup tagged = tagged(visibleLine);
        return (tagged != null && !tagged.taggedLine().attention()) || parse(visibleLine) != null;
    }

    public static boolean isSelectedConversationLine(String visibleLine) {
        if (!filterEnabled || !hasTarget() || visibleLine == null || visibleLine.isBlank()) {
            return false;
        }
        TaggedLookup tagged = tagged(visibleLine);
        if (tagged != null) {
            ParsedPrivateMessage parsed = parse(tagged.taggedLine().sourceMessage());
            return parsed != null && isTarget(parsed.partner());
        }
        ParsedPrivateMessage parsed = parse(canonicalPrivateSource(stripMarker(visibleLine)));
        return parsed != null && isTarget(parsed.partner());
    }

    public static boolean shouldItalicizeDimmedLine(String visibleLine) {
        return isPrivateMessageLine(visibleLine);
    }

    private static String normalizedSpeakerName(ParsedPrivateMessage parsed) {
        if (parsed == null) {
            return "PM";
        }
        String speaker = parsed.incoming() ? parsed.partner() : localPlayerName();
        if (speaker == null || speaker.isBlank()) {
            speaker = parsed.partner();
        }
        if (speaker == null || speaker.isBlank()) {
            speaker = "PM";
        }
        return speaker.trim();
    }

    private static void refreshChatHud() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.inGameHud == null) {
            return;
        }
        ChatHud chatHud = client.inGameHud.getChatHud();
        if (chatHud instanceof ChatHudRefreshBridge bridge) {
            bridge.koil$refreshPrivateMessageView();
        }
    }

    private static String rebuildPrivateMessageVisibleText(String[] lines, ParsedPrivateMessage parsed) {
        return rebuildPrivateMessageVisibleText(lines, parsed, filterEnabled && isTarget(parsed.partner()));
    }

    private static String rebuildPrivateMessageVisibleText(String[] lines, ParsedPrivateMessage parsed, boolean filteredView) {
        if (parsed == null) {
            return "";
        }
        String[] safeLines = lines == null ? new String[0] : lines;
        String[] bodyLines = bodyLines(safeLines, parsed);
        bodyLines = collapseWrappedLongToken(bodyLines);
        String prefix = filteredView ? "<" + normalizedSpeakerName(parsed) + "> " : parsed.prefix();
        String indent = LocalMultilineChatBridge.indentForPrefix(prefix);
        if (bodyLines.length == 0) {
            return prefix;
        }
        StringBuilder builder = new StringBuilder(Math.max(32, String.join("\n", safeLines).length() + 16));
        builder.append(prefix).append(bodyLines[0]);
        for (int i = 1; i < bodyLines.length; i++) {
            builder.append('\n').append(indent).append(bodyLines[i]);
        }
        return RichChatBodyWrapFormatter.format(Text.literal(builder.toString()), RichChatRowType.PRIVATE_MESSAGE).getString();
    }

    private static String[] collapseWrappedLongToken(String[] bodyLines) {
        if (bodyLines == null || bodyLines.length <= 1) {
            return bodyLines == null ? new String[0] : bodyLines;
        }
        // Minecraft can hand us an empty continuation row between two pieces
        // of an already soft-wrapped PM.  Keeping it makes the second layout
        // pass faithfully render a blank line every other row.  It is not user
        // authored content: explicit newlines arrive as one parsed body, while
        // these rows are only produced by ChatHud's visual wrapping.
        List<String> nonEmptyLines = new ArrayList<>(bodyLines.length);
        for (String line : bodyLines) {
            if (line != null && !line.isEmpty()) {
                nonEmptyLines.add(line);
            }
        }
        if (nonEmptyLines.isEmpty()) {
            return bodyLines;
        }
        boolean seenContent = false;
        for (String line : nonEmptyLines) {
            for (int i = 0; i < line.length(); i++) {
                if (Character.isWhitespace(line.charAt(i))) {
                    return bodyLines;
                }
            }
            seenContent = true;
        }
        if (!seenContent) {
            return bodyLines;
        }
        StringBuilder joined = new StringBuilder();
        for (String line : nonEmptyLines) {
            joined.append(line);
        }
        return new String[]{joined.toString()};
    }

    private static String[] bodyLines(String[] lines, ParsedPrivateMessage parsed) {
        if (parsed == null) {
            return new String[0];
        }
        List<String> body = new ArrayList<>();
        String originalIndent = LocalMultilineChatBridge.indentForPrefix(parsed.prefix());
        String normalizedPrefix = "<" + normalizedSpeakerName(parsed) + "> ";
        String normalizedIndent = LocalMultilineChatBridge.indentForPrefix(normalizedPrefix);
        if (lines != null && lines.length > 0) {
            String firstLine = lines[0];
            if (firstLine != null && firstLine.startsWith(normalizedPrefix)) {
                firstLine = firstLine.substring(normalizedPrefix.length());
            }
            String firstBody = stripLeadingPrefix(firstLine, parsed.prefix());
            if (!firstBody.isEmpty()) {
                body.add(firstBody);
            }
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line != null && line.startsWith(normalizedIndent)) {
                    line = line.substring(normalizedIndent.length());
                }
                String continuation = stripContinuationPrefix(line, originalIndent);
                if (!continuation.isEmpty() || !body.isEmpty()) {
                    body.add(continuation);
                }
            }
        }
        if (body.isEmpty()) {
            String parsedBody = parsed.body() == null ? "" : parsed.body();
            return parsedBody.isEmpty() ? new String[0] : new String[]{parsedBody};
        }
        return body.toArray(String[]::new);
    }

    private static List<String> availableTargets(MinecraftClient client) {
        Set<String> names = new LinkedHashSet<>();
        synchronized (CONVERSATION) {
            for (ConversationEntry entry : CONVERSATION) {
                if (entry.partner() != null && !entry.partner().isBlank()) {
                    names.add(entry.partner());
                }
            }
        }
        if (client != null && client.getNetworkHandler() != null) {
            String localName = localPlayerName();
            Collection<PlayerListEntry> players = client.getNetworkHandler().getPlayerList();
            if (players != null) {
                players.stream()
                        .map(PlayerListEntry::getProfile)
                        .filter(profile -> profile != null && profile.getName() != null && !profile.getName().isBlank())
                        .map(profile -> profile.getName())
                        .filter(name -> !normalizeName(name).equals(normalizeName(localName)))
                        .forEach(names::add);
            }
        }
        return names.stream()
                .sorted(Comparator
                        .comparing((String name) -> !UNREAD_PARTNERS.containsKey(normalizeName(name)))
                        .thenComparing(name -> !normalizeName(name).equals(normalizeName(targetPlayer)))
                        .thenComparing(name -> name.toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static void selectFallbackTarget() {
        String fallback = availableTargets(MinecraftClient.getInstance()).stream().findFirst().orElse("");
        if (!fallback.isBlank()) {
            targetPlayer = fallback;
        }
    }

    private static ParsedPrivateMessage parse(String visible) {
        if (visible == null || visible.isBlank()) {
            return null;
        }
        String firstLine = firstLine(canonicalPrivateSource(visible));
        return parseFirstLine(firstLine);
    }

    private static ParsedAttentionMessage parseAttention(String visible) {
        if (visible == null || visible.isBlank()) {
            return null;
        }
        Matcher matcher = ATTENTION_MESSAGE.matcher(firstLine(visible));
        if (!matcher.matches()) {
            return null;
        }
        String partner = matcher.group(1) == null ? "" : matcher.group(1).trim();
        String body = matcher.group(2) == null ? "" : matcher.group(2).trim();
        return partner.isBlank() ? null : new ParsedAttentionMessage(partner, body);
    }

    private static String normalizedAttentionDisplay(ParsedAttentionMessage attention) {
        if (attention == null) {
            return "";
        }
        String prefix = "<" + attention.partner() + ">";
        return attention.body().isBlank() ? prefix : prefix + " " + attention.body();
    }

    private static ParsedPrivateMessage parseFirstLine(String firstLine) {
        if (firstLine == null || firstLine.isBlank()) {
            return null;
        }
        ParsedPrivateMessage parsed = parse(firstLine, OUTGOING_WHISPER, false);
        if (parsed != null) {
            return parsed;
        }
        parsed = parse(firstLine, INCOMING_WHISPER, true);
        if (parsed != null) {
            return parsed;
        }
        parsed = parse(firstLine, OUTGOING_TO, false);
        if (parsed != null) {
            return parsed;
        }
        parsed = parse(firstLine, INCOMING_FROM, true);
        if (parsed != null) {
            return parsed;
        }
        parsed = parse(firstLine, BRACKET_TO, false);
        if (parsed != null) {
            return parsed;
        }
        return parse(firstLine, BRACKET_FROM, true);
    }

    private static ParsedPrivateMessage parse(String line, Pattern pattern, boolean incoming) {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        String partner = matcher.group(1) == null ? "" : matcher.group(1).trim();
        String body = matcher.groupCount() >= 2 && matcher.group(2) != null ? matcher.group(2).trim() : "";
        String prefix = matcher.groupCount() >= 2 && matcher.start(2) >= 0 ? line.substring(0, matcher.start(2)) : line;
        if (partner.isBlank()) {
            return null;
        }
        return new ParsedPrivateMessage(incoming, partner, body, prefix);
    }

    private static String firstLine(String visible) {
        int newline = visible.indexOf('\n');
        return newline >= 0 ? visible.substring(0, newline) : visible;
    }

    private static String canonicalPrivateSource(String visible) {
        if (visible == null || visible.isBlank()) {
            return visible == null ? "" : visible;
        }
        String[] lines = splitLines(stripPmMarkersPerLine(visible));
        if (lines.length == 0) {
            return visible;
        }
        String firstLine = lines[0];
        String strippedFirstLine = stripLeadingChatSenderPrefix(firstLine);
        if (strippedFirstLine.equals(firstLine) || parseFirstLine(strippedFirstLine) == null) {
            return String.join("\n", lines);
        }
        lines[0] = strippedFirstLine;
        return String.join("\n", lines);
    }

    private static String stripLeadingChatSenderPrefix(String line) {
        if (line == null || line.isEmpty() || !line.startsWith("<")) {
            return line == null ? "" : line;
        }
        int end = line.indexOf("> ");
        if (end <= 1) {
            return line;
        }
        return line.substring(end + 2).stripLeading();
    }

    private static String localPlayerName() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.player.getGameProfile() == null) {
            return "";
        }
        return client.player.getGameProfile().getName();
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsMarker(String visible) {
        return visible != null && visible.indexOf(MARKER_START) >= 0;
    }

    static String leadingMarkerPrefix(String visibleLine) {
        if (visibleLine == null || visibleLine.isEmpty() || visibleLine.charAt(0) != MARKER_START) {
            return "";
        }
        int markerEnd = visibleLine.indexOf(MARKER_END, 1);
        if (markerEnd <= 1) {
            return "";
        }
        String body = visibleLine.substring(1, markerEnd);
        if (!body.startsWith("PM:")) {
            return "";
        }
        return visibleLine.substring(0, markerEnd + 1);
    }

    private static String stripMarker(String visible) {
        if (visible == null || visible.isEmpty()) {
            return visible == null ? "" : visible;
        }
        TaggedLookup tagged = tagged(visible);
        String stripped = tagged == null ? visible : tagged.strippedLine();
        return stripPmMarkersPerLine(stripped);
    }

    private static String stripPmMarkersPerLine(String visible) {
        if (visible == null || visible.isEmpty() || visible.indexOf(MARKER_START) < 0) {
            return visible == null ? "" : visible;
        }
        String[] lines = splitLines(visible);
        StringBuilder builder = new StringBuilder(visible.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            String line = lines[i] == null ? "" : lines[i];
            String markerPrefix = leadingMarkerPrefix(line);
            builder.append(markerPrefix.isEmpty() ? line : line.substring(markerPrefix.length()));
        }
        return builder.toString();
    }

    private static TaggedLookup tagged(String visibleLine) {
        if (visibleLine == null || visibleLine.isEmpty() || visibleLine.charAt(0) != MARKER_START) {
            return null;
        }
        int markerEnd = visibleLine.indexOf(MARKER_END, 1);
        if (markerEnd <= 1) {
            return null;
        }
        String body = visibleLine.substring(1, markerEnd);
        if (!body.startsWith("PM:")) {
            return null;
        }
        TaggedLine taggedLine;
        synchronized (TAGGED_LINES) {
            taggedLine = TAGGED_LINES.get(body.substring(3));
        }
        if (taggedLine == null) {
            return null;
        }
        return new TaggedLookup(taggedLine, visibleLine.substring(markerEnd + 1));
    }

    private static String marker(String id) {
        return MARKER_START + "PM:" + id + MARKER_END;
    }

    private static String storeTaggedLine(String sourceMessage, String partner, int lineIndex, boolean attention) {
        String id = Long.toString(TAG_COUNTER.incrementAndGet(), 36);
        synchronized (TAGGED_LINES) {
            TAGGED_LINES.put(id, new TaggedLine(sourceMessage, partner, Math.max(0, lineIndex), attention));
        }
        return id;
    }

    private static String rebuiltLineAt(String rebuilt, int lineIndex, String fallback) {
        if (rebuilt == null || rebuilt.isEmpty()) {
            return fallback == null ? "" : fallback;
        }
        String[] lines = splitLines(rebuilt);
        if (lines.length == 0) {
            return fallback == null ? "" : fallback;
        }
        int safeIndex = Math.max(0, Math.min(lines.length - 1, lineIndex));
        return lines[safeIndex];
    }

    private static String[] splitLines(String visible) {
        return visible.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    private static String stripLeadingPrefix(String line, String prefix) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        if (prefix != null && !prefix.isEmpty() && line.startsWith(prefix)) {
            return line.substring(prefix.length());
        }
        return line.stripLeading();
    }

    private static String stripContinuationPrefix(String line, String indent) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        if (indent != null && !indent.isEmpty() && line.startsWith(indent)) {
            return line.substring(indent.length());
        }
        return line.stripLeading();
    }

    public enum VisualStyle {
        NORMAL,
        DIM,
        HIDE
    }

    private record ConversationEntry(String partner, String displayLine, String timestamp, boolean incoming) {
    }

    private record ParsedPrivateMessage(boolean incoming, String partner, String body, String prefix) {
    }

    private record ParsedAttentionMessage(String partner, String body) {
    }

    private record TaggedLine(String sourceMessage, String partner, int lineIndex, boolean attention) {
    }

    private record TaggedLookup(TaggedLine taggedLine, String strippedLine) {
    }
}
