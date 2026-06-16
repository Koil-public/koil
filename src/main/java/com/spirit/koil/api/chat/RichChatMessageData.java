package com.spirit.koil.api.chat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RichChatMessageData(
        UUID messageId,
        UUID senderUuid,
        String senderName,
        RichChatScope scope,
        RichChatMessageType type,
        String fallbackText,
        String rawText,
        List<RichChatSegment> segments,
        List<RichChatAttachment> attachments,
        long createdAtMillis,
        long expiresAtMillis,
        Map<String, String> metadata
) implements RichChatMessage {
    public RichChatMessageData {
        messageId = messageId == null ? UUID.randomUUID() : messageId;
        senderName = senderName == null ? "" : senderName;
        scope = scope == null ? RichChatScope.PUBLIC : scope;
        type = type == null ? RichChatMessageType.TEXT : type;
        fallbackText = fallbackText == null ? "" : fallbackText;
        rawText = rawText == null ? "" : rawText;
        segments = segments == null ? List.of() : List.copyOf(segments);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        Objects.requireNonNull(messageId, "messageId");
    }
}
