package com.spirit.koil.api.chat;

/**
 * Shared heading geometry used by both Rich Chat wrapping and rendering.
 * Keeping scale and reservation in one contract prevents large headings from
 * being measured at ordinary text width and losing their style after a wrap.
 */
public final class RichChatHeadingLayout {
    private RichChatHeadingLayout() {
    }

    public static Heading detect(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int leading = 0;
        while (leading < text.length() && Character.isWhitespace(text.charAt(leading))) {
            leading++;
        }
        int hashes = 0;
        while (leading + hashes < text.length()
                && hashes < 6
                && text.charAt(leading + hashes) == '#') {
            hashes++;
        }
        if (hashes <= 0
                || leading + hashes >= text.length()
                || text.charAt(leading + hashes) != ' ') {
            return null;
        }
        RichChatStructuralStyleRegistry.HeadingStyle configured =
                RichChatStructuralStyleRegistry.heading(hashes);
        String leadingWhitespace = text.substring(0, leading);
        String marker = "#".repeat(hashes) + " ";
        return new Heading(
                hashes,
                leadingWhitespace,
                marker,
                text.substring(leading + hashes + 1),
                configured.scale(),
                configured.yOffset(),
                configured.spacerLines()
        );
    }

    public record Heading(
            int level,
            String leadingWhitespace,
            String marker,
            String content,
            float scale,
            int yOffset,
            int spacerLines
    ) {
    }
}
