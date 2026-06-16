package com.spirit.koil.chat.internal;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.HashMap;
import java.util.Map;

public final class LocalMultilineChatBridge {
    private static final int MAX_PENDING = 8;
    private static final long MAX_AGE_MS = 10000L;
    private static final Deque<PendingLine> PENDING = new ArrayDeque<>();
    private static final String[] INDENT_GLYPHS = {" ", "\u00A0", "\u2006", "\u2009", "\u200A"};
    private static final Map<Integer, String> INDENT_CACHE = new HashMap<>();

    private LocalMultilineChatBridge() {
    }

    public static void remember(String fallback, String original) {
        if (fallback == null || fallback.isBlank() || original == null || original.indexOf('\n') < 0) {
            return;
        }

        long now = System.currentTimeMillis();
        PENDING.addLast(new PendingLine(fallback, original, now));
        while (PENDING.size() > MAX_PENDING) {
            PENDING.removeFirst();
        }
    }

    public static Text rewrite(Text message) {
        if (message == null || PENDING.isEmpty()) {
            return message;
        }

        long now = System.currentTimeMillis();
        String visible = message.getString();
        Iterator<PendingLine> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            PendingLine pending = iterator.next();
            if (now - pending.createdAtMillis > MAX_AGE_MS) {
                iterator.remove();
                continue;
            }

            int index = visible.indexOf(pending.fallback);
            if (index >= 0) {
                iterator.remove();
                String prefix = visible.substring(0, index);
                return Text.literal(prefix + indentContinuationLines(pending.original, prefix) + visible.substring(index + pending.fallback.length()));
            }
        }

        return message;
    }

    private static String indentContinuationLines(String original, String prefix) {
        String[] lines = original.split("\n", -1);
        if (lines.length <= 1) {
            return original;
        }

        String indent = indentForPrefix(prefix);
        StringBuilder builder = new StringBuilder(original.length() + indent.length() * (lines.length - 1));
        builder.append(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            builder.append('\n').append(indent).append(lines[i]);
        }
        return builder.toString();
    }

    private static String indentForPrefix(String prefix) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null || prefix == null || prefix.isEmpty()) {
            return " ".repeat(prefix == null ? 0 : prefix.length());
        }

        TextRenderer renderer = client.textRenderer;
        int targetWidth = renderer.getWidth(prefix);
        if (targetWidth <= 0) {
            return "";
        }

        String cached = INDENT_CACHE.get(targetWidth);
        if (cached != null) {
            return cached;
        }

        String indent = buildMeasuredIndent(renderer, targetWidth);
        INDENT_CACHE.put(targetWidth, indent);
        return indent;
    }

    private static String buildMeasuredIndent(TextRenderer renderer, int targetWidth) {
        int maxGlyphWidth = 0;
        for (String glyph : INDENT_GLYPHS) {
            maxGlyphWidth = Math.max(maxGlyphWidth, renderer.getWidth(glyph));
        }
        if (maxGlyphWidth <= 0) {
            return "";
        }

        int maxWidth = targetWidth + maxGlyphWidth;
        String[] best = new String[maxWidth + 1];
        best[0] = "";
        for (int width = 0; width <= maxWidth; width++) {
            if (best[width] == null) {
                continue;
            }
            for (String glyph : INDENT_GLYPHS) {
                int glyphWidth = renderer.getWidth(glyph);
                if (glyphWidth <= 0 || width + glyphWidth > maxWidth) {
                    continue;
                }
                String candidate = best[width] + glyph;
                if (best[width + glyphWidth] == null || candidate.length() < best[width + glyphWidth].length()) {
                    best[width + glyphWidth] = candidate;
                }
            }
        }

        int bestWidth = 0;
        int bestDelta = Integer.MAX_VALUE;
        for (int width = 0; width <= maxWidth; width++) {
            if (best[width] == null) {
                continue;
            }
            int delta = Math.abs(width - targetWidth);
            if (delta < bestDelta || delta == bestDelta && width <= targetWidth && bestWidth > targetWidth) {
                bestWidth = width;
                bestDelta = delta;
            }
        }

        return best[bestWidth] == null ? " ".repeat(Math.max(0, targetWidth / Math.max(1, renderer.getWidth(" ")))) : best[bestWidth];
    }

    private record PendingLine(String fallback, String original, long createdAtMillis) {
    }
}
