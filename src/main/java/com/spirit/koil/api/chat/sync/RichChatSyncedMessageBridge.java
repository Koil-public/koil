package com.spirit.koil.api.chat.sync;

import com.spirit.koil.api.chat.RichChatAttachment;
import com.spirit.koil.api.chat.RichChatMessageData;
import com.spirit.koil.api.chat.LocalMultilineChatBridge;
import com.spirit.koil.api.chat.RichChatTimestampBridge;
import com.spirit.koil.api.chat.upload.LocalRichAttachmentBridge;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

public final class RichChatSyncedMessageBridge {
    private static final int MAX_PENDING = 64;
    private static final long MAX_AGE_MS = 15000L;
    private static final Deque<PendingMessage> PENDING = new ArrayDeque<>();

    private RichChatSyncedMessageBridge() {
    }

    public static synchronized void remember(RichChatMessageData message) {
        if (message == null) {
            return;
        }
        PENDING.addLast(new PendingMessage(message, fallbackChunks(message), System.currentTimeMillis()));
        while (PENDING.size() > MAX_PENDING) {
            PENDING.removeFirst();
        }
    }

    public static synchronized void discard(java.util.UUID messageId) {
        if (messageId == null || PENDING.isEmpty()) {
            return;
        }
        PENDING.removeIf(pending -> messageId.equals(pending.message().messageId()));
    }

    public static Text immediateVisibleMessage(RichChatMessageData message) {
        if (message == null) {
            return Text.empty();
        }
        String prefix = message.scope() == com.spirit.koil.api.chat.RichChatScope.PRIVATE
                ? message.senderName() + " whispers to you: "
                : "<" + message.senderName() + "> ";
        String fallback = firstChunk(message);
        String rebuilt = buildRichBody(prefix, "", message, fallback);
        RichChatTimestampBridge.rememberVisibleLine(
                rebuilt,
                RichChatTimestampBridge.formatMillis(message.createdAtMillis() <= 0L ? System.currentTimeMillis() : message.createdAtMillis()),
                rebuilt.indexOf('\n') >= 0 || LocalRichAttachmentBridge.containsMarker(rebuilt)
        );
        return Text.literal(rebuilt);
    }

