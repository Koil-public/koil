package com.spirit.koil.api.chat;

import net.minecraft.text.Text;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces masked-link source with a compact render marker before vanilla
 * ChatHud wrapping. This keeps a long hidden URL or command target from being
 * wrapped through the middle of its Markdown syntax.
 */
public final class RichChatMaskedLinkBridge {
    public static final char MARKER_START = '\uE350';
    public static final char MARKER_END = '\uE351';
    private static final int MARKER_BASE = 0xE400;
    private static final int MARKER_COUNT = 0x0400;
    private static final int MAX_LINKS = 768;
    private static final Pattern MASKED_LINK = Pattern.compile(
            "\\[([^\\]\\n]{1,256})]\\(([^)\\n]{1,2048})\\)"
    );
    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final Map<Character, Link> LINKS = new LinkedHashMap<>(MAX_LINKS, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Character, Link> eldest) {
            return size() > MAX_LINKS;
        }
    };

    private RichChatMaskedLinkBridge() {
    }

    public static Text rewrite(Text message) {
        if (message == null) {
            return null;
        }
        String source = message.getString();
        if (source == null || source.isBlank() || source.indexOf('[') < 0) {
            return message;
        }
        Matcher matcher = MASKED_LINK.matcher(source);
        StringBuilder output = new StringBuilder(source.length());
        int cursor = 0;
        boolean changed = false;
        while (matcher.find()) {
            output.append(source, cursor, matcher.start());
            char id = nextMarkerId();
            synchronized (LINKS) {
                LINKS.put(id, new Link(matcher.group(1), matcher.group(2).trim()));
            }
            output.append(MARKER_START).append(id).append(MARKER_END);
            cursor = matcher.end();
            changed = true;
        }
        if (!changed) {
            return message;
        }
        output.append(source, cursor, source.length());
        return Text.literal(output.toString());
    }

    public static boolean containsMarker(String text) {
        return text != null && text.indexOf(MARKER_START) >= 0;
    }

    public static Marker nextMarker(String text, int fromIndex) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf(MARKER_START, Math.max(0, fromIndex));
        if (start < 0 || start + 2 >= text.length() || text.charAt(start + 2) != MARKER_END) {
            return null;
        }
        char id = text.charAt(start + 1);
        synchronized (LINKS) {
            return LINKS.containsKey(id) ? new Marker(start, start + 3, id) : null;
        }
    }

    public static Link link(Marker marker) {
        if (marker == null) {
            return null;
        }
        synchronized (LINKS) {
            return LINKS.get(marker.id());
        }
    }

    public static String logFriendlyText(String text) {
        if (!containsMarker(text)) {
            return text == null ? "" : text;
        }
        StringBuilder output = new StringBuilder(text.length() + 32);
        int cursor = 0;
        Marker marker;
        while ((marker = nextMarker(text, cursor)) != null) {
            output.append(text, cursor, marker.start());
            Link link = link(marker);
            if (link != null) {
                output.append('[').append(link.label()).append("](").append(link.target()).append(')');
            }
            cursor = marker.end();
        }
        output.append(text, cursor, text.length());
        return output.toString();
    }

    private static char nextMarkerId() {
        return (char) (MARKER_BASE + Math.floorMod(NEXT_ID.getAndIncrement(), MARKER_COUNT));
    }

    public record Marker(int start, int end, char id) {
    }

    public record Link(String label, String target) {
        public Link {
            label = label == null ? "" : label;
            target = target == null ? "" : target;
        }
    }
}
