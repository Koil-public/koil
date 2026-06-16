package com.spirit.koil.api.chat;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RichChatSegment(
        RichChatSegmentType type,
        String text,
        UUID attachmentId,
        Map<String, String> metadata
) {
    public RichChatSegment {
        type = Objects.requireNonNull(type, "type");
        text = text == null ? "" : text;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static RichChatSegment text(String text) {
        return new RichChatSegment(RichChatSegmentType.TEXT, text, null, Map.of());
    }

    public static RichChatSegment multilineText(String text) {
        return new RichChatSegment(RichChatSegmentType.MULTILINE_TEXT, text, null, Map.of());
    }

    public static RichChatSegment attachment(UUID attachmentId) {
        return new RichChatSegment(RichChatSegmentType.ATTACHMENT, "", attachmentId, Map.of());
    }
}
