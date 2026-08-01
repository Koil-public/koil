package com.spirit.koil.api.chat;

import net.minecraft.text.Style;
import net.minecraft.text.TextColor;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses Minecraft section-sign formatting without passing control codes to
 * the vanilla server. In addition to Java's legacy codes, Koil accepts the
 * compact {@code §#RRGGBB} form and the expanded {@code §x§R§R§G§G§B§B}
 * representation used by parts of the Minecraft ecosystem.
 */
public final class RichChatSectionFormatting {
    public static final char PREFIX = '\u00a7';

    private RichChatSectionFormatting() {
    }

    /** True even when the user has not finished typing a valid code yet. */
    public static boolean containsSectionSign(String value) {
        return value != null && value.indexOf(PREFIX) >= 0;
    }

    public static boolean containsFormatting(String value) {
        if (!containsSectionSign(value)) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            int length = codeLengthAt(value, index);
            if (length > 0) {
                return true;
            }
        }
        return false;
    }

    /** Returns the number of source characters occupied by a valid code. */
    public static int codeLengthAt(String value, int index) {
        if (value == null || index < 0 || index + 1 >= value.length() || value.charAt(index) != PREFIX) {
            return 0;
        }
        char code = Character.toLowerCase(value.charAt(index + 1));
        if (isLegacyCode(code)) {
            if (code == 'x') {
                return expandedHex(value, index) == null ? 0 : 14;
            }
            return 2;
        }
        if (code == '#' && index + 8 <= value.length()) {
            return isHex(value, index + 2, 6) ? 8 : 0;
        }
        return 0;
    }

    public static String stripCodes(String value) {
        if (value == null || value.isEmpty() || value.indexOf(PREFIX) < 0) {
            return value == null ? "" : value;
        }
        StringBuilder visible = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            int length = codeLengthAt(value, index);
            if (length > 0) {
                index += length;
            } else {
                visible.append(value.charAt(index++));
            }
        }
        return visible.toString();
    }

    /**
     * Produces text that is safe for vanilla chat/command packets. Valid codes
     * are removed as controls; every incomplete or invalid section sign is
     * removed as well because vanilla servers reject U+00A7 on the wire.
     */
    public static String networkSafeFallback(String value) {
        return stripCodes(value).replace(String.valueOf(PREFIX), "");
    }

    /**
     * Removes section controls for speech. Unlike the network fallback, an
     * unknown control also consumes its following code character so a voice
     * can never pronounce fragments such as "section a" or raw hex digits.
     */
    public static String speechSafeText(String value) {
        if (value == null || value.isEmpty() || value.indexOf(PREFIX) < 0) {
            return value == null ? "" : value;
        }
        StringBuilder spoken = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            if (value.charAt(index) != PREFIX) {
                spoken.append(value.charAt(index++));
                continue;
            }
            int validLength = codeLengthAt(value, index);
            if (validLength > 0) {
                index += validLength;
                continue;
            }
            if (index + 1 < value.length() && value.charAt(index + 1) == '#') {
                index += 2;
                int digits = 0;
                while (index < value.length() && digits < 6
                        && Character.digit(value.charAt(index), 16) >= 0) {
                    index++;
                    digits++;
                }
            } else {
                index += Math.min(2, value.length() - index);
            }
        }
        return spoken.toString();
    }

    /**
     * Converts raw controls to actual Text styles before Minecraft wraps the
     * row. This makes every control zero-width to vanilla and supports Koil
     * compact hex, which Minecraft's legacy string parser does not understand.
     */
    public static Text styleBeforeWrapping(Text value) {
        if (value == null || !containsFormatting(value.getString())) {
            return value;
        }
        MutableText styled = Text.empty();
        for (Segment segment : parse(value.getString(), Style.EMPTY)) {
            styled.append(Text.literal(segment.text()).setStyle(segment.style()));
        }
        return styled;
    }

    /** Recreates parser controls from the visual styles retained by OrderedText. */
    public static String controlSource(OrderedText value) {
        if (value == null) {
            return "";
        }
        StringBuilder source = new StringBuilder();
        VisualStyleState[] previous = new VisualStyleState[]{VisualStyleState.DEFAULT};
        value.accept((index, style, codePoint) -> {
            VisualStyleState next = VisualStyleState.from(style);
            if (!next.equals(previous[0])) {
                source.append(PREFIX).append('r');
                if (next.rgb() != null) {
                    source.append(PREFIX).append('#')
                            .append(String.format(Locale.ROOT, "%06X", next.rgb() & 0x00FFFFFF));
                }
                if (next.obfuscated()) source.append(PREFIX).append('k');
                if (next.bold()) source.append(PREFIX).append('l');
                if (next.strikethrough()) source.append(PREFIX).append('m');
                if (next.underlined()) source.append(PREFIX).append('n');
                if (next.italic()) source.append(PREFIX).append('o');
                previous[0] = next;
            }
            source.appendCodePoint(codePoint);
            return true;
        });
        return source.toString();
    }

    public static List<Segment> parse(String value, Style baseStyle) {
        return parse(value, baseStyle, false);
    }

    /** Draft form keeps control tokens visible while still applying them. */
    public static List<Segment> parseDraft(String value, Style baseStyle) {
        return parse(value, baseStyle, true, "");
    }

    private static List<Segment> parse(String value, Style baseStyle, boolean showControls) {
        return parse(value, baseStyle, showControls, "");
    }

    /** Applies prior section state without drawing a synthetic control token. */
    public static List<Segment> parseDraft(String value, Style baseStyle, String initialPrefix) {
        return parse(value, baseStyle, true, initialPrefix);
    }

    private static List<Segment> parse(
            String value,
            Style baseStyle,
            boolean showControls,
            String initialPrefix
    ) {
        String prefix = initialPrefix == null ? "" : initialPrefix;
        String source = prefix + (value == null ? "" : value);
        int visibleSourceStart = prefix.length();
        Style base = baseStyle == null ? Style.EMPTY : baseStyle;
        if (source.isEmpty()) {
            return List.of();
        }
        List<Segment> segments = new ArrayList<>();
        StringBuilder visible = new StringBuilder(source.length());
        Style active = base;
        int index = 0;
        while (index < source.length()) {
            int length = codeLengthAt(source, index);
            if (length <= 0) {
                visible.append(source.charAt(index++));
                continue;
            }
            flush(segments, visible, active);
            if (showControls && index >= visibleSourceStart) {
                char control = Character.toLowerCase(source.charAt(index + 1));
                String visibleControl = control == '#' || control == 'x'
                        ? String.valueOf(PREFIX)
                        : source.substring(index, index + length);
                segments.add(new Segment(
                        visibleControl,
                        RichChatStructuralStyleRegistry.apply(
                                RichChatStructuralStyleRegistry.Role.DRAFT_CONTROL,
                                base
                        )
                ));
            }
            active = applyCode(source, index, active, base);
            index += length;
        }
        flush(segments, visible, active);
        return List.copyOf(segments);
    }

    /**
     * Returns a canonical prefix that reopens the active section formatting on
     * a separately rendered continuation row.
     */
    public static String continuationPrefix(String value) {
        FormattingState state = new FormattingState();
        String source = value == null ? "" : value;
        for (int index = 0; index < source.length();) {
            int length = codeLengthAt(source, index);
            if (length <= 0) {
                index++;
                continue;
            }
            updateState(source, index, state);
            index += length;
        }
        return state.prefix();
    }

    private static Style applyCode(String source, int index, Style active, Style base) {
        char code = Character.toLowerCase(source.charAt(index + 1));
        if (code == '#') {
            return base.withColor(TextColor.fromRgb(Integer.parseInt(source.substring(index + 2, index + 8), 16)));
        }
        if (code == 'x') {
            return base.withColor(TextColor.fromRgb(Integer.parseInt(expandedHex(source, index), 16)));
        }
        Formatting formatting = Formatting.byCode(code);
        if (formatting == null) {
            return active;
        }
        if (formatting == Formatting.RESET) {
            return base;
        }
        if (formatting.isColor()) {
            return base.withColor(formatting);
        }
        return active.withFormatting(formatting);
    }

    private static void updateState(String source, int index, FormattingState state) {
        char code = Character.toLowerCase(source.charAt(index + 1));
        if (code == '#') {
            state.setColor("\u00a7#" + source.substring(index + 2, index + 8).toUpperCase(Locale.ROOT));
            return;
        }
        if (code == 'x') {
            state.setColor("\u00a7#" + expandedHex(source, index).toUpperCase(Locale.ROOT));
            return;
        }
        if (code == 'r') {
            state.reset();
        } else if (isLegacyColor(code)) {
            state.setColor("\u00a7" + code);
        } else {
            state.setFormat(code);
        }
    }

    private static String expandedHex(String source, int index) {
        if (source == null || index < 0 || index + 14 > source.length()
                || source.charAt(index) != PREFIX
                || Character.toLowerCase(source.charAt(index + 1)) != 'x') {
            return null;
        }
        StringBuilder hex = new StringBuilder(6);
        for (int pair = 0; pair < 6; pair++) {
            int marker = index + 2 + pair * 2;
            char digit = source.charAt(marker + 1);
            if (source.charAt(marker) != PREFIX || Character.digit(digit, 16) < 0) {
                return null;
            }
            hex.append(digit);
        }
        return hex.toString();
    }

    private static boolean isLegacyCode(char code) {
        return isLegacyColor(code) || code == 'k' || code == 'l' || code == 'm'
                || code == 'n' || code == 'o' || code == 'r' || code == 'x';
    }

    private static boolean isLegacyColor(char code) {
        return code >= '0' && code <= '9' || code >= 'a' && code <= 'f';
    }

    private static boolean isHex(String value, int start, int length) {
        if (start < 0 || length < 0 || start + length > value.length()) {
            return false;
        }
        for (int index = start; index < start + length; index++) {
            if (Character.digit(value.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static void flush(List<Segment> segments, StringBuilder visible, Style style) {
        if (visible.isEmpty()) {
            return;
        }
        segments.add(new Segment(visible.toString(), style));
        visible.setLength(0);
    }

    public record Segment(String text, Style style) {
    }

    private record VisualStyleState(
            Integer rgb,
            boolean bold,
            boolean italic,
            boolean underlined,
            boolean strikethrough,
            boolean obfuscated
    ) {
        private static final VisualStyleState DEFAULT = new VisualStyleState(
                null, false, false, false, false, false
        );

        private static VisualStyleState from(Style style) {
            Style safe = style == null ? Style.EMPTY : style;
            return new VisualStyleState(
                    safe.getColor() == null ? null : safe.getColor().getRgb(),
                    safe.isBold(),
                    safe.isItalic(),
                    safe.isUnderlined(),
                    safe.isStrikethrough(),
                    safe.isObfuscated()
            );
        }
    }

    private static final class FormattingState {
        private String color = "";
        private boolean obfuscated;
        private boolean bold;
        private boolean strikethrough;
        private boolean underline;
        private boolean italic;

        private void setColor(String color) {
            this.color = color;
            this.obfuscated = false;
            this.bold = false;
            this.strikethrough = false;
            this.underline = false;
            this.italic = false;
        }

        private void setFormat(char code) {
            switch (code) {
                case 'k' -> this.obfuscated = true;
                case 'l' -> this.bold = true;
                case 'm' -> this.strikethrough = true;
                case 'n' -> this.underline = true;
                case 'o' -> this.italic = true;
                default -> { }
            }
        }

        private void reset() {
            this.color = "";
            this.obfuscated = false;
            this.bold = false;
            this.strikethrough = false;
            this.underline = false;
            this.italic = false;
        }

        private String prefix() {
            StringBuilder prefix = new StringBuilder(18);
            prefix.append(this.color);
            if (this.obfuscated) prefix.append("\u00a7k");
            if (this.bold) prefix.append("\u00a7l");
            if (this.strikethrough) prefix.append("\u00a7m");
            if (this.underline) prefix.append("\u00a7n");
            if (this.italic) prefix.append("\u00a7o");
            return prefix.toString();
        }
    }
}
