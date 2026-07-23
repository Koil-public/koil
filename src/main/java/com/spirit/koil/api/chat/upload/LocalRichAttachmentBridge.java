package com.spirit.koil.api.chat.upload;

import com.spirit.koil.api.chat.RichChatAttachment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

public final class LocalRichAttachmentBridge {
    public static final char MARKER_START = '\uE340';
    public static final char MARKER_END = '\uE341';
    private static final int MAX_PENDING = 8;
    private static final long MAX_AGE_MS = 10000L;
    private static final Deque<PendingAttachmentLine> PENDING = new ArrayDeque<>();
    private static final Map<UUID, RichChatAttachment> ATTACHMENTS = new ConcurrentHashMap<>();
    private static final Map<UUID, String> PREFIXES = new ConcurrentHashMap<>();
    private static final Map<String, RichChatAttachment> OCCURRENCE_ATTACHMENTS = new ConcurrentHashMap<>();
    private static final Map<String, String> OCCURRENCE_PREFIXES = new ConcurrentHashMap<>();
    private static final AtomicLong OCCURRENCE_COUNTER = new AtomicLong();

    private LocalRichAttachmentBridge() {
    }

    public static synchronized void remember(String fallback, String original, RichChatAttachment attachment) {
        if (fallback == null || fallback.isBlank() || original == null || attachment == null || attachment.attachmentId() == null) {
            return;
        }
        ATTACHMENTS.put(attachment.attachmentId(), attachment);
        PENDING.addLast(new PendingAttachmentLine(fallback, original, attachment.attachmentId(), System.currentTimeMillis()));
        while (PENDING.size() > MAX_PENDING) {
            PENDING.removeFirst();
        }
    }

