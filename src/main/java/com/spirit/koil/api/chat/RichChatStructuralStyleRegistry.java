package com.spirit.koil.api.chat;

import net.minecraft.text.Style;

import java.util.EnumMap;
import java.util.Map;

/**
 * Replaceable visual contracts for Rich Chat containers. Defaults preserve
 * Koil's design, while integrations can change one role or heading level
 * without forking the renderer.
 */
public final class RichChatStructuralStyleRegistry {
    public enum Role {
        HEADING,
        QUOTE_TEXT,
        QUOTE_BAR,
        SUBTEXT,
        SPOILER_HIDDEN,
        INLINE_CODE,
        LINK,
        COMMAND_LINK,
        DRAFT_CONTROL
    }

    public record TextStyle(
            Integer rgb,
            Boolean bold,
            Boolean italic,
            Boolean underlined,
            Boolean strikethrough,
            Boolean obfuscated
    ) {
        public Style apply(Style base) {
            Style result = base == null ? Style.EMPTY : base;
            if (rgb != null) result = result.withColor(rgb & 0x00FFFFFF);
            if (bold != null) result = result.withBold(bold);
            if (italic != null) result = result.withItalic(italic);
            if (underlined != null) result = result.withUnderline(underlined);
            if (strikethrough != null) result = result.withStrikethrough(strikethrough);
            if (obfuscated != null) result = result.withObfuscated(obfuscated);
            return result;
        }
    }

    public record HeadingStyle(TextStyle text, float scale, int yOffset, int spacerLines) {
        public HeadingStyle {
            text = text == null ? styleDefaults().get(Role.HEADING) : text;
            scale = Math.max(0.5F, Math.min(4.0F, scale));
            yOffset = Math.max(-8, Math.min(16, yOffset));
            spacerLines = Math.max(0, Math.min(6, spacerLines));
        }
    }

    private static volatile Map<Role, TextStyle> styles = styleDefaults();
    private static volatile Map<Integer, HeadingStyle> headings = headingDefaults(styles.get(Role.HEADING));

    private RichChatStructuralStyleRegistry() {
    }

    public static TextStyle style(Role role) {
        Role safeRole = role == null ? Role.HEADING : role;
        return styles.getOrDefault(safeRole, styleDefaults().get(safeRole));
    }

    public static Style apply(Role role, Style base) {
        return style(role).apply(base);
    }

    public static int color(Role role, int fallback) {
        Integer configured = style(role).rgb();
        return configured == null ? fallback : (fallback & 0xFF000000) | (configured & 0x00FFFFFF);
    }

    public static HeadingStyle heading(int level) {
        int safeLevel = Math.max(1, Math.min(6, level));
        return headings.getOrDefault(safeLevel, headingDefaults(style(Role.HEADING)).get(safeLevel));
    }

    public static synchronized void register(Role role, TextStyle style) {
        if (role == null || style == null) {
            return;
        }
        Map<Role, TextStyle> next = new EnumMap<>(styles);
        next.put(role, style);
        styles = Map.copyOf(next);
        if (role == Role.HEADING) {
            Map<Integer, HeadingStyle> nextHeadings = new java.util.LinkedHashMap<>();
            headings.forEach((level, heading) -> nextHeadings.put(
                    level,
                    new HeadingStyle(style, heading.scale(), heading.yOffset(), heading.spacerLines())
            ));
            headings = Map.copyOf(nextHeadings);
        }
    }

    public static synchronized void registerHeading(int level, HeadingStyle style) {
        if (level < 1 || level > 6 || style == null) {
            return;
        }
        Map<Integer, HeadingStyle> next = new java.util.LinkedHashMap<>(headings);
        next.put(level, style);
        headings = Map.copyOf(next);
    }

    public static synchronized void resetDefaults() {
        styles = styleDefaults();
        headings = headingDefaults(styles.get(Role.HEADING));
    }

    private static Map<Role, TextStyle> styleDefaults() {
        Map<Role, TextStyle> defaults = new EnumMap<>(Role.class);
        // Heading color inherits the current row/section color instead of
        // forcing white. Its shape remains the familiar Koil default.
        defaults.put(Role.HEADING, new TextStyle(null, true, null, true, null, null));
        defaults.put(Role.QUOTE_TEXT, new TextStyle(null, null, null, null, null, null));
        defaults.put(Role.QUOTE_BAR, new TextStyle(0x7B8794, null, null, null, null, null));
        defaults.put(Role.SUBTEXT, new TextStyle(0x555555, null, null, null, null, null));
        defaults.put(Role.SPOILER_HIDDEN, new TextStyle(null, null, null, null, null, true));
        defaults.put(Role.INLINE_CODE, new TextStyle(0xAAAAAA, null, null, null, null, null));
        defaults.put(Role.LINK, new TextStyle(0x5555FF, null, null, true, null, null));
        defaults.put(Role.COMMAND_LINK, new TextStyle(0xFFAA00, null, null, true, null, null));
        defaults.put(Role.DRAFT_CONTROL, new TextStyle(0x777777, false, false, false, false, false));
        return Map.copyOf(defaults);
    }

    private static Map<Integer, HeadingStyle> headingDefaults(TextStyle text) {
        Map<Integer, HeadingStyle> defaults = new java.util.LinkedHashMap<>();
        defaults.put(1, new HeadingStyle(text, 2.0F, 2, 2));
        defaults.put(2, new HeadingStyle(text, 1.66F, 2, 2));
        defaults.put(3, new HeadingStyle(text, 1.33F, 1, 1));
        defaults.put(4, new HeadingStyle(text, 1.20F, 1, 1));
        defaults.put(5, new HeadingStyle(text, 1.10F, 1, 1));
        defaults.put(6, new HeadingStyle(text, 1.0F, 1, 1));
        return Map.copyOf(defaults);
    }
}
