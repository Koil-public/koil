package com.spirit.koil.api.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RichMessageBuilder {
    private UUID messageId = UUID.randomUUID();
    private UUID senderUuid;
    private String senderName = "";
    private RichChatScope scope = RichChatScope.PUBLIC;
    private RichChatMessageType type = RichChatMessageType.TEXT;
    private String fallbackText = "";
    private String rawText = "";
    private final List<RichChatSegment> segments = new ArrayList<>();
    private final List<RichChatAttachment> attachments = new ArrayList<>();
    private long createdAtMillis = System.currentTimeMillis();
    private long expiresAtMillis;
    private final Map<String, String> metadata = new LinkedHashMap<>();

    private RichMessageBuilder() {
    }

    public static RichMessageBuilder create() {
        return new RichMessageBuilder();
    }

    public static RichMessageBuilder text(String text) {
        return create()
                .type(RichChatMessageType.TEXT)
                .rawText(text)
                .fallbackText(text)
                .segment(RichChatSegment.text(text));
    }

    public static RichMessageBuilder multilineText(String text) {
        return create()
                .type(RichChatMessageType.MULTILINE_TEXT)
                .rawText(text)
                .fallbackText(text)
                .segment(RichChatSegment.multilineText(text));
    }

    public RichMessageBuilder messageId(UUID messageId) {
        this.messageId = messageId == null ? UUID.randomUUID() : messageId;
        return this;
    }

    public RichMessageBuilder sender(UUID senderUuid, String senderName) {
        this.senderUuid = senderUuid;
        this.senderName = senderName == null ? "" : senderName;
        return this;
    }

    public RichMessageBuilder scope(RichChatScope scope) {
        this.scope = scope == null ? RichChatScope.PUBLIC : scope;
        return this;
    }

    public RichMessageBuilder type(RichChatMessageType type) {
        this.type = type == null ? RichChatMessageType.TEXT : type;
        return this;
    }

    public RichMessageBuilder fallbackText(String fallbackText) {
        this.fallbackText = fallbackText == null ? "" : fallbackText;
        return this;
    }

    public RichMessageBuilder rawText(String rawText) {
        this.rawText = rawText == null ? "" : rawText;
        return this;
    }

    public RichMessageBuilder segment(RichChatSegment segment) {
        if (segment != null) {
            this.segments.add(segment);
        }
        return this;
    }

    public RichMessageBuilder attachment(RichChatAttachment attachment) {
        if (attachment != null) {
            this.attachments.add(attachment);
            this.segments.add(RichChatSegment.attachment(attachment.attachmentId()));
        }
        return this;
    }

    public RichMessageBuilder createdAtMillis(long createdAtMillis) {
        this.createdAtMillis = createdAtMillis;
        return this;
    }

    public RichMessageBuilder expiresAtMillis(long expiresAtMillis) {
        this.expiresAtMillis = expiresAtMillis;
        return this;
    }

    public RichMessageBuilder metadata(String key, String value) {
        if (key != null && !key.isBlank() && value != null) {
            this.metadata.put(key, value);
        }
        return this;
    }

    public RichChatMessageData build() {
        return new RichChatMessageData(
                messageId,
                senderUuid,
                senderName,
                scope,
                type,
                fallbackText,
                rawText,
                segments,
                attachments,
                createdAtMillis,
                expiresAtMillis,
                metadata
        );
    }
}
