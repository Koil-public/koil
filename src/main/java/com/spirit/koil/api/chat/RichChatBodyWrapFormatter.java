package com.spirit.koil.api.chat;

import com.spirit.koil.api.chat.latex.RichChatLatexTextureCache;
import com.spirit.koil.api.chat.upload.LocalRichAttachmentBridge;
import com.spirit.koil.api.model.chat.ModelChatIdentity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RichChatBodyWrapFormatter {
    private static final int MAX_CACHE = 256;
    // Koil and native ChatHud use the same TextRenderer and measured 40-320px
    // Chat Width contract. Only retain a tiny glyph-edge guard; the previous
    // filter/prefix constants could remove about half of a 320px chat row.
    private static final int WRAP_EDGE_GUARD = 2;
    // A single unbroken token is cut by trimToWidth, then can be measured a
    // fraction wider by ChatHud's native glyph path. Keep a small hard-token
    // buffer without making ordinary word-wrapped text narrower.
    private static final int HARD_TOKEN_WRAP_SAFETY = 6;
    private static final Pattern MASKED_LINK = Pattern.compile(
            "\\[([^\\]\\n]{1,256})]\\(([^)\\n]{1,2048})\\)"
    );
    private static final Map<String, Text> CACHE = new LinkedHashMap<>(MAX_CACHE, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Text> eldest) {
            return size() > MAX_CACHE;
        }
    };

    private RichChatBodyWrapFormatter() {
    }

    public static Text format(Text message) {
        return format(message, RichChatRowType.UNKNOWN);
    }

    public static Text format(Text message, RichChatRowType rowType) {
        if (message == null) {
            return null;
        }
        String visible = message.getString();
        if (visible == null || visible.isBlank()) {
            return message;
        }

        int wrapWidth = currentWrapWidth();
        RichChatRowType safeType = rowType == null ? RichChatRowType.UNKNOWN : rowType;
        String cacheKey = safeType.name()
                + ":" + wrapWidth
                + ":" + (RichChatPrivateMessageBridge.filterEnabled() ? "filtered" : "unfiltered")
                + ":" + visible;
        synchronized (CACHE) {
            Text cached = CACHE.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        String wrapped = wrapVisibleText(visible, wrapWidth, safeType);
        if (wrapped.equals(visible)) {
            return message;
        }

        Text formatted = Text.literal(wrapped);
        synchronized (CACHE) {
            CACHE.put(cacheKey, formatted);
        }
        return formatted;
    }

    /**
     * Wraps confirmation/detail rows without requiring a chat identity prefix.
     * Every continuation repeats the standard `-#` semantic prefix. The live
     * Rich Chat renderer consumes that prefix and applies the same scale and
     * color on every visual row, without leaking private-use marker glyphs.
     */
    public static Text formatConfirmationDetails(Text message, int wrapWidth) {
        if (message == null || message.getString().isBlank()) return message;
        TextRenderer renderer = textRenderer();
        if (renderer == null) return message;
        String[] lines = message.getString().replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder output = new StringBuilder();
        boolean changed = false;
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) output.append('\n');
            String line = lines[index];
            int whitespace = leadingWhitespaceWidth(line);
            String leading = line.substring(0, whitespace);
            String content = line.substring(whitespace);
            if (!content.startsWith("-# ") || measuredWidth(renderer, line) <= wrapWidth) {
                output.append(line);
                continue;
            }
            String body = content.substring(3);
            int bodyWidth = Math.max(8, (int) Math.floor((wrapWidth - renderer.getWidth(leading)) / 0.82F)
                    - renderer.getWidth("-# ") - WRAP_EDGE_GUARD);
            List<String> parts = carryInlineFormatting(wrapBody(body, renderer, bodyWidth, bodyWidth));
            if (parts.size() <= 1) {
                output.append(line);
                continue;
            }
            output.append(leading).append("-# ").append(parts.get(0));
            for (int part = 1; part < parts.size(); part++) {
                output.append('\n').append(RichChatStructuralContinuation.subtextPrefix(leading)).append(parts.get(part));
            }
            changed = true;
        }
        return changed ? Text.literal(output.toString()) : message;
    }

    public static int currentWrapWidth() {
        // This must remain the real native ChatHud boundary. Artificially
        // enlarging it makes Minecraft wrap Koil's already-wrapped rows a
        // second time, which presents as an empty row between every line.
        return RichChatLatexTextureCache.currentChatContentWidth();
    }

    private static String wrapVisibleText(String visible, int wrapWidth, RichChatRowType rowType) {
        String[] lines = visible.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder result = new StringBuilder(visible.length() + 32);
        boolean changed = false;
        String persistentModelIndent = rowType == RichChatRowType.MODEL_RESPONSE
                ? LocalMultilineChatBridge.indentForPrefix(ModelChatIdentity.PREFIX)
                : "";
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append('\n');
            }
            String sourceLine = lines[i];
            if (i > 0
                    && !persistentModelIndent.isEmpty()
                    && !sourceLine.isBlank()
                    && !sourceLine.startsWith(persistentModelIndent)
                    && !sourceLine.startsWith(ModelChatIdentity.PREFIX)) {
                sourceLine = persistentModelIndent + sourceLine;
                changed = true;
            }
            String wrapped = wrapLine(sourceLine, wrapWidth, rowType);
            changed |= !wrapped.equals(sourceLine);
            result.append(wrapped);
            int spacerLines = headerSpacerLines(lines, i);
            for (int spacer = 0; spacer < spacerLines; spacer++) {
                result.append('\n');
                changed = true;
            }
        }
        return changed ? result.toString() : visible;
    }

    private static String wrapLine(String line, int wrapWidth, RichChatRowType rowType) {
        if (line == null || line.isEmpty()) {
            return line == null ? "" : line;
        }
        String markerPrefix = RichChatPrivateMessageBridge.leadingMarkerPrefix(line);
        String visibleLine = markerPrefix.isEmpty() ? line : line.substring(markerPrefix.length());
        if (LocalRichAttachmentBridge.containsMarker(visibleLine)
                || RichChatCodeBlockBridge.containsMarker(visibleLine)
                || RichChatTableBridge.containsMarker(visibleLine)
                || RichChatMaskedLinkBridge.containsMarker(visibleLine)) {
            return line;
        }
        TextRenderer renderer = textRenderer();
        if (renderer == null) {
            return line;
        }
        // MixinChatHud compensates native wrapping for the exact measured
        // width of Koil's hidden PM control prefix. It is therefore excluded
        // from this visible-content calculation instead of guessed twice.
        int effectiveWrapWidth = Math.max(8, wrapWidth);
        String measuredPrefix = rowType != null && rowType.usesBodyIndent()
                ? detectVisibleBodyPrefix(visibleLine, rowType)
                : "";
        String measuredBody = measuredPrefix.isEmpty()
                ? visibleLine
                : visibleLine.substring(measuredPrefix.length());
        RichChatHeadingLayout.Heading measuredHeading = RichChatHeadingLayout.detect(measuredBody);
        int boldReserve = boldWrapReserve(renderer, measuredBody);
        int measuredModelPadding = ModelChatIdentity.PREFIX.equals(measuredPrefix)
                ? ModelChatIdentity.alignmentPadding(
                renderer.getWidth(measuredPrefix),
                renderer.getWidth(" ")
        )
                : 0;
        int visibleMeasuredWidth = measuredHeading == null
                ? measuredWidth(renderer, visibleLine) + measuredModelPadding + boldReserve
                : renderer.getWidth(measuredPrefix) + measuredModelPadding
                + Math.round(measuredWidth(renderer, measuredHeading.content()) * measuredHeading.scale())
                + boldReserve;
        if (visibleMeasuredWidth <= effectiveWrapWidth) {
            return line;
        }

        if (rowType == null || !rowType.usesBodyIndent()) {
            return line;
        }

        String modelIndent = rowType == RichChatRowType.MODEL_RESPONSE
                ? LocalMultilineChatBridge.indentForPrefix(ModelChatIdentity.PREFIX)
                : "";
        boolean modelContinuation = !modelIndent.isEmpty() && visibleLine.startsWith(modelIndent);
        String prefix = modelContinuation
                ? modelIndent
                : detectVisibleBodyPrefix(visibleLine, rowType);
        if (prefix.isEmpty()) {
            return line;
        }

        // An already-indented model row is a continuation of the model identity, not a new
        // nested prefix. Reusing the exact indent prevents cumulative
        // rightward drift when that logical row wraps again.
        String indent = modelContinuation
                ? modelIndent
                : LocalMultilineChatBridge.indentForPrefix(prefix);
        String body = visibleLine.substring(prefix.length());
        StructuralPrefix structural = structuralPrefix(body);
        body = structural.content();
        RichChatHeadingLayout.Heading heading = RichChatHeadingLayout.detect(body);
        String wrappedBody = heading == null ? body : heading.content();
        int safetyMargin = WRAP_EDGE_GUARD;
        int structuralWidth = structural.visibleAdvance(renderer);
        int renderedPrefixWidth = renderer.getWidth(prefix);
        if (!modelContinuation && ModelChatIdentity.PREFIX.equals(prefix)) {
            renderedPrefixWidth = ModelChatIdentity.alignedPrefixAdvance(
                    renderedPrefixWidth,
                    renderer.getWidth(" ")
            );
        }
        int firstWidth = Math.max(6, effectiveWrapWidth - renderedPrefixWidth - structuralWidth - safetyMargin);
        int continuationWidth = Math.max(6, effectiveWrapWidth - renderer.getWidth(indent) - structuralWidth - safetyMargin);
        if (heading != null) {
            firstWidth = Math.max(6, (int) Math.floor(firstWidth / heading.scale()));
            continuationWidth = Math.max(6, (int) Math.floor(continuationWidth / heading.scale()));
        } else if (structural.contentScale() < 0.999F) {
            firstWidth = Math.max(6, (int) Math.floor(firstWidth / structural.contentScale()));
            continuationWidth = Math.max(6, (int) Math.floor(continuationWidth / structural.contentScale()));
        }
        int bodyBoldReserve = boldWrapReserve(renderer, wrappedBody);
        firstWidth = Math.max(6, firstWidth - bodyBoldReserve);
        continuationWidth = Math.max(6, continuationWidth - bodyBoldReserve);
        if (firstWidth <= 1 || continuationWidth <= 1) {
            return line;
        }
        wrappedBody = splitOversizedMaskedLabels(
                wrappedBody,
                renderer,
                Math.max(6, Math.min(firstWidth, continuationWidth))
        );

        List<String> parts = carryInlineFormatting(
                wrapBody(wrappedBody, renderer, firstWidth, continuationWidth)
        );
        if (parts.size() <= 1) {
            return line;
        }

        StringBuilder builder = new StringBuilder(line.length() + indent.length() * Math.max(0, parts.size() - 1) + markerPrefix.length() * parts.size());
        builder.append(markerPrefix).append(prefix).append(structural.marker());
        appendHeadingPart(builder, heading, parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            if (heading != null) {
                for (int spacer = 0; spacer < heading.spacerLines(); spacer++) {
                    builder.append('\n');
                }
            }
            builder.append('\n').append(markerPrefix).append(indent).append(structural.continuationMarker());
            appendHeadingPart(builder, heading, parts.get(i));
        }
        return builder.toString();
    }

    /**
     * A masked link is atomic to the normal tokenizer so its hidden target is
     * never split. When the visible label itself is wider than a row, emit
     * adjacent label fragments with the same target. They remain one visual
     * phrase and every fragment retains the same click behavior.
     */
    private static String splitOversizedMaskedLabels(String body, TextRenderer renderer, int width) {
        if (body == null || body.indexOf('[') < 0 || renderer == null || width <= 1) {
            return body == null ? "" : body;
        }
        Matcher matcher = MASKED_LINK.matcher(body);
        StringBuilder output = new StringBuilder(body.length());
        int cursor = 0;
        boolean changed = false;
        while (matcher.find()) {
            output.append(body, cursor, matcher.start());
            String label = matcher.group(1);
            String target = matcher.group(2);
            if (renderer.getWidth(visibleMarkupText(label)) <= width) {
                output.append(matcher.group());
            } else {
                String remaining = label;
                while (!remaining.isEmpty()) {
                    String fragment = renderer.trimToWidth(remaining, Math.max(1, width - HARD_TOKEN_WRAP_SAFETY));
                    if (fragment.isEmpty()) {
                        fragment = remaining.substring(0, 1);
                    }
                    output.append('[').append(fragment).append("](").append(target).append(')');
                    remaining = remaining.substring(fragment.length());
                }
                changed = true;
            }
            cursor = matcher.end();
        }
        if (!changed) {
            return body;
        }
        output.append(body, cursor, body.length());
        return output.toString();
    }

    private static StructuralPrefix structuralPrefix(String body) {
        if (body == null || body.isEmpty()) {
            return new StructuralPrefix("", "", "", 1.0F, false, "");
        }
        int whitespace = leadingWhitespaceWidth(body);
        String leading = body.substring(0, whitespace);
        String content = body.substring(whitespace);
        if (content.startsWith("-# ")) {
            return new StructuralPrefix(
                    leading + "-# ",
                    content.substring(3),
                    leading,
                    0.82F,
                    true,
                    RichChatStructuralContinuation.subtextPrefix(leading)
            );
        }
        if (RichChatStructuralContinuation.isSubtext(content, 0)) {
            return new StructuralPrefix(
                    RichChatStructuralContinuation.subtextPrefix(leading),
                    content.substring(1),
                    leading,
                    0.82F,
                    true,
                    RichChatStructuralContinuation.subtextPrefix(leading)
            );
        }
        if (content.startsWith("> ")) {
            return new StructuralPrefix(leading + "> ", content.substring(2), leading, 1.0F, false, leading + "> ");
        }
        return new StructuralPrefix("", body, "", 1.0F, false, "");
    }

    private static void appendHeadingPart(
            StringBuilder output,
            RichChatHeadingLayout.Heading heading,
            String part
    ) {
        if (heading != null) {
            output.append(heading.leadingWhitespace()).append(heading.marker());
        }
        output.append(part);
    }

    private static List<String> wrapBody(String body, TextRenderer renderer, int firstWidth, int continuationWidth) {
        List<String> parts = new ArrayList<>();
        String remaining = body == null ? "" : body;
        boolean first = true;
        while (!remaining.isEmpty()) {
            int limit = first ? firstWidth : continuationWidth;
            if (measuredWidth(renderer, remaining) <= limit) {
                parts.add(remaining);
                break;
            }
            String rawSlice = fittedSlice(remaining, renderer, limit);
            if (rawSlice.isEmpty()) {
                rawSlice = remaining.substring(0, 1);
            }
            String part = rawSlice.stripTrailing();
            if (!part.isEmpty()) {
                parts.add(part);
            }
            remaining = remaining.substring(Math.min(remaining.length(), rawSlice.length())).stripLeading();
            first = false;
        }
        return parts;
    }

    /**
     * Reopens formatting spans on wrapped continuation rows. Rich Chat markup
     * is parsed per rendered row, so a delimiter that starts on one row and
     * ends on another must be closed/reopened at the wrap boundary.
     */
    private static List<String> carryInlineFormatting(List<String> rawParts) {
        if (rawParts == null || rawParts.size() <= 1) {
            return rawParts == null ? List.of() : rawParts;
        }
        List<String> formatted = new ArrayList<>(rawParts.size());
        Deque<String> active = new ArrayDeque<>();
        String sectionPrefix = "";
        for (String rawPart : rawParts) {
            String part = rawPart == null ? "" : rawPart;
            StringBuilder row = new StringBuilder(part.length() + active.size() * 6 + sectionPrefix.length());
            row.append(sectionPrefix);
            for (String marker : active) {
                row.append(marker);
            }
            updateActiveFormatting(part, active);
            sectionPrefix = RichChatSectionFormatting.continuationPrefix(sectionPrefix + part);
            row.append(part);
            active.descendingIterator().forEachRemaining(row::append);
            formatted.add(row.toString());
        }
        return List.copyOf(formatted);
    }

    private static void updateActiveFormatting(String text, Deque<String> active) {
        text = formattingScanText(text);
        int cursor = 0;
        while (text != null && cursor < text.length()) {
            FormattingMarker next = nextFormattingMarker(text, cursor);
            if (next == null) {
                break;
            }
            if (!active.isEmpty() && active.peekLast().equals(next.marker())) {
                active.removeLast();
            } else {
                active.addLast(next.marker());
            }
            cursor = next.index() + next.marker().length();
        }
    }

    private static FormattingMarker nextFormattingMarker(String text, int from) {
        int best = Integer.MAX_VALUE;
        String selected = null;
        for (String marker : List.of("***", "**", "__", "--", "||", "`", "*")) {
            int index = text.indexOf(marker, Math.max(0, from));
            if (index >= 0 && index < best) {
                best = index;
                selected = marker;
            }
        }
        return selected == null ? null : new FormattingMarker(best, selected);
    }

    private static String formattingScanText(String text) {
        if (text == null || text.indexOf('[') < 0) {
            return text == null ? "" : text;
        }
        Matcher matcher = MASKED_LINK.matcher(text);
        StringBuffer visible = new StringBuffer(text.length());
        while (matcher.find()) {
            matcher.appendReplacement(visible, Matcher.quoteReplacement(matcher.group(1)));
        }
        matcher.appendTail(visible);
        return visible.toString();
    }

    private static String fittedSlice(String text, TextRenderer renderer, int limit) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int index = 0;
        int currentWidth = 0;
        int lastBreak = -1;
        while (index < text.length()) {
            Token token = nextToken(text, index, renderer);
            if (token == null || token.end() <= index) {
                break;
            }
            if (token.whitespace()) {
                lastBreak = token.end();
            }
            if (currentWidth + token.width() <= limit) {
                currentWidth += token.width();
                index = token.end();
                continue;
            }
            if (lastBreak > 0) {
                return text.substring(0, lastBreak);
            }
            if (index > 0) {
                return text.substring(0, index);
            }
            if (token.marker()) {
                return text.substring(0, token.end());
            }
            int leadingMarkerLength = leadingFormattingMarkerLength(token.text());
            int leadingMarkupWidth = leadingMarkerLength <= 0
                    ? 0
                    : renderer.getWidth(token.text().substring(0, leadingMarkerLength));
            String fitted = renderer.trimToWidth(
                    token.text(),
                    Math.max(1, limit - HARD_TOKEN_WRAP_SAFETY + leadingMarkupWidth)
            );
            if (leadingMarkerLength > 0 && fitted.length() <= leadingMarkerLength
                    && token.text().length() > leadingMarkerLength) {
                fitted = token.text().substring(0, leadingMarkerLength + 1);
            }
            if (fitted.isEmpty()) {
                fitted = token.text().substring(0, 1);
            }
            return fitted;
        }
        return text.substring(0, Math.max(0, index));
    }

    private static Token nextToken(String text, int index, TextRenderer renderer) {
        if (text == null || index < 0 || index >= text.length()) {
            return null;
        }
        RichChatLatexTextureCache.Marker marker = RichChatLatexTextureCache.nextMarker(text, index);
        if (marker != null && marker.start() == index) {
            return new Token(text.substring(index, marker.end()), marker.end(), markerWidth(marker), false, true);
        }
        Matcher link = MASKED_LINK.matcher(text);
        link.region(index, text.length());
        if (link.lookingAt()) {
            return new Token(
                    text.substring(index, link.end()),
                    link.end(),
                    renderer.getWidth(visibleMarkupText(link.group(1))),
                    false,
                    true
            );
        }
        char current = text.charAt(index);
        if (Character.isWhitespace(current)) {
            int end = index + 1;
            while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            String token = text.substring(index, end);
            return new Token(token, end, renderer.getWidth(token), true, false);
        }
        int end = index + 1;
        while (end < text.length()) {
            RichChatLatexTextureCache.Marker nextMarker = RichChatLatexTextureCache.nextMarker(text, end);
            if (nextMarker != null && nextMarker.start() == end) {
                break;
            }
            if (Character.isWhitespace(text.charAt(end))) {
                break;
            }
            end++;
        }
        String token = text.substring(index, end);
        return new Token(token, end, renderer.getWidth(visibleMarkupText(token)), false, false);
    }

    private static int measuredWidth(TextRenderer renderer, String text) {
        if (renderer == null || text == null || text.isEmpty()) {
            return 0;
        }
        String visibleText = visibleMarkupText(text);
        if (!RichChatLatexTextureCache.containsMarker(visibleText)) {
            return renderer.getWidth(visibleText);
        }
        int width = 0;
        int index = 0;
        RichChatLatexTextureCache.Marker marker;
        while ((marker = RichChatLatexTextureCache.nextMarker(visibleText, index)) != null) {
            if (marker.start() > index) {
                width += renderer.getWidth(visibleText.substring(index, marker.start()));
            }
            width += markerWidth(marker);
            index = marker.end();
        }
        if (index < visibleText.length()) {
            width += renderer.getWidth(visibleText.substring(index));
        }
        return width;
    }

    /**
     * Koil has already split Rich Chat rows by their painted width. Vanilla's
     * final text splitter still sees source delimiters such as ** and the
     * hidden -# marker, so grant only the largest per-row difference between
     * source width and Koil's actual painted width.
     */
    public static int nativeWrapWidthAdjustment(TextRenderer renderer, String text) {
        if (renderer == null || text == null || text.isBlank()) {
            return 0;
        }
        int adjustment = 0;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String sourceLine : lines) {
            if (sourceLine == null || sourceLine.isBlank()) {
                continue;
            }
            String line = RichChatPrivateMessageBridge.stripVisibleMarkersForLayout(sourceLine);
            String prefix = detectVisibleBodyPrefix(line);
            String body = prefix.isEmpty() ? line : line.substring(prefix.length());
            StructuralPrefix structural = structuralPrefix(body);
            RichChatHeadingLayout.Heading heading = RichChatHeadingLayout.detect(structural.content());
            String content = heading == null ? structural.content() : heading.content();
            float scale = heading == null ? structural.contentScale() : heading.scale();
            int paintedWidth = renderer.getWidth(prefix)
                    + structural.visibleAdvance(renderer)
                    + Math.round(measuredWidth(renderer, content) * scale);
            adjustment = Math.max(adjustment, Math.max(0, renderer.getWidth(line) - paintedWidth));
        }
        return Math.min(256, adjustment);
    }

    private static int leadingFormattingMarkerLength(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        int sectionLength = 0;
        int codeLength;
        while ((codeLength = RichChatSectionFormatting.codeLengthAt(token, sectionLength)) > 0) {
            sectionLength += codeLength;
        }
        for (String marker : List.of("***", "**", "__", "--", "||", "`", "*")) {
            if (token.startsWith(marker, sectionLength)) {
                return sectionLength + marker.length();
            }
        }
        return sectionLength;
    }

    private static int boldWrapReserve(TextRenderer renderer, String text) {
        if (renderer == null || !containsClosedBoldSpan(text)) {
            return 0;
        }
        return renderer.getWidth(Text.literal("0000").formatted(Formatting.BOLD));
    }

    private static boolean containsClosedBoldSpan(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int opening = text.indexOf("**");
        return opening >= 0 && text.indexOf("**", opening + 2) >= 0;
    }

    private static String visibleMarkupText(String text) {
        String visible = RichChatSectionFormatting.stripCodes(formattingScanText(text));
        return visible
                .replace("***", "")
                .replace("**", "")
                .replace("__", "")
                .replace("--", "")
                .replace("||", "")
                .replace("`", "")
                .replace("*", "");
    }

    private static int markerWidth(RichChatLatexTextureCache.Marker marker) {
        if (marker == null || marker.entry() == null) {
            return 0;
        }
        return Math.max(1, marker.entry().advanceWidth());
    }

    public static String detectVisibleBodyPrefix(String line) {
        return detectVisibleBodyPrefix(line, null);
    }

    public static String detectVisibleBodyPrefix(String line, RichChatRowType rowType) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        int leadingWhitespace = leadingWhitespaceWidth(line);
        if (leadingWhitespace > 0) {
            return line.substring(0, leadingWhitespace);
        }
        boolean allowNamedPrefix = rowType == null || rowType == RichChatRowType.PLAYER_CHAT || rowType == RichChatRowType.PRIVATE_MESSAGE;
        boolean allowPrivatePrefix = rowType == null || rowType == RichChatRowType.PRIVATE_MESSAGE;
        boolean allowModelPrefix = rowType == null || rowType == RichChatRowType.MODEL_RESPONSE;
        if (allowModelPrefix && line.startsWith(ModelChatIdentity.PREFIX)) {
            return ModelChatIdentity.PREFIX;
        }
        if (allowNamedPrefix && line.startsWith("<")) {
            int end = line.indexOf("> ");
            if (end >= 0) {
                return line.substring(0, end + 2);
            }
        }
        if (!allowPrivatePrefix) {
            return "";
        }
        String[] privatePrefixes = {
                "You whisper to ",
                " whispers to you: ",
                "To ",
                "From "
        };
        for (String marker : privatePrefixes) {
            int markerIndex = line.indexOf(marker);
            if (markerIndex == 0 || " whispers to you: ".equals(marker)) {
                int bodyStart = line.indexOf(": ");
                if (bodyStart >= 0) {
                    return line.substring(0, bodyStart + 2);
                }
            }
        }
        if (line.startsWith("[To ") || line.startsWith("[From ")) {
            int end = line.indexOf("] ");
            if (end >= 0) {
                return line.substring(0, end + 2);
            }
        }
        return "";
    }

    private static int headerSpacerLines(String[] lines, int index) {
        if (lines == null || index < 0 || index >= lines.length) {
            return 0;
        }
        String line = lines[index];
        if (line == null || line.isEmpty()) {
            return 0;
        }
        if (index + 1 < lines.length && lines[index + 1] != null && lines[index + 1].isBlank()) {
            return 0;
        }
        String visibleLine = RichChatPrivateMessageBridge.stripVisibleMarkersForLayout(line);
        String prefix = detectVisibleBodyPrefix(visibleLine);
        String content = (prefix.isEmpty() ? visibleLine : visibleLine.substring(prefix.length())).stripLeading();
        RichChatHeadingLayout.Heading heading = RichChatHeadingLayout.detect(content);
        return heading == null ? 0 : heading.spacerLines();
    }

    private static int leadingWhitespaceWidth(String line) {
        int index = 0;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
            index++;
        }
        return index;
    }

    private static TextRenderer textRenderer() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client == null ? null : client.textRenderer;
    }

    private record Token(String text, int end, int width, boolean whitespace, boolean marker) {
    }

    private record FormattingMarker(int index, String marker) {
    }

    private record StructuralPrefix(
            String marker,
            String content,
            String leadingWhitespace,
            float contentScale,
            boolean markerHidden,
            String continuationMarker
    ) {
        private int visibleAdvance(TextRenderer renderer) {
            if (renderer == null) {
                return 0;
            }
            if (markerHidden) {
                return renderer.getWidth(leadingWhitespace);
            }
            if (marker.endsWith("> ")) {
                return renderer.getWidth(leadingWhitespace) + 5;
            }
            return renderer.getWidth(marker);
        }
    }
}
