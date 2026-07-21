package com.spirit.koil.chat.internal;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RichChatTimestampBridge {
    private static final int MAX_CACHE = 512;
    private static final int TIMESTAMP_COLOR = 0xFF5F646C;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a");
    private static final Map<String, TimestampMeta> FIRST_LINE_TIMESTAMPS = new LinkedHashMap<>(MAX_CACHE, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TimestampMeta> eldest) {
            return size() > MAX_CACHE;
        }
    };

    private RichChatTimestampBridge() {
    }

    public static void remember(Text message) {
        if (message == null) {
            return;
        }
        String visible = message.getString();
        if (visible == null || visible.isBlank()) {
            return;
        }
        String firstLine = firstLine(visible);
        if (!hasChatPrefix(firstLine)) {
            return;
        }
        synchronized (FIRST_LINE_TIMESTAMPS) {
            if (FIRST_LINE_TIMESTAMPS.containsKey(firstLine)) {
                return;
            }
        }
        rememberVisibleLine(firstLine, formatMillis(System.currentTimeMillis()), hasRoomUnderUsername(visible));
    }

    public static void rememberVisibleLine(String visibleLine, String timestamp) {
        rememberVisibleLine(visibleLine, timestamp, hasRoomUnderUsername(visibleLine));
    }

    public static void rememberVisibleLine(String visibleLine, String timestamp, boolean inlineAllowed) {
        if (visibleLine == null || visibleLine.isBlank() || timestamp == null || timestamp.isBlank()) {
            return;
        }
        String firstLine = firstLine(visibleLine);
        if (!hasChatPrefix(firstLine)) {
            return;
        }
        synchronized (FIRST_LINE_TIMESTAMPS) {
            FIRST_LINE_TIMESTAMPS.put(firstLine, new TimestampMeta(timestamp, inlineAllowed));
        }
    }

    public static void render(DrawContext context, TextRenderer renderer, String visibleLine, int x, int y) {
        if (context == null || renderer == null || visibleLine == null || visibleLine.isBlank()) {
            return;
        }
        String displayLine = RichChatPrivateMessageBridge.displayText(visibleLine);
        RichChatPrivateMessageBridge.VisualStyle style = RichChatPrivateMessageBridge.visualStyle(visibleLine);
        if (style == RichChatPrivateMessageBridge.VisualStyle.HIDE) {
            return;
        }
        TimestampMeta meta;
        synchronized (FIRST_LINE_TIMESTAMPS) {
            meta = FIRST_LINE_TIMESTAMPS.get(firstLine(displayLine));
        }
        if (meta == null || meta.timestamp().isBlank() || !meta.inlineAllowed()) {
            return;
        }
        PrefixBounds prefix = prefixBounds(renderer, displayLine, x);
        if (prefix == null) {
            return;
        }
        context.getMatrices().push();
        context.getMatrices().scale(0.5F, 0.5F, 1.0F);
        context.drawText(
                renderer,
                meta.timestamp(),
                Math.round(prefix.timestampX() / 0.5F),
                Math.round((y + renderer.fontHeight + 2) / 0.5F),
                style == RichChatPrivateMessageBridge.VisualStyle.DIM ? RichChatPrivateMessageBridge.dimColor(TIMESTAMP_COLOR) : TIMESTAMP_COLOR,
                false
        );
        context.getMatrices().pop();
    }

    public static String timestampForLine(String visibleLine) {
        if (visibleLine == null || visibleLine.isBlank()) {
            return "";
        }
        synchronized (FIRST_LINE_TIMESTAMPS) {
            TimestampMeta meta = FIRST_LINE_TIMESTAMPS.get(firstLine(visibleLine));
            return meta == null ? "" : meta.timestamp();
        }
    }

    public static boolean inlineAllowed(String visibleLine) {
        if (visibleLine == null || visibleLine.isBlank()) {
            return false;
        }
        synchronized (FIRST_LINE_TIMESTAMPS) {
            TimestampMeta meta = FIRST_LINE_TIMESTAMPS.get(firstLine(visibleLine));
            return meta != null && meta.inlineAllowed();
        }
    }

    public static String formatMillis(long millis) {
        return TIME_FORMAT.format(LocalTime.ofInstant(java.time.Instant.ofEpochMilli(Math.max(0L, millis)), java.time.ZoneId.systemDefault()));
    }

    private static String firstLine(String visible) {
        int newline = visible.indexOf('\n');
        return newline >= 0 ? visible.substring(0, newline) : visible;
    }

    private static boolean hasChatPrefix(String line) {
        return line != null && line.startsWith("<") && line.indexOf("> ") > 1;
    }

    private static boolean hasRoomUnderUsername(String visible) {
        return visible != null && (visible.indexOf('\n') >= 0 || com.spirit.koil.chat.internal.upload.LocalRichAttachmentBridge.containsMarker(visible));
    }

    private static PrefixBounds prefixBounds(TextRenderer renderer, String line, int x) {
        int end = line.indexOf("> ");
        if (end <= 1) {
            return null;
        }
        int timestampX = x + renderer.getWidth("<");
        return new PrefixBounds(timestampX);
    }

    private record PrefixBounds(int timestampX) {
    }

    private record TimestampMeta(String timestamp, boolean inlineAllowed) {
    }
}
