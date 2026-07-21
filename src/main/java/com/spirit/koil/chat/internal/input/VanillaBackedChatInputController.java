package com.spirit.koil.chat.internal.input;

import com.spirit.koil.chat.internal.upload.RichChatAttachmentRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.Rect2i;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

public final class VanillaBackedChatInputController {
    private VanillaBackedChatInputController() {
    }

    public static int suggestionAnchorX(TextFieldWidget field, TextRenderer renderer, Rect2i vanillaArea, int popupWidth, boolean multiline, String beforeCursor, boolean commandSuggestion, int baseX) {
        if (field == null || renderer == null) {
            return vanillaArea == null ? 2 : vanillaArea.getX();
        }
        int tokenStart = 0;
        for (int i = beforeCursor.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(beforeCursor.charAt(i))) {
                tokenStart = i + 1;
                break;
            }
        }
        int tokenX = baseX + renderer.getWidth(beforeCursor.substring(0, Math.max(0, Math.min(tokenStart, beforeCursor.length()))));
        return tokenX
                - com.spirit.client.gui.SuggestionPopupRenderer.PADDING
                - com.spirit.client.gui.SuggestionPopupRenderer.KIND_COLUMN_WIDTH
                - com.spirit.client.gui.SuggestionPopupRenderer.KIND_VALUE_GAP;
    }

    public static int suggestionAnchorY(TextFieldWidget field, int popupHeight, boolean multiline, int draftTop, int visibleCursorLine, int lineHeight) {
        if (!multiline && field != null) {
            return Math.max(2, field.getY() - popupHeight - 2);
        }
        int cursorLineY = draftTop + 5 + (visibleCursorLine * lineHeight);
        return Math.max(2, cursorLineY - popupHeight - 2);
    }

    public static boolean hasStyledPreview(String text) {
        return text != null
                && !text.isEmpty()
                && (text.startsWith("/") || RichChatAttachmentRenderer.containsLiveFormatting(text) || looksLikeHeader(text));
    }

    public static int renderStyledLine(DrawContext context, TextRenderer renderer, MinecraftClient client, String line, int x, int y, int maxWidth) {
        return renderStyledLine(context, renderer, client, line, x, y, maxWidth, line == null ? -1 : line.length());
    }

    public static int renderStyledLine(DrawContext context, TextRenderer renderer, MinecraftClient client, String line, int x, int y, int maxWidth, int activeCursor) {
        if (line == null || line.isEmpty()) {
            return x;
        }
        List<KoilCommandAnalysisService.StyledChunk> chunks = line.startsWith("/")
                ? KoilCommandAnalysisService.highlightLine(client, line, activeCursor)
                : formattedPreviewChunks(line);
        int cursor = x;
        int right = x + Math.max(8, maxWidth);
        for (KoilCommandAnalysisService.StyledChunk chunk : chunks) {
            if (chunk == null || chunk.text().isEmpty() || cursor >= right) {
                continue;
            }
            String visible = renderer.trimToWidth(chunk.text(), Math.max(1, right - cursor));
            if (visible.isEmpty()) {
                continue;
            }
            OrderedText ordered = Text.literal(visible).setStyle(chunk.style()).asOrderedText();
            cursor = RichChatAttachmentRenderer.renderPreviewOrDrawText(context, renderer, ordered, cursor, y, chunk.color());
        }
        return cursor;
    }

    /** Measures text with the same styled chunks used by the live draft draw. */
    public static int styledLineWidth(TextRenderer renderer, MinecraftClient client, String line) {
        if (renderer == null || line == null || line.isEmpty()) {
            return 0;
        }
        List<KoilCommandAnalysisService.StyledChunk> chunks = line.startsWith("/")
                ? KoilCommandAnalysisService.highlightLine(client, line)
                : formattedPreviewChunks(line);
        int width = 0;
        for (KoilCommandAnalysisService.StyledChunk chunk : chunks) {
            if (chunk != null && chunk.text() != null && !chunk.text().isEmpty()) {
                width += renderer.getWidth(Text.literal(chunk.text()).setStyle(chunk.style()));
            }
        }
        return width;
    }

    private static List<KoilCommandAnalysisService.StyledChunk> formattedPreviewChunks(String line) {
        List<KoilCommandAnalysisService.StyledChunk> out = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            return out;
        }
        Style baseStyle = Style.EMPTY;
        int headerOffset = headerOffset(line);
        if (headerOffset >= 0) {
            if (headerOffset > 0) {
                out.add(new KoilCommandAnalysisService.StyledChunk(line.substring(0, headerOffset), baseStyle, 0xFFE0E0E0));
            }
            int hashes = 0;
            while (headerOffset + hashes < line.length() && hashes < 6 && line.charAt(headerOffset + hashes) == '#') {
                hashes++;
            }
            int markerEnd = Math.min(line.length(), headerOffset + hashes + 1);
            out.add(new KoilCommandAnalysisService.StyledChunk(line.substring(headerOffset, markerEnd), Style.EMPTY, 0xFFA8B0BC));
            collectFormattedChunks(line.substring(markerEnd), baseStyle.withBold(true).withUnderline(true).withColor(Formatting.WHITE), out);
            return out;
        }
        collectFormattedChunks(line, baseStyle, out);
        return out;
    }

    private static void collectFormattedChunks(String text, Style baseStyle, List<KoilCommandAnalysisService.StyledChunk> out) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int index = 0;
        while (index < text.length()) {
            MarkerMatch match = nextDraftMarker(text, index);
            if (match == null) {
                out.add(new KoilCommandAnalysisService.StyledChunk(text.substring(index), baseStyle, 0xFFE0E0E0));
                return;
            }
            if (match.start() > index) {
                out.add(new KoilCommandAnalysisService.StyledChunk(text.substring(index, match.start()), baseStyle, 0xFFE0E0E0));
            }
            int close = text.indexOf(match.marker(), match.start() + match.marker().length());
            if (close < 0) {
                out.add(new KoilCommandAnalysisService.StyledChunk(match.marker(), Style.EMPTY, 0xFFA8B0BC));
                index = match.start() + match.marker().length();
                continue;
            }
            String inner = text.substring(match.start() + match.marker().length(), close);
            if ("||".equals(match.marker())) {
                out.add(new KoilCommandAnalysisService.StyledChunk(inner, baseStyle.withObfuscated(true), 0xFFE0E0E0));
            } else {
                collectFormattedChunks(inner, applyDraftStyle(baseStyle, match.marker()), out);
            }
            index = close + match.marker().length();
        }
    }

    private static MarkerMatch nextDraftMarker(String text, int from) {
        String[] markers = {"***", "**", "__", "--", "||", "*"};
        MarkerMatch best = null;
        for (String marker : markers) {
            int index = text.indexOf(marker, from);
            if (index < 0) {
                continue;
            }
            if (best == null || index < best.start() || (index == best.start() && marker.length() > best.marker().length())) {
                best = new MarkerMatch(marker, index);
            }
        }
        return best;
    }

    private static Style applyDraftStyle(Style baseStyle, String marker) {
        return switch (marker) {
            case "***" -> baseStyle.withBold(true).withItalic(true);
            case "**" -> baseStyle.withBold(true);
            case "__" -> baseStyle.withUnderline(true);
            case "*" -> baseStyle.withItalic(true);
            case "--" -> baseStyle.withStrikethrough(true);
            default -> baseStyle;
        };
    }

    private static boolean looksLikeHeader(String text) {
        return headerOffset(text) >= 0;
    }

    private static int headerOffset(String text) {
        if (text == null || text.isEmpty()) {
            return -1;
        }
        int offset = 0;
        while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) {
            offset++;
        }
        int hashes = 0;
        while (offset + hashes < text.length() && hashes < 6 && text.charAt(offset + hashes) == '#') {
            hashes++;
        }
        if (hashes <= 0 || offset + hashes >= text.length() || text.charAt(offset + hashes) != ' ') {
            return -1;
        }
        return offset;
    }

    private record MarkerMatch(String marker, int start) {
    }
}
