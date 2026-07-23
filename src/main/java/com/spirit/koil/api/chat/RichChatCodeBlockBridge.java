package com.spirit.koil.api.chat;

import com.spirit.koil.api.code.CodeLanguageDetector;
import com.spirit.koil.api.chat.latex.RichChatLatexTextureCache;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RichChatCodeBlockBridge {
    public static final char MARKER_START = '\uE360';
    public static final char MARKER_END = '\uE361';
    /** Invisible one-chat-row reserve after a code card's taller final line. */
    public static final char SPACER_MARKER = '\uE362';
    private static final int CODE_BLOCK_WIDTH_REDUCTION = 43;
    private static final int CODE_BLOCK_HORIZONTAL_PADDING = 4;
    private static final int CODE_BLOCK_MENU_WIDTH = 18;
    /** Keep chat cards compact; the menu opens untouched source in the editor. */
    public static final int MAX_VISIBLE_DISPLAY_LINES = 10;
    private static final int MAX_BLOCKS = 256;
    private static final Map<String, CodeBlock> BLOCKS = new LinkedHashMap<>(MAX_BLOCKS, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CodeBlock> eldest) {
            return size() > MAX_BLOCKS;
        }
    };

    private RichChatCodeBlockBridge() {
    }

    public static Text rewrite(Text message) {
        if (message == null) {
            return null;
        }
        String visible = message.getString();
        if (visible == null || visible.isBlank() || visible.indexOf("```") < 0) {
            return message;
        }
        String[] rawLines = visible.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> output = new ArrayList<>();
        boolean inFence = false;
        String language = "";
        List<String> pendingLines = new ArrayList<>();
        String blockFirstPrefix = "";
        String blockContinuationPrefix = "";
        boolean changed = false;
        for (String rawLine : rawLines) {
            String markerPrefix = RichChatPrivateMessageBridge.leadingMarkerPrefix(rawLine);
            String line = markerPrefix.isEmpty() ? rawLine : rawLine.substring(markerPrefix.length());
            PrefixParts prefixParts = stripVisiblePrefix(line, inFence ? blockContinuationPrefix : "");
            String visibleBody = prefixParts.body();
            String trimmed = visibleBody.trim();
            if (!inFence) {
                if (trimmed.startsWith("```")) {
                    inFence = true;
                    language = trimmed.length() > 3 ? trimmed.substring(3).trim() : "";
                    blockFirstPrefix = prefixParts.prefix();
                    blockContinuationPrefix = blockFirstPrefix.isBlank() ? "" : LocalMultilineChatBridge.indentForPrefix(blockFirstPrefix);
                    pendingLines.clear();
                    changed = true;
                    continue;
                }
                output.add(rawLine);
                continue;
            }
            if (trimmed.startsWith("```")) {
                appendBlock(output, markerPrefix, blockFirstPrefix, blockContinuationPrefix, language, pendingLines);
                pendingLines.clear();
                inFence = false;
                language = "";
                blockFirstPrefix = "";
                blockContinuationPrefix = "";
                changed = true;
                continue;
            }
            pendingLines.add(visibleBody);
            changed = true;
        }
        if (inFence) {
            appendBlock(output, "", blockFirstPrefix, blockContinuationPrefix, language, pendingLines);
        }
        if (!changed) {
            return message;
        }
        return Text.literal(String.join("\n", output));
    }

    public static boolean containsMarker(String text) {
        return text != null && (text.indexOf(MARKER_START) >= 0 || text.indexOf(SPACER_MARKER) >= 0);
    }

    public static Marker nextMarker(String text, int fromIndex) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf(MARKER_START, Math.max(0, fromIndex));
        if (start < 0) {
            return null;
        }
        int end = text.indexOf(MARKER_END, start + 1);
        if (end < 0) {
            return null;
        }
        String body = text.substring(start + 1, end);
        if (!body.startsWith("CODE:")) {
            return null;
        }
        String[] parts = body.substring("CODE:".length()).split(":");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new Marker(start, end + 1, parts[0], Math.max(0, Integer.parseInt(parts[1])));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static CodeBlock block(Marker marker) {
        if (marker == null) {
            return null;
        }
        return block(marker.blockId());
    }

    public static CodeBlock block(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return null;
        }
        synchronized (BLOCKS) {
            return BLOCKS.get(blockId);
        }
    }

    public static String logFriendlyText(String visible) {
        if (visible == null || visible.isBlank() || visible.indexOf(MARKER_START) < 0) {
            return visible == null ? "" : visible;
        }
        StringBuilder builder = new StringBuilder(visible.length() + 32);
        List<String> emittedBlocks = new ArrayList<>();
        String[] lines = visible.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            String line = lines[i];
            String pmMarker = RichChatPrivateMessageBridge.leadingMarkerPrefix(line);
            String stripped = pmMarker.isEmpty() ? line : line.substring(pmMarker.length());
            if (stripped.length() == 1 && stripped.charAt(0) == SPACER_MARKER) {
                continue;
            }
            Marker marker = nextMarker(stripped, 0);
            if (marker == null || marker.start() != 0 || marker.end() != stripped.length()) {
                builder.append(line);
                continue;
            }
            CodeBlock block = block(marker);
            if (block == null) {
                continue;
            }
            if (emittedBlocks.contains(block.id())) {
                continue;
            }
            emittedBlocks.add(block.id());
            builder.append(pmMarker).append(block.firstVisiblePrefix()).append("```").append(block.language());
            for (int row = 0; row < block.originalLines().size(); row++) {
                builder.append('\n')
                        .append(pmMarker)
                        .append(row == 0 ? block.firstVisiblePrefix() : block.continuationVisiblePrefix())
                        .append(block.originalLines().get(row));
            }
            builder.append('\n').append(pmMarker).append(block.continuationVisiblePrefix()).append("```");
        }
        return builder.toString();
    }

    private static void appendBlock(List<String> output, String markerPrefix, String firstPrefix, String continuationPrefix, String language, List<String> lines) {
        List<String> blockLines = lines == null || lines.isEmpty() ? List.of("") : new ArrayList<>(lines);
        int commonIndent = commonLeadingWhitespace(blockLines);
        List<String> normalized = new ArrayList<>(blockLines.size());
        for (String line : blockLines) {
            int strip = Math.min(commonIndent, leadingWhitespace(line));
            normalized.add(line.substring(Math.min(strip, line.length())));
        }
        List<String> allDisplayLines = wrappedDisplayLines(normalized, commonIndent);
        boolean truncated = allDisplayLines.size() > MAX_VISIBLE_DISPLAY_LINES;
        List<String> displayLines = truncated
                ? List.copyOf(allDisplayLines.subList(0, MAX_VISIBLE_DISPLAY_LINES))
                : allDisplayLines;
        String blockId = UUID.randomUUID().toString();
        String safeFirstPrefix = firstPrefix == null ? "" : firstPrefix;
        String safeContinuationPrefix = continuationPrefix == null ? "" : continuationPrefix;
        synchronized (BLOCKS) {
            BLOCKS.put(
                    blockId,
                    new CodeBlock(
                            blockId,
                            language == null ? "" : language.trim(),
                            normalized,
                            displayLines,
                            allDisplayLines.size(),
                            truncated,
                            repeat(' ', commonIndent),
                            safeFirstPrefix,
                            safeContinuationPrefix
                    )
            );
        }
        for (int row = 0; row < displayLines.size(); row++) {
            String visiblePrefix = row == 0 ? safeFirstPrefix : safeContinuationPrefix;
            output.add((markerPrefix == null ? "" : markerPrefix) + visiblePrefix + MARKER_START + "CODE:" + blockId + ":" + row + MARKER_END);
        }
        // A code renderer line is taller than a vanilla chat line. Reserve one
        // invisible row after the card so its final border cannot be cut by the
        // first row of the next message.
        output.add((markerPrefix == null ? "" : markerPrefix) + safeContinuationPrefix + SPACER_MARKER);
    }

    private static List<String> wrappedDisplayLines(List<String> lines, int commonIndent) {
        TextRenderer renderer = MinecraftClient.getInstance() == null ? null : MinecraftClient.getInstance().textRenderer;
        if (renderer == null) {
            return lines == null || lines.isEmpty() ? List.of("") : List.copyOf(lines);
        }
        int chatWidth = Math.max(1, RichChatLatexTextureCache.currentChatContentWidth() + 54);
        int width = Math.min(273, Math.max(120, chatWidth - renderer.getWidth(repeat(' ', Math.max(0, commonIndent))) - 14 - CODE_BLOCK_WIDTH_REDUCTION));
        int contentWidth = Math.max(24, width - CODE_BLOCK_HORIZONTAL_PADDING - CODE_BLOCK_MENU_WIDTH);
        List<String> out = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            out.add("");
            return out;
        }
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                out.add("");
                continue;
            }
            String remaining = line;
            while (!remaining.isEmpty()) {
                String fitted = renderer.trimToWidth(remaining, contentWidth);
                if (fitted.isEmpty()) {
                    fitted = remaining.substring(0, 1);
                }
                out.add(fitted);
                remaining = remaining.substring(Math.min(remaining.length(), fitted.length()));
            }
        }
        return out.isEmpty() ? List.of("") : List.copyOf(out);
    }

    private static PrefixParts stripVisiblePrefix(String line, String fallbackContinuationPrefix) {
        if (line == null || line.isEmpty()) {
            return new PrefixParts("", line == null ? "" : line);
        }
        if (fallbackContinuationPrefix != null && !fallbackContinuationPrefix.isEmpty() && line.startsWith(fallbackContinuationPrefix)) {
            return new PrefixParts(fallbackContinuationPrefix, line.substring(fallbackContinuationPrefix.length()));
        }
        if (line.startsWith("<")) {
            int end = line.indexOf("> ");
            if (end > 1) {
                return new PrefixParts(line.substring(0, end + 2), line.substring(end + 2));
            }
        }
        String[] prefixes = {"You whisper to ", "To ", "From "};
        for (String prefix : prefixes) {
            if (line.startsWith(prefix)) {
                int end = line.indexOf(": ");
                if (end > 0) {
                    return new PrefixParts(line.substring(0, end + 2), line.substring(end + 2));
                }
            }
        }
        int whisper = line.indexOf(" whispers to you: ");
        if (whisper > 0) {
            int end = line.indexOf(": ");
            if (end > 0) {
                return new PrefixParts(line.substring(0, end + 2), line.substring(end + 2));
            }
        }
        if (line.startsWith("[To ") || line.startsWith("[From ")) {
            int end = line.indexOf("] ");
            if (end > 0) {
                return new PrefixParts(line.substring(0, end + 2), line.substring(end + 2));
            }
        }
        return new PrefixParts("", line);
    }

    private static int commonLeadingWhitespace(List<String> lines) {
        int common = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            common = Math.min(common, leadingWhitespace(line));
        }
        return common == Integer.MAX_VALUE ? 0 : common;
    }

    private static int leadingWhitespace(String line) {
        int index = 0;
        while (line != null && index < line.length() && Character.isWhitespace(line.charAt(index))) {
            index++;
        }
        return index;
    }

    private static String repeat(char c, int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }

    public record Marker(int start, int end, String blockId, int row) {
    }

    public static String syntaxFileName(CodeBlock block) {
        if (block == null) {
            return "snippet.txt";
        }
        CodeLanguageDetector.CodeLanguage explicit = CodeLanguageDetector.fromFenceLabel(block.language());
        if (explicit != CodeLanguageDetector.CodeLanguage.TEXT) {
            return explicit.suggestedFileName();
        }
        return CodeLanguageDetector.bestGuess(String.join("\n", block.originalLines())).language().suggestedFileName();
    }

    public record CodeBlock(
            String id,
            String language,
            List<String> originalLines,
            List<String> displayLines,
            int totalDisplayLines,
            boolean truncated,
            String chatIndent,
            String firstVisiblePrefix,
            String continuationVisiblePrefix
    ) {
    }

    private record PrefixParts(String prefix, String body) {
    }
}
