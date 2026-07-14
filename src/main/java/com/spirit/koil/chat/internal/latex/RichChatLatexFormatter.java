package com.spirit.koil.chat.internal.latex;

import com.spirit.koil.api.chat.RichChatSegment;
import com.spirit.koil.api.chat.RichChatSegmentType;
import com.spirit.koil.chat.internal.LocalMultilineChatBridge;
import com.spirit.koil.chat.internal.RichChatBodyWrapFormatter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RichChatLatexFormatter {
    private static final int MAX_CACHE = 256;
    private static final Map<String, Text> CACHE = new LinkedHashMap<>(MAX_CACHE, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Text> eldest) {
            return size() > MAX_CACHE;
        }
    };

    private static final Map<String, String> REPLACEMENTS = Map.ofEntries(
            Map.entry("\\alpha", "α"),
            Map.entry("\\beta", "β"),
            Map.entry("\\gamma", "γ"),
            Map.entry("\\delta", "δ"),
            Map.entry("\\epsilon", "ε"),
            Map.entry("\\theta", "θ"),
            Map.entry("\\lambda", "λ"),
            Map.entry("\\mu", "μ"),
            Map.entry("\\pi", "π"),
            Map.entry("\\sigma", "σ"),
            Map.entry("\\phi", "φ"),
            Map.entry("\\omega", "ω"),
            Map.entry("\\int", "∫"),
            Map.entry("\\sum", "Σ"),
            Map.entry("\\prod", "Π"),
            Map.entry("\\sqrt", "√"),
            Map.entry("\\infty", "∞"),
            Map.entry("\\leq", "≤"),
            Map.entry("\\geq", "≥"),
            Map.entry("\\neq", "≠"),
            Map.entry("\\times", "×"),
            Map.entry("\\cdot", "·"),
            Map.entry("\\rightarrow", "→"),
            Map.entry("\\leftarrow", "←"),
            Map.entry("\\Rightarrow", "⇒"),
            Map.entry("\\Leftarrow", "⇐")
    );

    private RichChatLatexFormatter() {
    }

    public static Text format(Text message) {
        if (message == null) {
            return message;
        }
        String visible = message.getString();
        if (visible.indexOf('$') < 0 && visible.indexOf("\\(") < 0 && visible.indexOf("\\[") < 0 && visible.indexOf("```latex") < 0 && visible.indexOf("```tex") < 0) {
            return message;
        }

        String cacheKey = RichChatLatexTextureCache.currentChatContentWidth() + ":" + visible;
        synchronized (CACHE) {
            Text cached = CACHE.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        RichChatLatexDetector.Result result = RichChatLatexDetector.detect(visible);
        if (!result.hasLatex()) {
            return message;
        }

        MutableText formatted = Text.empty();
        List<RichChatSegment> segments = result.segments();
        int pendingInlineSafetyRows = 0;
        for (int i = 0; i < segments.size(); i++) {
            RichChatSegment segment = segments.get(i);
            boolean hasFollowingVisibleText = hasFollowingVisibleText(segments, i + 1);
            if (segment.type() == RichChatSegmentType.TEXT || segment.type() == RichChatSegmentType.MULTILINE_TEXT) {
                formatted.append(Text.literal(segment.text()));
                if (textEndsCurrentLine(segment.text())) {
                    pendingInlineSafetyRows = 0;
                }
            } else if (segment.type() == RichChatSegmentType.LATEX_INLINE) {
                if (needsOwnLine(segment.text())) {
                    appendInlineSafetyReservation(formatted, pendingInlineSafetyRows, true);
                    pendingInlineSafetyRows = 0;
                    formatted.append(formatOwnLine(segment.text(), formatted.getString(), ownLineMode(segment.text()), hasFollowingVisibleText));
                } else {
                    formatted.append(formatInline(segment.text(), formatted.getString()));
                    pendingInlineSafetyRows = Math.max(
                            pendingInlineSafetyRows,
                            RichChatLatexTextureCache.inlineSafetyBlankLines(segment.text(), currentFontHeight())
                    );
                }
            } else if (segment.type() == RichChatSegmentType.LATEX_BLOCK) {
                appendInlineSafetyReservation(formatted, pendingInlineSafetyRows, true);
                pendingInlineSafetyRows = 0;
                formatted.append(formatBlock("LaTeX", segment.text(), formatted.getString(), hasFollowingVisibleText));
            } else if (segment.type() == RichChatSegmentType.LATEX_DOCUMENT) {
                appendInlineSafetyReservation(formatted, pendingInlineSafetyRows, true);
                pendingInlineSafetyRows = 0;
                formatted.append(formatBlock("LaTeX Document", segment.text(), formatted.getString(), hasFollowingVisibleText));
            }
        }
        appendInlineSafetyReservation(formatted, pendingInlineSafetyRows, false);

        synchronized (CACHE) {
            CACHE.put(cacheKey, formatted);
        }
        return formatted;
    }

    private static Text formatInline(String source, String currentOutput) {
        String marker = RichChatLatexTextureCache.marker(source, RichChatLatexTextureCache.Mode.INLINE);
        if (shouldWrapBeforeFormula(currentOutput, source, RichChatLatexTextureCache.Mode.INLINE)) {
            String indent = bodyIndentForCurrentLine(currentOutput);
            return Text.literal("\n")
                    .append(Text.literal(indent))
                    .append(Text.literal(marker).formatted(Formatting.WHITE));
        }
        return Text.literal(marker).formatted(Formatting.WHITE);
    }

    private static Text formatOwnLine(String source, String currentOutput, RichChatLatexTextureCache.Mode mode, boolean hasFollowingVisibleText) {
        String indent = bodyIndentForCurrentLine(currentOutput);
        int maxFormulaWidth = availableFormulaWidth(indent);
        String marker = RichChatLatexTextureCache.marker(source, mode, 0, maxFormulaWidth);
        boolean hasTextBefore = currentLineHasText(currentOutput);
        MutableText text = Text.empty();
        if (hasTextBefore) {
            text.append(Text.literal("\n"));
        }
        text.append(Text.literal(indent))
                .append(Text.literal(marker).formatted(Formatting.WHITE));
        appendVerticalReservation(text, source, mode, indent, maxFormulaWidth, hasFollowingVisibleText);
        if (hasFollowingVisibleText && !indent.isEmpty()) {
            text.append(Text.literal(indent));
        }
        return text;
    }

    private static Text formatBlock(String label, String source, String currentOutput, boolean hasFollowingVisibleText) {
        boolean hasTextBefore = currentLineHasText(currentOutput);
        String indent = bodyIndentForCurrentLine(currentOutput);
        int maxFormulaWidth = availableFormulaWidth(indent);
        if ("LaTeX".equals(label)) {
            MutableText text = Text.empty();
            if (hasTextBefore) {
                text.append(Text.literal("\n"));
            }
            if (!indent.isEmpty()) {
                text.append(Text.literal(indent));
            }
            text.append(Text.literal(RichChatLatexTextureCache.marker(source, RichChatLatexTextureCache.Mode.BLOCK, 0, maxFormulaWidth)).formatted(Formatting.WHITE));
            appendVerticalReservation(text, source, RichChatLatexTextureCache.Mode.BLOCK, indent, maxFormulaWidth, hasFollowingVisibleText);
            if (hasFollowingVisibleText && !indent.isEmpty()) {
                text.append(Text.literal(indent));
            }
            return text;
        }
        MutableText text = Text.empty();
        if (hasTextBefore) {
            text.append(Text.literal("\n"));
        }
        return text.append(Text.literal("[" + label + "]").formatted(Formatting.AQUA, Formatting.BOLD))
                .append(Text.literal("\n  ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(simplify(source)).formatted(Formatting.WHITE))
                .append(Text.literal("\n[/"+ label + "]").formatted(Formatting.DARK_AQUA));
    }

    private static void appendVerticalReservation(MutableText text, String source, RichChatLatexTextureCache.Mode mode, String indent, int maxFormulaWidth, boolean hasFollowingVisibleText) {
        int blankLines = RichChatLatexTextureCache.reservedBlankLines(source, mode, currentFontHeight(), maxFormulaWidth);
        for (int i = 0; i < blankLines; i++) {
            text.append(Text.literal("\n"));
            if (indent != null && !indent.isEmpty()) {
                text.append(Text.literal(indent));
            }
            text.append(Text.literal(RichChatLatexTextureCache.marker(source, mode, i + 1, maxFormulaWidth)).formatted(Formatting.WHITE));
        }
        if (hasFollowingVisibleText) {
            text.append(Text.literal("\n"));
        }
    }

    private static void appendInlineSafetyReservation(MutableText text, int blankLines, boolean hasFollowingVisibleText) {
        for (int i = 0; i < blankLines; i++) {
            text.append(Text.literal("\n "));
        }
        if (blankLines > 0 && hasFollowingVisibleText) {
            text.append(Text.literal("\n"));
        }
    }

    private static boolean textEndsCurrentLine(String text) {
        return text != null && text.indexOf('\n') >= 0;
    }

    private static boolean hasFollowingVisibleText(List<RichChatSegment> segments, int startIndex) {
        if (segments == null) {
            return false;
        }
        for (int i = Math.max(0, startIndex); i < segments.size(); i++) {
            RichChatSegment segment = segments.get(i);
            if (segment == null) {
                continue;
            }
            if (segment.type() == RichChatSegmentType.LATEX_INLINE
                    || segment.type() == RichChatSegmentType.LATEX_BLOCK
                    || segment.type() == RichChatSegmentType.LATEX_DOCUMENT) {
                return true;
            }
            String text = segment.text();
            if (text != null && !text.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldWrapBeforeFormula(String currentOutput, String source, RichChatLatexTextureCache.Mode mode) {
        if (!currentLineHasText(currentOutput)) {
            return false;
        }
        int lineWidth = currentLineWidth(currentOutput);
        int formulaWidth = RichChatLatexTextureCache.layoutAdvance(source, mode);
        return lineWidth + formulaWidth > RichChatBodyWrapFormatter.currentWrapWidth();
    }

    private static int currentLineWidth(String currentOutput) {
        if (currentOutput == null || currentOutput.isEmpty()) {
            return 0;
        }
        int lineStart = currentOutput.lastIndexOf('\n') + 1;
        String line = currentOutput.substring(lineStart);
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            TextRenderer renderer = client == null ? null : client.textRenderer;
            if (renderer != null) {
                return renderer.getWidth(line);
            }
        } catch (Throwable ignored) {
        }
        return line.length() * 6;
    }

    private static int availableFormulaWidth(String indent) {
        int used = textWidth(indent);
        return Math.max(24, RichChatBodyWrapFormatter.currentWrapWidth() - used);
    }

    private static int textWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            TextRenderer renderer = client == null ? null : client.textRenderer;
            if (renderer != null) {
                return renderer.getWidth(text);
            }
        } catch (Throwable ignored) {
        }
        return text.length() * 6;
    }

    private static int currentFontHeight() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            TextRenderer renderer = client == null ? null : client.textRenderer;
            if (renderer != null) {
                return Math.max(1, renderer.fontHeight);
            }
        } catch (Throwable ignored) {
        }
        return 9;
    }

    private static boolean needsOwnLine(String source) {
        if (source == null) {
            return false;
        }
        String text = source.toLowerCase();
        return text.contains("\\frac")
                || text.contains("\\dfrac")
                || text.contains("\\tfrac")
                || text.contains("\\over")
                || text.contains("\\sum")
                || text.contains("\\prod")
                || text.contains("\\int")
                || text.contains("\\lim")
                || text.contains("\\sqrt")
                || text.contains("\\begin")
                || text.contains("matrix")
                || text.contains("cases");
    }

    private static RichChatLatexTextureCache.Mode ownLineMode(String source) {
        if (source == null) {
            return RichChatLatexTextureCache.Mode.TALL_INLINE;
        }
        String text = source.toLowerCase();
        int complexParts = count(text, "\\frac") + count(text, "\\sum") + count(text, "\\prod") + count(text, "\\int") + count(text, "\\sqrt") + count(text, "\\begin");
        if (source.length() > 72 || complexParts >= 3) {
            return RichChatLatexTextureCache.Mode.BLOCK;
        }
        return RichChatLatexTextureCache.Mode.TALL_INLINE;
    }

    private static int count(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static String bodyIndentForCurrentLine(String currentOutput) {
        if (currentOutput == null || currentOutput.isEmpty()) {
            return "";
        }
        int lineStart = currentOutput.lastIndexOf('\n') + 1;
        String line = currentOutput.substring(lineStart);
        String prefix = RichChatBodyWrapFormatter.detectVisibleBodyPrefix(line);
        if (!prefix.isEmpty()) {
            return LocalMultilineChatBridge.indentForPrefix(prefix);
        }
        return "";
    }

    private static boolean currentLineHasText(String currentOutput) {
        if (currentOutput == null || currentOutput.isEmpty()) {
            return false;
        }
        int lineStart = currentOutput.lastIndexOf('\n') + 1;
        return !currentOutput.substring(lineStart).trim().isEmpty();
    }

    private static String simplify(String source) {
        String text = source == null ? "" : source.trim();
        text = replaceFractions(text);
        for (Map.Entry<String, String> entry : REPLACEMENTS.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        text = text.replace("\\left", "")
                .replace("\\right", "")
                .replace("\\,", " ")
                .replace("\\;", " ")
                .replace("\\:", " ")
                .replace("\\!", "")
                .replaceAll("\\s+", " ")
                .trim();
        text = replaceScripts(text, '^');
        text = replaceScripts(text, '_');
        text = text.replace("{", "").replace("}", "");
        return text.isBlank() ? "render unavailable" : text;
    }

    private static String replaceFractions(String text) {
        String current = text;
        int guard = 0;
        while (guard++ < 32) {
            int index = current.indexOf("\\frac{");
            if (index < 0) {
                return current;
            }
            int numeratorStart = index + "\\frac".length();
            int numeratorEnd = findBalancedEnd(current, numeratorStart);
            if (numeratorEnd < 0 || numeratorEnd + 1 >= current.length() || current.charAt(numeratorEnd + 1) != '{') {
                return current;
            }
            int denominatorStart = numeratorEnd + 1;
            int denominatorEnd = findBalancedEnd(current, denominatorStart);
            if (denominatorEnd < 0) {
                return current;
            }
            String numerator = current.substring(numeratorStart + 1, numeratorEnd);
            String denominator = current.substring(denominatorStart + 1, denominatorEnd);
            current = current.substring(0, index) + "(" + numerator + ")/(" + denominator + ")" + current.substring(denominatorEnd + 1);
        }
        return current;
    }

    private static String replaceScripts(String text, char marker) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == marker && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '{') {
                    int end = findBalancedEnd(text, i + 1);
                    if (end > i) {
                        builder.append(marker == '^' ? "^(" : "_(").append(text, i + 2, end).append(')');
                        i = end;
                        continue;
                    }
                } else {
                    builder.append(marker).append(next);
                    i++;
                    continue;
                }
            }
            builder.append(current);
        }
        return builder.toString();
    }

    private static int findBalancedEnd(String text, int openIndex) {
        if (openIndex < 0 || openIndex >= text.length() || text.charAt(openIndex) != '{') {
            return -1;
        }
        int depth = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
}
