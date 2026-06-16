package com.spirit.koil.api.chat;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RichChatAttachment(
        UUID attachmentId,
        RichChatAttachmentType type,
        String fileName,
        String mimeType,
        long sizeBytes,
        String sha256,
        int width,
        int height,
        long durationMillis,
        Map<String, String> metadata
) {
    public RichChatAttachment {
        attachmentId = attachmentId == null ? UUID.randomUUID() : attachmentId;
        type = Objects.requireNonNull(type, "type");
        fileName = fileName == null ? "" : fileName;
        mimeType = mimeType == null ? "" : mimeType;
        sha256 = sha256 == null ? "" : sha256;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
