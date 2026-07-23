package com.spirit.koil.api.chat;

import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public final class LocalOverflowChatBridge {
    private static final int MAX_PENDING = 48;
    private static final long MAX_AGE_MS = 10000L;
    private static final Deque<PendingGroup> PENDING = new ArrayDeque<>();

    private LocalOverflowChatBridge() {
    }

    public static synchronized void remember(String visiblePrefix, List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        ArrayDeque<String> filtered = new ArrayDeque<>();
        for (String chunk : chunks) {
            if (chunk != null && !chunk.isBlank()) {
                filtered.addLast(chunk);
            }
        }
        if (filtered.isEmpty()) {
            return;
        }
        PENDING.addLast(new PendingGroup(visiblePrefix == null ? "" : visiblePrefix, filtered, now));
        while (PENDING.size() > MAX_PENDING) {
            PENDING.removeFirst();
        }
    }

    public static synchronized boolean consume(Text message) {
        if (message == null || PENDING.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        String visible = message.getString();
        String firstLine = firstLine(visible);
        Iterator<PendingGroup> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            PendingGroup pending = iterator.next();
            if (now - pending.createdAtMillis > MAX_AGE_MS) {
                iterator.remove();
                continue;
            }
            String expectedChunk = pending.chunks.peekFirst();
            if (expectedChunk == null) {
                iterator.remove();
                continue;
            }
            if (matchesPending(firstLine, pending.visiblePrefix, expectedChunk)) {
                pending.chunks.removeFirst();
                if (pending.chunks.isEmpty()) {
                    iterator.remove();
                }
                return true;
            }
        }
        return false;
    }

    private static boolean matchesPending(String visible, String visiblePrefix, String chunk) {
        if (visible == null || chunk == null) {
            return false;
        }
        String prefix = visiblePrefix == null ? "" : visiblePrefix;
        String candidate = prefix.isBlank() ? chunk : prefix + chunk;
        if (Objects.equals(visible, candidate)) {
            return true;
        }
        if (!prefix.isBlank() && visible.startsWith(prefix)) {
            String body = visible.substring(prefix.length());
            return Objects.equals(body, chunk);
        }
        return Objects.equals(visible, chunk);
    }

    private static String firstLine(String visible) {
        if (visible == null || visible.isBlank()) {
            return "";
        }
        int newline = visible.indexOf('\n');
        return newline < 0 ? visible : visible.substring(0, newline);
    }

    private static final class PendingGroup {
        private final String visiblePrefix;
        private final ArrayDeque<String> chunks;
        private final long createdAtMillis;

        private PendingGroup(String visiblePrefix, ArrayDeque<String> chunks, long createdAtMillis) {
            this.visiblePrefix = visiblePrefix;
            this.chunks = chunks;
            this.createdAtMillis = createdAtMillis;
        }
    }
}
