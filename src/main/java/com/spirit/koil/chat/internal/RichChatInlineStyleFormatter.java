package com.spirit.koil.chat.internal;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class RichChatInlineStyleFormatter {
    private RichChatInlineStyleFormatter() {
    }

    public static Text format(Text message) {
        if (message == null) {
            return null;
        }
        String raw = message.getString();
        if (raw == null || raw.isBlank()) {
            return message;
        }
        if (!containsSupportedFormatting(raw)) {
            return message;
        }
        MutableText result = Text.empty();
        boolean changed = false;
        String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append(Text.literal("\n"));
            }
            LineFormat formatted = formatLine(lines[i]);
            result.append(formatted.text());
            changed |= formatted.changed();
        }
        return changed ? result : message;
    }

    private static LineFormat formatLine(String line) {
        if (line == null || line.isEmpty()) {
            return new LineFormat(Text.empty(), false);
        }
        Style baseStyle = Style.EMPTY;
        String content = line;
        boolean changed = false;
        int hashes = 0;
        while (hashes < content.length() && hashes < 6 && content.charAt(hashes) == '#') {
            hashes++;
        }
        if (hashes > 0 && hashes < content.length() && content.charAt(hashes) == ' ') {
            content = content.substring(hashes + 1);
            changed = true;
            baseStyle = switch (hashes) {
                case 1 -> Style.EMPTY.withBold(true).withColor(Formatting.GOLD);
                case 2 -> Style.EMPTY.withBold(true).withColor(Formatting.YELLOW);
                default -> Style.EMPTY.withBold(true).withColor(Formatting.AQUA);
            };
        }
        ParseResult parsed = parseInline(content, baseStyle);
        return new LineFormat(parsed.text(), changed || parsed.changed());
    }

    private static ParseResult parseInline(String text, Style baseStyle) {
        MutableText out = Text.empty();
        int index = 0;
        boolean changed = false;
        while (index < text.length()) {
            MarkerMatch match = nextMarker(text, index);
            if (match == null) {
                out.append(Text.literal(text.substring(index)).setStyle(baseStyle));
                break;
            }
            if (match.start() > index) {
                out.append(Text.literal(text.substring(index, match.start())).setStyle(baseStyle));
            }
            int close = text.indexOf(match.marker(), match.start() + match.marker().length());
            if (close < 0) {
                out.append(Text.literal(match.marker()).setStyle(baseStyle));
                index = match.start() + match.marker().length();
                continue;
            }
            String inner = text.substring(match.start() + match.marker().length(), close);
            if (inner.isEmpty()) {
                out.append(Text.literal(match.marker()).setStyle(baseStyle));
                index = match.start() + match.marker().length();
                continue;
            }
            out.append(Text.literal(inner).setStyle(applyStyle(baseStyle, match.marker())));
            changed = true;
            index = close + match.marker().length();
        }
        return new ParseResult(out, changed);
    }

    private static Style applyStyle(Style baseStyle, String marker) {
        return switch (marker) {
            case "**" -> baseStyle.withBold(true);
            case "*" -> baseStyle.withItalic(true);
            case "--" -> baseStyle.withStrikethrough(true);
            default -> baseStyle;
        };
    }

    private static MarkerMatch nextMarker(String text, int from) {
        int best = Integer.MAX_VALUE;
        String chosen = null;
        for (String marker : new String[]{"**", "--", "*"}) {
            int at = text.indexOf(marker, from);
            if (at >= 0 && at < best) {
                best = at;
                chosen = marker;
            }
        }
        return chosen == null ? null : new MarkerMatch(best, chosen);
    }

    private static boolean containsSupportedFormatting(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        if (raw.contains("**") || raw.contains("--")) {
            return true;
        }
        if (raw.indexOf('*') >= 0) {
            return true;
        }
        String[] lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                continue;
            }
            int hashes = 0;
            while (hashes < line.length() && hashes < 6 && line.charAt(hashes) == '#') {
                hashes++;
            }
            if (hashes > 0 && hashes < line.length() && line.charAt(hashes) == ' ') {
                return true;
            }
        }
        return false;
    }

    private record MarkerMatch(int start, String marker) {
    }

    private record ParseResult(MutableText text, boolean changed) {
    }

    private record LineFormat(MutableText text, boolean changed) {
    }
}