    public static synchronized RewriteResult rewrite(Text message) {
        if (message == null || PENDING.isEmpty()) {
            return RewriteResult.pass(message);
        }
        long now = System.currentTimeMillis();
        String visible = message.getString();
        Iterator<PendingMessage> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            PendingMessage pending = iterator.next();
            if (now - pending.createdAtMillis() > MAX_AGE_MS) {
                iterator.remove();
                continue;
            }
            Match match = match(pending, visible);
            if (match == null) {
                continue;
            }
            if (pending.chunked()) {
                if (match.chunkIndex() == 0 && !pending.displayed()) {
                    pending.displayed(true);
                    pending.nextChunkIndex(1);
                    Text replacement = Text.literal(buildVisible(visible, match.matchedText(), pending.message()));
                    if (pending.nextChunkIndex() >= pending.chunks().size()) {
                        iterator.remove();
                    }
                    return RewriteResult.replace(replacement);
                }
                if (pending.displayed() && match.chunkIndex() == pending.nextChunkIndex()) {
                    pending.nextChunkIndex(pending.nextChunkIndex() + 1);
                    if (pending.nextChunkIndex() >= pending.chunks().size()) {
                        iterator.remove();
                    }
                    return RewriteResult.cancelOnly();
                }
                continue;
            }
            iterator.remove();
            return RewriteResult.replace(Text.literal(buildVisible(visible, match.matchedText(), pending.message())));
        }
        return RewriteResult.pass(message);
    }

    private static Match match(PendingMessage pending, String visible) {
        if (visible == null || visible.isBlank()) {
            return null;
        }
        if (pending.chunked()) {
            List<String> chunks = pending.chunks();
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                if (!chunk.isBlank() && visible.contains(chunk) && senderMatches(visible, pending.message().senderName())) {
                    return new Match(chunk, i);
                }
            }
            return null;
        }
        String fallback = pending.message().fallbackText();
        if (fallback == null || fallback.isBlank() || !visible.contains(fallback)) {
            return null;
        }
        if (fallback.startsWith("[Koil PM ")) {
            return new Match(fallback, 0);
        }
        if (!senderMatches(visible, pending.message().senderName())) {
            return null;
        }
        return new Match(fallback, 0);
    }

    private static boolean senderMatches(String visible, String senderName) {
        if (senderName == null || senderName.isBlank()) {
            return true;
        }
        return visible.contains("<" + senderName + ">")
                || visible.startsWith("You whisper to ")
                || visible.contains(" " + senderName + ":")
                || visible.contains(" " + senderName + "]")
                || visible.contains(senderName + " whispers to you")
                || visible.startsWith("To ")
                || visible.contains("To " + senderName + ":")
                || visible.contains("From " + senderName + ":");
    }

    private static String buildVisible(String originalVisible, String matchedText, RichChatMessageData message) {
        int start = originalVisible.indexOf(matchedText);
        if (start < 0) {
            start = 0;
        }
        String prefix = originalVisible.substring(0, start);
        String suffix = originalVisible.substring(Math.min(originalVisible.length(), start + matchedText.length()));
        String rebuilt = buildRichBody(prefix, suffix, message, matchedText);
        RichChatTimestampBridge.rememberVisibleLine(
                rebuilt,
                RichChatTimestampBridge.formatMillis(message.createdAtMillis() <= 0L ? System.currentTimeMillis() : message.createdAtMillis()),
                rebuilt.indexOf('\n') >= 0 || LocalRichAttachmentBridge.containsMarker(rebuilt)
        );
        return rebuilt;
    }

    private static String buildRichBody(String prefix, String suffix, RichChatMessageData message, String fallback) {
        String rawText = message.rawText() == null ? "" : message.rawText().replace("\r\n", "\n").replace('\r', '\n').trim();
        List<RichChatAttachment> attachments = message.attachments() == null ? List.of() : message.attachments();
        if (attachments.isEmpty()) {
            String body = rawText.isBlank() ? fallback : rawText;
            return prefix + LocalMultilineChatBridge.indentContinuationLines(body, prefix) + suffix;
        }
        String indent = LocalMultilineChatBridge.indentForPrefix(prefix);
        StringBuilder builder = new StringBuilder(prefix.length() + rawText.length() + 64);
        if (!rawText.isBlank()) {
            builder.append(prefix).append(LocalMultilineChatBridge.indentContinuationLines(rawText, prefix));
            builder.append('\n');
        }
        for (int i = 0; i < attachments.size(); i++) {
            RichChatAttachment attachment = attachments.get(i);
            LocalRichAttachmentBridge.rememberVisibleAttachment(attachment, rawText.isBlank() && i == 0 ? prefix : indent);
            builder.append(LocalRichAttachmentBridge.marker(attachment.attachmentId(), rawText.isBlank() && i == 0 ? prefix : indent));
            if (i < attachments.size() - 1) {
                builder.append('\n');
            }
        }
        if (!suffix.isBlank()) {
            builder.append(suffix.stripLeading());
        }
        return builder.toString();
    }

    private static List<String> fallbackChunks(RichChatMessageData message) {
        List<String> chunks = new ArrayList<>();
        String chunkCount = message.metadata().getOrDefault("chunk_count", "");
        if (chunkCount.isBlank()) {
            return chunks;
        }
        try {
            int count = Math.max(0, Integer.parseInt(chunkCount));
            for (int i = 0; i < count; i++) {
                String chunk = message.metadata().getOrDefault("chunk_" + i, "");
                if (!chunk.isBlank()) {
                    chunks.add(chunk);
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return chunks;
    }

    private static String firstChunk(RichChatMessageData message) {
        List<String> chunks = fallbackChunks(message);
        return chunks.isEmpty() ? (message == null ? "" : message.fallbackText()) : chunks.get(0);
    }

    public record RewriteResult(Text message, boolean cancel) {
        public static RewriteResult pass(Text message) {
            return new RewriteResult(message, false);
        }

        public static RewriteResult replace(Text message) {
            return new RewriteResult(message, true);
        }

        public static RewriteResult cancelOnly() {
            return new RewriteResult(null, true);
        }
    }

    private record Match(String matchedText, int chunkIndex) {
    }

    private static final class PendingMessage {
        private final RichChatMessageData message;
        private final List<String> chunks;
        private final long createdAtMillis;
        private int nextChunkIndex;
        private boolean displayed;

        private PendingMessage(RichChatMessageData message, List<String> chunks, long createdAtMillis) {
            this.message = message;
            this.chunks = chunks == null ? List.of() : chunks;
            this.createdAtMillis = createdAtMillis;
        }

        private RichChatMessageData message() {
            return message;
        }

        private List<String> chunks() {
            return chunks;
        }

        private boolean chunked() {
            return !chunks.isEmpty();
        }

        private long createdAtMillis() {
            return createdAtMillis;
        }

        private int nextChunkIndex() {
            return nextChunkIndex;
        }

        private void nextChunkIndex(int nextChunkIndex) {
            this.nextChunkIndex = nextChunkIndex;
        }

        private boolean displayed() {
            return displayed;
        }

        private void displayed(boolean displayed) {
            this.displayed = displayed;
        }
    }
}
