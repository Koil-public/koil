package com.spirit.koil.api.chat.sync;

import com.spirit.koil.api.chat.RichChatAttachment;
import com.spirit.koil.api.chat.RichChatMessageData;
import com.spirit.koil.api.chat.RichChatMessageType;
import com.spirit.koil.api.chat.RichChatScope;
import com.spirit.koil.api.chat.RichChatSegment;
import com.spirit.koil.api.chat.RichChatSegmentType;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RichChatSyncNetwork {
    public static final Identifier CLIENT_UPLOAD_PACKET = new Identifier("koil", "rich_chat_sync_upload");
    public static final Identifier SERVER_DELIVER_PACKET = new Identifier("koil", "rich_chat_sync_deliver");
    private static final int MAX_TEXT = 32767;
    private static final int MAX_NAME = 256;
    private static final int MAX_PATH = 1024;
    private static final int MAX_MAP_ENTRIES = 128;
    private static final int MAX_KEY = 128;
    private static final int MAX_VALUE = 4096;

    private RichChatSyncNetwork() {
    }

    public static void write(PacketByteBuf buffer, RichChatMessageData message, List<byte[]> attachmentPayloads) {
        buffer.writeUuid(message.messageId());
        buffer.writeBoolean(message.senderUuid() != null);
        if (message.senderUuid() != null) {
            buffer.writeUuid(message.senderUuid());
        }
        buffer.writeString(trim(message.senderName(), MAX_NAME), MAX_NAME);
        buffer.writeString(message.scope().name(), 32);
        buffer.writeString(message.type().name(), 32);
        buffer.writeString(trim(message.fallbackText(), MAX_TEXT), MAX_TEXT);
        buffer.writeString(trim(message.rawText(), MAX_TEXT), MAX_TEXT);
        buffer.writeLong(message.createdAtMillis());
        buffer.writeLong(message.expiresAtMillis());
        writeMap(buffer, message.metadata());

        List<RichChatSegment> segments = message.segments() == null ? List.of() : message.segments();
        buffer.writeInt(segments.size());
        for (RichChatSegment segment : segments) {
            buffer.writeString(segment.type().name(), 32);
            buffer.writeString(trim(segment.text(), MAX_TEXT), MAX_TEXT);
            buffer.writeBoolean(segment.attachmentId() != null);
            if (segment.attachmentId() != null) {
                buffer.writeUuid(segment.attachmentId());
            }
            writeMap(buffer, segment.metadata());
        }

        List<RichChatAttachment> attachments = message.attachments() == null ? List.of() : message.attachments();
        buffer.writeInt(attachments.size());
        for (int i = 0; i < attachments.size(); i++) {
            RichChatAttachment attachment = attachments.get(i);
            byte[] payload = attachmentPayloads != null && i < attachmentPayloads.size() ? attachmentPayloads.get(i) : new byte[0];
            buffer.writeUuid(attachment.attachmentId());
            buffer.writeString(attachment.type().name(), 32);
            buffer.writeString(trim(attachment.fileName(), MAX_PATH), MAX_PATH);
            buffer.writeString(trim(attachment.mimeType(), MAX_NAME), MAX_NAME);
            buffer.writeLong(attachment.sizeBytes());
            buffer.writeString(trim(attachment.sha256(), 128), 128);
            buffer.writeInt(attachment.width());
            buffer.writeInt(attachment.height());
            buffer.writeLong(attachment.durationMillis());
            writeMap(buffer, attachment.metadata());
            buffer.writeInt(payload == null ? 0 : payload.length);
            if (payload != null && payload.length > 0) {
                buffer.writeBytes(payload);
            }
        }
    }

    public static SyncedMessage read(PacketByteBuf buffer) {
        UUID messageId = buffer.readUuid();
        UUID senderUuid = buffer.readBoolean() ? buffer.readUuid() : null;
        String senderName = buffer.readString(MAX_NAME);
        RichChatScope scope = readEnum(buffer.readString(32), RichChatScope.PUBLIC, RichChatScope.class);
        RichChatMessageType type = readEnum(buffer.readString(32), RichChatMessageType.TEXT, RichChatMessageType.class);
        String fallbackText = buffer.readString(MAX_TEXT);
        String rawText = buffer.readString(MAX_TEXT);
        long createdAtMillis = buffer.readLong();
        long expiresAtMillis = buffer.readLong();
        Map<String, String> metadata = readMap(buffer);

        int segmentCount = Math.max(0, buffer.readInt());
        List<RichChatSegment> segments = new ArrayList<>(segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            RichChatSegmentType segmentType = readEnum(buffer.readString(32), RichChatSegmentType.TEXT, RichChatSegmentType.class);
            String text = buffer.readString(MAX_TEXT);
            UUID attachmentId = buffer.readBoolean() ? buffer.readUuid() : null;
            Map<String, String> segmentMetadata = readMap(buffer);
            segments.add(new RichChatSegment(segmentType, text, attachmentId, segmentMetadata));
        }

        int attachmentCount = Math.max(0, buffer.readInt());
        List<RichChatAttachment> attachments = new ArrayList<>(attachmentCount);
        List<byte[]> payloads = new ArrayList<>(attachmentCount);
        for (int i = 0; i < attachmentCount; i++) {
            UUID attachmentId = buffer.readUuid();
            RichChatAttachment attachment = new RichChatAttachment(
                    attachmentId,
                    readEnum(buffer.readString(32), com.spirit.koil.api.chat.RichChatAttachmentType.FILE, com.spirit.koil.api.chat.RichChatAttachmentType.class),
                    buffer.readString(MAX_PATH),
                    buffer.readString(MAX_NAME),
                    buffer.readLong(),
                    buffer.readString(128),
                    buffer.readInt(),
                    buffer.readInt(),
                    buffer.readLong(),
                    readMap(buffer)
            );
            int payloadLength = Math.max(0, buffer.readInt());
            byte[] payload = new byte[payloadLength];
            if (payloadLength > 0) {
                buffer.readBytes(payload);
            }
            attachments.add(attachment);
            payloads.add(payload);
        }

        RichChatMessageData message = new RichChatMessageData(
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
        return new SyncedMessage(message, payloads);
    }

    private static void writeMap(PacketByteBuf buffer, Map<String, String> values) {
        Map<String, String> safeValues = values == null ? Map.of() : values;
        buffer.writeInt(Math.min(MAX_MAP_ENTRIES, safeValues.size()));
        int written = 0;
        for (Map.Entry<String, String> entry : safeValues.entrySet()) {
            if (written >= MAX_MAP_ENTRIES) {
                break;
            }
            buffer.writeString(trim(entry.getKey(), MAX_KEY), MAX_KEY);
            buffer.writeString(trim(entry.getValue(), MAX_VALUE), MAX_VALUE);
            written++;
        }
    }

    private static Map<String, String> readMap(PacketByteBuf buffer) {
        int size = Math.max(0, Math.min(MAX_MAP_ENTRIES, buffer.readInt()));
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            values.put(buffer.readString(MAX_KEY), buffer.readString(MAX_VALUE));
        }
        return values;
    }

    private static <E extends Enum<E>> E readEnum(String name, E fallback, Class<E> type) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record SyncedMessage(RichChatMessageData message, List<byte[]> attachmentPayloads) {
    }
}
