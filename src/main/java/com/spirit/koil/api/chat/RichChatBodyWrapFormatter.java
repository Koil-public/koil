package com.spirit.koil.api.chat;

import com.spirit.koil.api.chat.latex.RichChatLatexTextureCache;
import com.spirit.koil.api.chat.upload.LocalRichAttachmentBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RichChatBodyWrapFormatter {
    private static final int MAX_CACHE = 256;
    // Koil and native ChatHud use the same TextRenderer and measured 40-320px
    // Chat Width contract. Only retain a tiny glyph-edge guard; the previous
    // filter/prefix constants could remove about half of a 320px chat row.
    private static final int WRAP_EDGE_GUARD = 2;
    // A single unbroken token is cut by trimToWidth, then can be measured a
    // fraction wider by ChatHud's native glyph path. Keep a small hard-token
    // buffer without making ordinary word-wrapped text narrower.
    private static final int HARD_TOKEN_WRAP_SAFETY = 6;
    private static final Map<String, Text> CACHE = new LinkedHashMap<>(MAX_CACHE, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Text> eldest) {
            return size() > MAX_CACHE;
        }
    };

    private RichChatBodyWrapFormatter() {
    }

    public static Text format(Text message) {
        return format(message, RichChatRowType.UNKNOWN);
    }

    public static Text format(Text message, RichChatRowType rowType) {
        if (message == null) {
            return null;
        }
        String visible = message.getString();
        if (visible == null || visible.isBlank()) {
            return message;
        }

        int wrapWidth = currentWrapWidth();
        RichChatRowType safeType = rowType == null ? RichChatRowType.UNKNOWN : rowType;
        String cacheKey = safeType.name()
                + ":" + wrapWidth
                + ":" + (RichChatPrivateMessageBridge.filterEnabled() ? "filtered" : "unfiltered")
                + ":" + visible;
        synchronized (CACHE) {
            Text cached = CACHE.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        String wrapped = wrapVisibleText(visible, wrapWidth, safeType);
        if (wrapped.equals(visible)) {
            return message;
        }

        Text formatted = Text.literal(wrapped);
        synchronized (CACHE) {
            CACHE.put(cacheKey, formatted);
        }
        return formatted;
    }

    public static int currentWrapWidth() {
        // This must remain the real native ChatHud boundary. Artificially
        // enlarging it makes Minecraft wrap Koil's already-wrapped rows a
        // second time, which presents as an empty row between every line.
        return RichChatLatexTextureCache.currentChatContentWidth();
    }

    private static String wrapVisibleText(String visible, int wrapWidth, RichChatRowType rowType) {
        String[] lines = visible.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder result = new StringBuilder(visible.length() + 32);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append('\n');
            }
            String wrapped = wrapLine(lines[i], wrapWidth, rowType);
            changed |= !wrapped.equals(lines[i]);
            result.append(wrapped);
            int spacerLines = headerSpacerLines(lines, i);
            for (int spacer = 0; spacer < spacerLines; spacer++) {
                result.append('\n');
                changed = true;
            }
        }
        return changed ? result.toString() : visible;
    }

    private static String wrapLine(String line, int wrapWidth, RichChatRowType rowType) {
        if (line == null || line.isEmpty()) {
            return line == null ? "" : line;
        }
        String markerPrefix = RichChatPrivateMessageBridge.leadingMarkerPrefix(line);
        String visibleLine = markerPrefix.isEmpty() ? line : line.substring(markerPrefix.length());
        if (LocalRichAttachmentBridge.containsMarker(visibleLine) || RichChatCodeBlockBridge.containsMarker(visibleLine)) {
            return line;
        }
        TextRenderer renderer = textRenderer();
        if (renderer == null) {
            return line;
        }
        // MixinChatHud compensates native wrapping for the exact measured
        // width of Koil's hidden PM control prefix. It is therefore excluded
        // from this visible-content calculation instead of guessed twice.
        int effectiveWrapWidth = Math.max(24, wrapWidth);
        if (measuredWidth(renderer, visibleLine) <= effectiveWrapWidth) {
            return line;
        }

        if (rowType == null || !rowType.usesBodyIndent()) {
            return line;
        }

        String prefix = detectVisibleBodyPrefix(visibleLine, rowType);
        if (prefix.isEmpty()) {
            return line;
        }

        String indent = LocalMultilineChatBridge.indentForPrefix(prefix);
        String body = visibleLine.substring(prefix.length());
        int safetyMargin = WRAP_EDGE_GUARD;
        int firstWidth = Math.max(24, effectiveWrapWidth - renderer.getWidth(prefix) - safetyMargin);
        int continuationWidth = Math.max(24, effectiveWrapWidth - renderer.getWidth(indent) - safetyMargin);
        if (firstWidth <= 24 || continuationWidth <= 24) {
            return line;
        }

        List<String> parts = wrapBody(body, renderer, firstWidth, continuationWidth);
        if (parts.size() <= 1) {
            return line;
        }

        StringBuilder builder = new StringBuilder(line.length() + indent.length() * Math.max(0, parts.size() - 1) + markerPrefix.length() * parts.size());
        builder.append(markerPrefix).append(prefix).append(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            builder.append('\n').append(markerPrefix).append(indent).append(parts.get(i));
        }
        return builder.toString();
    }

    private static List<String> wrapBody(String body, TextRenderer renderer, int firstWidth, int continuationWidth) {
        List<String> parts = new ArrayList<>();
        String remaining = body == null ? "" : body;
        boolean first = true;
        while (!remaining.isEmpty()) {
            int limit = first ? firstWidth : continuationWidth;
            if (measuredWidth(renderer, remaining) <= limit) {
                parts.add(remaining);
                break;
            }
            String rawSlice = fittedSlice(remaining, renderer, limit);
            if (rawSlice.isEmpty()) {
                rawSlice = remaining.substring(0, 1);
            }
            String part = rawSlice.stripTrailing();
            if (!part.isEmpty()) {
                parts.add(part);
            }
            remaining = remaining.substring(Math.min(remaining.length(), rawSlice.length())).stripLeading();
            first = false;
        }
        return parts;
    }

    private static String fittedSlice(String text, TextRenderer renderer, int limit) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int index = 0;
        int currentWidth = 0;
        int lastBreak = -1;
        while (index < text.length()) {
            Token token = nextToken(text, index, renderer);
            if (token == null || token.end() <= index) {
                break;
            }
            if (token.whitespace()) {
                lastBreak = token.end();
            }
            if (currentWidth + token.width() <= limit) {
                currentWidth += token.width();
                index = token.end();
                continue;
            }
            if (lastBreak > 0) {
                return text.substring(0, lastBreak);
            }
            if (index > 0) {
                return text.substring(0, index);
            }
            if (token.marker()) {
                return text.substring(0, token.end());
            }
            String fitted = renderer.trimToWidth(token.text(), Math.max(1, limit - HARD_TOKEN_WRAP_SAFETY));
            if (fitted.isEmpty()) {
                fitted = token.text().substring(0, 1);
            }
            return fitted;
        }
        return text.substring(0, Math.max(0, index));
    }

    private static Token nextToken(String text, int index, TextRenderer renderer) {
        if (text == null || index < 0 || index >= text.length()) {
            return null;
        }
        RichChatLatexTextureCache.Marker marker = RichChatLatexTextureCache.nextMarker(text, index);
        if (marker != null && marker.start() == index) {
            return new Token(text.substring(index, marker.end()), marker.end(), markerWidth(marker), false, true);
        }
        char current = text.charAt(index);
        if (Character.isWhitespace(current)) {
            int end = index + 1;
            while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            String token = text.substring(index, end);
            return new Token(token, end, renderer.getWidth(token), true, false);
        }
        int end = index + 1;
        while (end < text.length()) {
            RichChatLatexTextureCache.Marker nextMarker = RichChatLatexTextureCache.nextMarker(text, end);
            if (nextMarker != null && nextMarker.start() == end) {
                break;
            }
            if (Character.isWhitespace(text.charAt(end))) {
                break;
            }
            end++;
        }
        String token = text.substring(index, end);
        return new Token(token, end, renderer.getWidth(token), false, false);
    }

    private static int measuredWidth(TextRenderer renderer, String text) {
        if (renderer == null || text == null || text.isEmpty()) {
            return 0;
        }
        if (!RichChatLatexTextureCache.containsMarker(text)) {
            return renderer.getWidth(text);
        }
        int width = 0;
        int index = 0;
        RichChatLatexTextureCache.Marker marker;
        while ((marker = RichChatLatexTextureCache.nextMarker(text, index)) != null) {
            if (marker.start() > index) {
                width += renderer.getWidth(text.substring(index, marker.start()));
            }
            width += markerWidth(marker);
            index = marker.end();
        }
        if (index < text.length()) {
            width += renderer.getWidth(text.substring(index));
        }
        return width;
    }

    private static int markerWidth(RichChatLatexTextureCache.Marker marker) {
        if (marker == null || marker.entry() == null) {
            return 0;
        }
        return Math.max(1, marker.entry().advanceWidth());
    }

    public static String detectVisibleBodyPrefix(String line) {
        return detectVisibleBodyPrefix(line, null);
    }

    public static String detectVisibleBodyPrefix(String line, RichChatRowType rowType) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        int leadingWhitespace = leadingWhitespaceWidth(line);
        if (leadingWhitespace > 0) {
            return line.substring(0, leadingWhitespace);
        }
        boolean allowNamedPrefix = rowType == null || rowType == RichChatRowType.PLAYER_CHAT || rowType == RichChatRowType.PRIVATE_MESSAGE;
        boolean allowPrivatePrefix = rowType == null || rowType == RichChatRowType.PRIVATE_MESSAGE;
        if (allowNamedPrefix && line.startsWith("<")) {
            int end = line.indexOf("> ");
            if (end >= 0) {
                return line.substring(0, end + 2);
            }
        }
        if (!allowPrivatePrefix) {
            return "";
        }
        String[] privatePrefixes = {
                "You whisper to ",
                " whispers to you: ",
                "To ",
                "From "
        };
        for (String marker : privatePrefixes) {
            int markerIndex = line.indexOf(marker);
            if (markerIndex == 0 || " whispers to you: ".equals(marker)) {
                int bodyStart = line.indexOf(": ");
                if (bodyStart >= 0) {
                    return line.substring(0, bodyStart + 2);
                }
            }
        }
        if (line.startsWith("[To ") || line.startsWith("[From ")) {
            int end = line.indexOf("] ");
            if (end >= 0) {
                return line.substring(0, end + 2);
            }
        }
        return "";
    }

    private static int headerSpacerLines(String[] lines, int index) {
        if (lines == null || index < 0 || index >= lines.length) {
            return 0;
        }
        String line = lines[index];
        if (line == null || line.isEmpty()) {
            return 0;
        }
        if (index + 1 < lines.length && lines[index + 1] != null && lines[index + 1].isBlank()) {
            return 0;
        }
        String visibleLine = RichChatPrivateMessageBridge.stripVisibleMarkersForLayout(line);
        String prefix = detectVisibleBodyPrefix(visibleLine);
        String content = (prefix.isEmpty() ? visibleLine : visibleLine.substring(prefix.length())).stripLeading();
        if (content.startsWith("# ") || content.startsWith("## ")) {
            return 2;
        }
        if (content.startsWith("### ") || content.startsWith("#### ") || content.startsWith("##### ") || content.startsWith("###### ")) {
            return 1;
        }
        return 0;
    }

    private static int leadingWhitespaceWidth(String line) {
        int index = 0;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
            index++;
        }
        return index;
    }

    private static TextRenderer textRenderer() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client == null ? null : client.textRenderer;
    }

    private record Token(String text, int end, int width, boolean whitespace, boolean marker) {
    }
}
