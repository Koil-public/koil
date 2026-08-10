package com.spirit.koil.api.chat;

/**
 * Shared parser for Rich Chat continuation markers. Minecraft may reopen an
 * inherited section style before a wrapped structural marker; this parser
 * consumes those controls and moves them onto the body so {@code -# } stays
 * semantic and can never become visible text.
 */
public final class RichChatStructuralContinuation {
    public static final char SUBTEXT = '\uE380';

    private RichChatStructuralContinuation() {
    }

    public static String subtextPrefix(String leadingWhitespace) {
        return (leadingWhitespace == null ? "" : leadingWhitespace) + "-# ";
    }

    public static boolean isSubtext(String value, int index) {
        return value != null
                && index >= 0
                && index < value.length()
                && value.charAt(index) == SUBTEXT;
    }

    public static Subtext parseSubtext(String value) {
        if (value == null || value.isEmpty()) return null;
        int whitespace = 0;
        while (whitespace < value.length() && Character.isWhitespace(value.charAt(whitespace))) {
            whitespace++;
        }
        int cursor = whitespace;
        StringBuilder inheritedFormatting = new StringBuilder();
        while (cursor < value.length()) {
            int length = RichChatSectionFormatting.codeLengthAt(value, cursor);
            if (length <= 0) break;
            inheritedFormatting.append(value, cursor, cursor + length);
            cursor += length;
        }
        int markerLength;
        if (value.startsWith("-# ", cursor)) {
            markerLength = 3;
        } else if (isSubtext(value, cursor)) {
            markerLength = 1;
        } else {
            return null;
        }
        return new Subtext(
                value.substring(0, whitespace),
                inheritedFormatting + value.substring(cursor + markerLength)
        );
    }

    public record Subtext(String leadingWhitespace, String content) {
        public Subtext {
            leadingWhitespace = leadingWhitespace == null ? "" : leadingWhitespace;
            content = content == null ? "" : content;
        }
    }
}
