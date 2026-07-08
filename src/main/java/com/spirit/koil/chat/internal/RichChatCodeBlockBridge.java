package com.spirit.koil.chat.internal;

import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RichChatCodeBlockBridge {
    public static final char MARKER_START = '\uE360';
    public static final char MARKER_END = '\uE361';
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
        boolean changed = false;
        for (String rawLine : rawLines) {
            String markerPrefix = RichChatPrivateMessageBridge.leadingMarkerPrefix(rawLine);
            String line = markerPrefix.isEmpty() ? rawLine : rawLine.substring(markerPrefix.length());
            String trimmed = line.trim();
            if (!inFence) {
                if (trimmed.startsWith("```")) {
                    inFence = true;
                    language = trimmed.length() > 3 ? trimmed.substring(3).trim() : "";
                    pendingLines.clear();
                    changed = true;
                    continue;
                }
                output.add(rawLine);
                continue;
            }
            if (trimmed.startsWith("```")) {
                appendBlock(output, markerPrefix, language, pendingLines);
                pendingLines.clear();
                inFence = false;
                language = "";
                changed = true;
                continue;
            }
            pendingLines.add(line);
            changed = true;
        }
        if (inFence) {
            appendBlock(output, "", language, pendingLines);
        }
        if (!changed) {
            return message;
        }
        return Text.literal(String.join("\n", output));
    }

    public static boolean containsMarker(String text) {
        return text != null && text.indexOf(MARKER_START) >= 0;
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
        String[] lines = visible.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            String line = lines[i];
            String pmMarker = RichChatPrivateMessageBridge.leadingMarkerPrefix(line);
            String stripped = pmMarker.isEmpty() ? line : line.substring(pmMarker.length());
            Marker marker = nextMarker(stripped, 0);
            if (marker == null || marker.start() != 0 || marker.end() != stripped.length()) {
                builder.append(line);
                continue;
            }
            CodeBlock block = block(marker);
            if (block == null) {
                continue;
            }
            String prefix = marker.row() == 0 ? "```" + block.language() : "";
            if (!prefix.isEmpty()) {
                builder.append(pmMarker).append(prefix).append('\n');
            }
            String codeLine = marker.row() < block.lines().size() ? block.lines().get(marker.row()) : "";
            builder.append(pmMarker).append(codeLine);
            if (marker.row() == block.lines().size() - 1) {
                builder.append('\n').append(pmMarker).append("```");
            }
        }
        return builder.toString();
    }

    private static void appendBlock(List<String> output, String markerPrefix, String language, List<String> lines) {
        List<String> blockLines = lines == null || lines.isEmpty() ? List.of("") : new ArrayList<>(lines);
        int commonIndent = commonLeadingWhitespace(blockLines);
        List<String> normalized = new ArrayList<>(blockLines.size());
        for (String line : blockLines) {
            int strip = Math.min(commonIndent, leadingWhitespace(line));
            normalized.add(line.substring(Math.min(strip, line.length())));
        }
        String blockId = UUID.randomUUID().toString();
        synchronized (BLOCKS) {
            BLOCKS.put(blockId, new CodeBlock(blockId, language == null ? "" : language.trim(), normalized, repeat(' ', commonIndent)));
        }
        for (int row = 0; row < normalized.size(); row++) {
            output.add((markerPrefix == null ? "" : markerPrefix) + MARKER_START + "CODE:" + blockId + ":" + row + MARKER_END);
        }
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

    public record CodeBlock(String id, String language, List<String> lines, String chatIndent) {
    }
}
