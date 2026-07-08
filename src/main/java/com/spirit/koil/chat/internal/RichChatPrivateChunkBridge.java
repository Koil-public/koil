package com.spirit.koil.chat.internal;

import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class RichChatPrivateChunkBridge {
    public static final char MARKER_START = '\uE352';
    public static final char MARKER_END = '\uE353';
    private static final String PREFIX = "KPM:";
    private static final int MAX_PENDING = 64;
    private static final long MAX_AGE_MS = 15000L;
    private static final Deque<PendingGroup> PENDING = new ArrayDeque<>();

    private RichChatPrivateChunkBridge() {
    }

    public static String encode(long groupTimestamp, int chunkIndex, int chunkCount, boolean prependSpace, String chunkText) {
        String body = chunkText == null ? "" : chunkText;
        return MARKER_START
                + PREFIX
                + groupTimestamp
                + ":"
                + Math.max(0, chunkIndex)
                + ":"
                + Math.max(1, chunkCount)
                + ":"
                + (prependSpace ? "1" : "0")
                + MARKER_END
                + body;
    }

    public static RewriteResult rewrite(Text message) {
        if (message == null) {
            return RewriteResult.pass(null);
        }
        Chunk chunk = parse(message.getString());
        if (chunk == null) {
            return RewriteResult.pass(message);
        }
        synchronized (PENDING) {
            cleanup();
            PendingGroup group = groupFor(chunk);
            group.parts().put(chunk.index(), new ChunkPart(chunk.index(), chunk.prependSpace(), chunk.chunkText()));
            if (group.parts().size() < chunk.count()) {
                trim();
                return RewriteResult.cancelOnly();
            }
            List<ChunkPart> ordered = new ArrayList<>(group.parts().values());
            ordered.sort(Comparator.comparingInt(ChunkPart::index));
            StringBuilder builder = new StringBuilder(chunk.prefix().length() + ordered.stream().mapToInt(part -> part.text().length() + 1).sum());
            builder.append(chunk.prefix());
            boolean first = true;
            for (ChunkPart part : ordered) {
                if (!first && part.prependSpace()) {
                    builder.append(' ');
                }
                builder.append(part.text());
                first = false;
            }
            PENDING.remove(group);
            return RewriteResult.replace(Text.literal(builder.toString()));
        }
    }

    private static PendingGroup groupFor(Chunk chunk) {
        String key = chunk.prefix() + "|" + chunk.groupTimestamp() + "|" + chunk.count();
        for (PendingGroup pending : PENDING) {
            if (pending.key().equals(key)) {
                return pending;
            }
        }
        PendingGroup created = new PendingGroup(key, System.currentTimeMillis(), new HashMap<>());
        PENDING.addLast(created);
        trim();
        return created;
    }

    private static void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<PendingGroup> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            PendingGroup pending = iterator.next();
            if (now - pending.createdAtMillis() > MAX_AGE_MS) {
                iterator.remove();
            }
        }
    }

    private static void trim() {
        while (PENDING.size() > MAX_PENDING) {
            PENDING.removeFirst();
        }
    }

    private static Chunk parse(String visible) {
        if (visible == null || visible.isBlank()) {
            return null;
        }
        int start = visible.indexOf(MARKER_START);
        if (start < 0) {
            return null;
        }
        int end = visible.indexOf(MARKER_END, start + 1);
        if (end < 0) {
            return null;
        }
        String header = visible.substring(start + 1, end);
        if (!header.startsWith(PREFIX)) {
            return null;
        }
        String[] parts = header.substring(PREFIX.length()).split(":");
        if (parts.length < 4) {
            return null;
        }
        try {
            long groupTimestamp = Long.parseLong(parts[0]);
            int index = Math.max(0, Integer.parseInt(parts[1]));
            int count = Math.max(1, Integer.parseInt(parts[2]));
            boolean prependSpace = "1".equals(parts[3]);
            String prefix = visible.substring(0, start);
            String chunkText = visible.substring(end + 1);
            return new Chunk(prefix, chunkText, groupTimestamp, index, count, prependSpace);
        } catch (Exception ignored) {
            return null;
        }
    }

    public record RewriteResult(Text message, boolean cancel) {
        public static RewriteResult pass(Text message) {
            return new RewriteResult(message, false);
        }

        public static RewriteResult replace(Text message) {
            return new RewriteResult(message, false);
        }

        public static RewriteResult cancelOnly() {
            return new RewriteResult(null, true);
        }
    }

    private record Chunk(String prefix, String chunkText, long groupTimestamp, int index, int count, boolean prependSpace) {
    }

    private record ChunkPart(int index, boolean prependSpace, String text) {
    }

    private record PendingGroup(String key, long createdAtMillis, Map<Integer, ChunkPart> parts) {
    }
}
