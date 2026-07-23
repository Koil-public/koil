package com.spirit.koil.api.chat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

public final class LocalMultilineChatBridge {
    private static final int MAX_PENDING = 8;
    private static final long MAX_AGE_MS = 10000L;
    private static final Deque<PendingLine> PENDING = new ArrayDeque<>();

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

    public static String indentContinuationLines(String original, String prefix) {
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

    public static String indentForPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "";
        }
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer renderer = client == null ? null : client.textRenderer;
        if (renderer == null) {
            return " ".repeat(Math.max(0, prefix.length()));
        }
        int prefixWidth = renderer.getWidth(prefix);
        int spaceWidth = Math.max(1, renderer.getWidth(" "));
        int spaces = Math.max(0, (int) Math.ceil(prefixWidth / (double) spaceWidth));
        return " ".repeat(spaces);
    }

    private record PendingLine(String fallback, String original, long createdAtMillis) {
    }
}
