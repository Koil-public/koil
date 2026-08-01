package com.spirit.koil.api.chat.input;

import com.spirit.koil.api.chat.upload.RichChatAttachmentRenderer;
import com.spirit.koil.api.chat.RichChatSectionFormatting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.Rect2i;
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

    /**
     * Width-bounds a draft without letting vanilla split or consume a section
     * control. Valid legacy/hex controls are atomic cursor/source units.
     */
    public static String trimDraftSourceToWidth(
            TextRenderer renderer,
            MinecraftClient client,
            String source,
            int maxWidth
    ) {
        if (renderer == null || source == null || source.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (styledLineWidth(renderer, client, source) <= maxWidth) {
            return source;
        }
        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);
        for (int index = 0; index < source.length();) {
            int controlLength = RichChatSectionFormatting.codeLengthAt(source, index);
            index += controlLength > 0
                    ? controlLength
                    : Character.charCount(source.codePointAt(index));
            boundaries.add(index);
        }
        int low = 0;
        int high = boundaries.size() - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            int end = boundaries.get(middle);
            int width = styledRangeWidth(renderer, client, source, 0, end, end);
            if (width <= maxWidth) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return source.substring(0, boundaries.get(low));
    }

    public static int renderStyledLine(DrawContext context, TextRenderer renderer, MinecraftClient client, String line, int x, int y, int maxWidth) {
        return renderStyledLine(context, renderer, client, line, x, y, maxWidth, line == null ? -1 : line.length());
    }

    public static int renderStyledLine(DrawContext context, TextRenderer renderer, MinecraftClient client, String line, int x, int y, int maxWidth, int activeCursor) {
        if (line == null || line.isEmpty()) {
            return x;
        }
        if (!line.startsWith("/")) {
            return RichChatAttachmentRenderer.renderLiveDraftFormattedText(
                    context, renderer, line, x, y, 0xFFE0E0E0, Style.EMPTY
            );
        }
        return renderChunks(
                context,
                renderer,
                KoilCommandAnalysisService.highlightLine(client, line, activeCursor),
                x,
                y,
                maxWidth
        );
    }

    public static int renderStyledRange(DrawContext context, TextRenderer renderer, MinecraftClient client, String fullLine, int from, int to, int activeCursor, int x, int y, int maxWidth) {
        if (fullLine == null || fullLine.isEmpty()) {
            return x;
        }
        int start = Math.max(0, Math.min(fullLine.length(), from));
        int end = Math.max(start, Math.min(fullLine.length(), to));
        if (!fullLine.startsWith("/")) {
            return RichChatAttachmentRenderer.renderLiveDraftFormattedText(
                    context,
                    renderer,
                    fullLine.substring(start, end),
                    x,
                    y,
                    0xFFE0E0E0,
                    Style.EMPTY
            );
        }
        return renderChunks(
                context,
                renderer,
                KoilCommandAnalysisService.highlightRange(client, fullLine, start, end, activeCursor),
                x,
                y,
                maxWidth
        );
    }

    public static int styledRangeWidth(TextRenderer renderer, MinecraftClient client, String fullLine, int from, int to, int activeCursor) {
        if (renderer == null || fullLine == null || fullLine.isEmpty()) {
            return 0;
        }
        int start = Math.max(0, Math.min(fullLine.length(), from));
        int end = Math.max(start, Math.min(fullLine.length(), to));
        if (!fullLine.startsWith("/")) {
            return RichChatAttachmentRenderer.measureLiveDraftFormattedText(
                    renderer,
                    fullLine.substring(start, end),
                    Style.EMPTY
            );
        }
        List<KoilCommandAnalysisService.StyledChunk> chunks = fullLine.startsWith("/")
                ? KoilCommandAnalysisService.highlightRange(client, fullLine, start, end, activeCursor)
                : formattedPreviewChunks(fullLine.substring(start, end));
        int width = 0;
        for (KoilCommandAnalysisService.StyledChunk chunk : chunks) {
            if (chunk != null && chunk.text() != null && !chunk.text().isEmpty()) {
                for (com.spirit.koil.api.chat.RichChatSectionFormatting.Segment segment
                        : com.spirit.koil.api.chat.RichChatSectionFormatting.parseDraft(chunk.text(), chunk.style())) {
                    width += renderer.getWidth(Text.literal(segment.text()).setStyle(segment.style()));
                }
            }
        }
        return width;
    }

    private static int renderChunks(DrawContext context, TextRenderer renderer, List<KoilCommandAnalysisService.StyledChunk> chunks, int x, int y, int maxWidth) {
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
            cursor = RichChatAttachmentRenderer.renderLiveDraftFormattedText(
                    context,
                    renderer,
                    visible,
                    cursor,
                    y,
                    chunk.color(),
                    chunk.style()
            );
        }
        return cursor;
    }

    /** Measures text with the same styled chunks used by the live draft draw. */
    public static int styledLineWidth(TextRenderer renderer, MinecraftClient client, String line) {
        if (renderer == null || line == null || line.isEmpty()) {
            return 0;
        }
        if (!line.startsWith("/")) {
            return RichChatAttachmentRenderer.measureLiveDraftFormattedText(
                    renderer,
                    line,
                    Style.EMPTY
            );
        }
        List<KoilCommandAnalysisService.StyledChunk> chunks =
                KoilCommandAnalysisService.highlightLine(client, line);
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
            collectFormattedChunks(
                    line.substring(markerEnd),
                    com.spirit.koil.api.chat.RichChatStructuralStyleRegistry.apply(
                            com.spirit.koil.api.chat.RichChatStructuralStyleRegistry.Role.HEADING,
                            baseStyle
                    ),
                    out
            );
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
                out.add(new KoilCommandAnalysisService.StyledChunk(
                        inner,
                        com.spirit.koil.api.chat.RichChatStructuralStyleRegistry.apply(
                                com.spirit.koil.api.chat.RichChatStructuralStyleRegistry.Role.SPOILER_HIDDEN,
                                baseStyle
                        ),
                        0xFFE0E0E0
                ));
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