    public static synchronized Text rewrite(Text message) {
        if (message == null || PENDING.isEmpty()) {
            return message;
        }
        long now = System.currentTimeMillis();
        String visible = message.getString();
        Iterator<PendingAttachmentLine> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            PendingAttachmentLine pending = iterator.next();
            if (now - pending.createdAtMillis > MAX_AGE_MS) {
                iterator.remove();
                continue;
            }
            int index = visible.indexOf(pending.fallback);
            if (index >= 0) {
                iterator.remove();
                String prefix = visible.substring(0, index);
                String indent = com.spirit.koil.api.chat.LocalMultilineChatBridge.indentForPrefix(prefix);
                String suffix = visible.substring(index + pending.fallback.length());
                String original = pending.original == null ? "" : pending.original.strip();
                RichChatAttachment attachment = ATTACHMENTS.get(pending.attachmentId);
                String displayPrefix = prefix;
                String trailingBody = suffix;
                if (!original.isBlank()) {
                    if (renderAttachmentBelowMessage(attachment)) {
                        PREFIXES.put(pending.attachmentId, indent);
                        StringBuilder block = new StringBuilder(prefix)
                                .append(com.spirit.koil.api.chat.LocalMultilineChatBridge.indentContinuationLines(original, prefix))
                                .append('\n')
                                .append(marker(pending.attachmentId, indent));
                        if (!suffix.isBlank()) {
                            block.append(suffix.stripLeading());
                        }
                        return Text.literal(block.toString());
                    }
                    int newline = original.indexOf('\n');
                    String firstLine = newline >= 0 ? original.substring(0, newline).stripTrailing() : original;
                    String remaining = newline >= 0 ? original.substring(newline + 1) : "";
                    displayPrefix = prefix + firstLine + (firstLine.isBlank() ? "" : " ");
                    trailingBody = remaining.isBlank() ? suffix : remaining + suffix;
                }
                PREFIXES.put(pending.attachmentId, displayPrefix);
                return Text.literal(marker(pending.attachmentId, displayPrefix) + trailingBody.stripLeading());
            }
        }
        return message;
    }

    public static Optional<RichChatAttachment> attachment(UUID attachmentId) {
        return Optional.ofNullable(ATTACHMENTS.get(attachmentId));
    }

    public static Optional<RichChatAttachment> attachment(Marker marker) {
        if (marker == null) {
            return Optional.empty();
        }
        RichChatAttachment occurrenceAttachment = OCCURRENCE_ATTACHMENTS.get(marker.occurrenceId());
        if (occurrenceAttachment != null) {
            return Optional.of(occurrenceAttachment);
        }
        return attachment(marker.attachmentId());
    }

    public static void rememberVisibleAttachment(RichChatAttachment attachment, String prefix) {
        if (attachment == null || attachment.attachmentId() == null) {
            return;
        }
        ATTACHMENTS.put(attachment.attachmentId(), attachment);
        PREFIXES.put(attachment.attachmentId(), prefix == null ? "" : prefix);
    }

    public static String prefix(UUID attachmentId) {
        return attachmentId == null ? "" : PREFIXES.getOrDefault(attachmentId, "");
    }

    public static String prefix(Marker marker) {
        if (marker == null) {
            return "";
        }
        String occurrencePrefix = OCCURRENCE_PREFIXES.get(marker.occurrenceId());
        if (occurrencePrefix != null) {
            return occurrencePrefix;
        }
        return prefix(marker.attachmentId());
    }

    public static String marker(UUID attachmentId) {
        return marker(attachmentId, "");
    }

    public static String marker(UUID attachmentId, String linePrefix) {
        StringBuilder builder = new StringBuilder();
        String prefix = linePrefix == null ? "" : linePrefix;
        int rows = reservedRows(attachmentId, renderedWidth(prefix));
        String occurrenceId = Long.toString(OCCURRENCE_COUNTER.incrementAndGet(), 36);
        RichChatAttachment attachment = ATTACHMENTS.get(attachmentId);
        if (attachment != null) {
            OCCURRENCE_ATTACHMENTS.put(occurrenceId, attachment);
        }
        OCCURRENCE_PREFIXES.put(occurrenceId, prefix);
        for (int row = 0; row < rows; row++) {
            if (row > 0) {
                builder.append('\n');
            }
            if (row == 0) {
                builder.append(prefix);
            }
            builder.append(marker(attachmentId, occurrenceId, row));
        }
        return builder.toString();
    }

    public static String marker(UUID attachmentId, int row) {
        return marker(attachmentId, attachmentId, row);
    }

    private static String marker(UUID attachmentId, Object occurrenceId, int row) {
        String occurrence = occurrenceId == null ? "0" : occurrenceId.toString();
        return MARKER_START + "ATTACH:" + occurrence + ":" + Math.max(0, row) + MARKER_END;
    }

    public static int reservedRows() {
        return 15;
    }

    public static int reservedRows(UUID attachmentId) {
        return reservedRows(attachmentId, 0);
    }

    private static int reservedRows(UUID attachmentId, int leadingWidth) {
        RichChatAttachment attachment = ATTACHMENTS.get(attachmentId);
        if (attachment == null) {
            return reservedRows();
        }
        // Keep every attachment class on the renderer's single height
        // contract. The old FILE shortcut reserved only two text rows for a
        // 34px card, which could overlap following private or public chat.
        return RichChatAttachmentRenderer.reservedMarkerRows(attachment, leadingWidth);
    }

    private static int renderedWidth(String text) {
        MinecraftClient client = MinecraftClient.getInstance();
        return client == null || client.textRenderer == null || text == null ? 0 : client.textRenderer.getWidth(text);
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
        if (!body.startsWith("ATTACH:")) {
            return null;
        }
        try {
            String[] parts = body.substring("ATTACH:".length()).split(":");
            if (parts.length >= 2) {
                try {
                    UUID id = UUID.fromString(parts[0]);
                    String occurrenceId = id.toString();
                    int row = 0;
                    if (parts.length == 2) {
                        row = Math.max(0, Integer.parseInt(parts[1]));
                    } else {
                        occurrenceId = parts[1];
                        row = Math.max(0, Integer.parseInt(parts[2]));
                    }
                    return new Marker(start, end + 1, id, occurrenceId, row);
                } catch (IllegalArgumentException ignored) {
                    String occurrenceId = parts[0];
                    int row = Math.max(0, Integer.parseInt(parts[1]));
                    RichChatAttachment attachment = OCCURRENCE_ATTACHMENTS.get(occurrenceId);
                    UUID id = attachment == null ? null : attachment.attachmentId();
                    return new Marker(start, end + 1, id, occurrenceId, row);
                }
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    public static boolean visualAttachment(RichChatAttachment attachment) {
        return attachment != null;
    }

    private static boolean renderAttachmentBelowMessage(RichChatAttachment attachment) {
        return attachment != null;
    }

    public record Marker(int start, int end, UUID attachmentId, String occurrenceId, int row) {
    }

    private record PendingAttachmentLine(String fallback, String original, UUID attachmentId, long createdAtMillis) {
    }
}
