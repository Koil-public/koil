package com.spirit.koil.chat.internal;

import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

public final class LocalOverflowChatBridge {
    private static final int MAX_PENDING = 24;
    private static final long MAX_AGE_MS = 10000L;
    private static final Deque<PendingChunk> PENDING = new ArrayDeque<>();

    private LocalOverflowChatBridge() {
    }

    public static synchronized void remember(String visiblePrefix, List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String chunk : chunks) {
            if (chunk == null || chunk.isBlank()) {
                continue;
            }
            PENDING.addLast(new PendingChunk(visiblePrefix == null ? "" : visiblePrefix, chunk, now));
        }
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
        Iterator<PendingChunk> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            PendingChunk pending = iterator.next();
            if (now - pending.createdAtMillis > MAX_AGE_MS) {
                iterator.remove();
                continue;
            }
            if (visible.contains(pending.chunk) && (pending.visiblePrefix.isBlank() || visible.contains(pending.visiblePrefix))) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private record PendingChunk(String visiblePrefix, String chunk, long createdAtMillis) {
    }
}
