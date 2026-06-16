package com.spirit.koil.api.chat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface RichChatMessage {
    UUID messageId();
    UUID senderUuid();
    String senderName();
    RichChatScope scope();
    RichChatMessageType type();
    String fallbackText();
    String rawText();
    List<RichChatSegment> segments();
    List<RichChatAttachment> attachments();
    long createdAtMillis();
    long expiresAtMillis();
    Map<String, String> metadata();

    default boolean expired(long nowMillis) {
        return expiresAtMillis() > 0L && nowMillis > expiresAtMillis();
    }
}
