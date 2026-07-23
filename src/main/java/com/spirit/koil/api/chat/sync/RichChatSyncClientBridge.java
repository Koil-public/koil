package com.spirit.koil.api.chat.sync;

import com.spirit.koil.api.chat.RichChatAttachment;
import com.spirit.koil.api.chat.RichChatMessageData;
import com.spirit.koil.api.chat.RichChatScope;
import com.spirit.koil.api.chat.LocalOverflowChatBridge;
import com.spirit.koil.api.chat.RichChatPreviewFormatter;
import com.spirit.koil.api.chat.LocalMultilineChatBridge;
import com.spirit.koil.api.chat.RichChatMessageStore;
import com.spirit.koil.api.chat.upload.LocalRichAttachmentBridge;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RichChatSyncClientBridge {
    private static final Path CLIENT_CACHE_ROOT = Path.of("koil", "cache", "chat_media", "synced");

    private RichChatSyncClientBridge() {
    }

    public static void registerReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(RichChatSyncNetwork.SERVER_DELIVER_PACKET, (client, handler, buffer, responseSender) -> {
            RichChatSyncNetwork.SyncedMessage synced = RichChatSyncNetwork.read(buffer);
            client.execute(() -> accept(synced));
        });
    }

    public static boolean canSync() {
        return ClientPlayNetworking.canSend(RichChatSyncNetwork.CLIENT_UPLOAD_PACKET);
    }

    public static void send(RichChatMessageData message) {
        if (message == null || !canSync()) {
            return;
        }
        PacketByteBuf buffer = PacketByteBufs.create();
        RichChatSyncNetwork.write(buffer, message, attachmentPayloads(message.attachments()));
        ClientPlayNetworking.send(RichChatSyncNetwork.CLIENT_UPLOAD_PACKET, buffer);
    }

    private static void accept(RichChatSyncNetwork.SyncedMessage synced) {
        if (synced == null || synced.message() == null) {
            return;
        }
        if (isLocalSender(synced.message())) {
            return;
        }
        List<RichChatAttachment> attachments = synced.message().attachments();
        List<RichChatAttachment> cachedAttachments = new ArrayList<>(attachments.size());
        List<byte[]> payloads = synced.attachmentPayloads();
        for (int i = 0; i < attachments.size(); i++) {
            RichChatAttachment attachment = attachments.get(i);
            byte[] payload = payloads != null && i < payloads.size() ? payloads.get(i) : new byte[0];
            cachedAttachments.add(cacheAttachment(attachment, payload));
        }
        RichChatMessageData cachedMessage = new RichChatMessageData(
                synced.message().messageId(),
                synced.message().senderUuid(),
                synced.message().senderName(),
                synced.message().scope(),
                synced.message().type(),
                synced.message().fallbackText(),
                synced.message().rawText(),
                synced.message().segments(),
                cachedAttachments,
                synced.message().createdAtMillis(),
                synced.message().expiresAtMillis(),
                synced.message().metadata()
        );
        RichChatMessageStore.remember(cachedMessage);
        RichChatSyncedMessageBridge.remember(cachedMessage);
        String previewChunk = firstChunk(cachedMessage);
        if (cachedAttachments.size() == 1 && previewChunk != null && !previewChunk.isBlank()) {
            LocalRichAttachmentBridge.remember(previewChunk, cachedMessage.rawText(), cachedAttachments.get(0));
        } else if (cachedAttachments.size() == 1 && cachedMessage.fallbackText() != null && !cachedMessage.fallbackText().isBlank()) {
            LocalRichAttachmentBridge.remember(cachedMessage.fallbackText(), cachedMessage.rawText(), cachedAttachments.get(0));
        } else if (cachedAttachments.isEmpty() && cachedMessage.rawText() != null && cachedMessage.rawText().indexOf('\n') >= 0) {
            LocalMultilineChatBridge.remember(cachedMessage.fallbackText(), cachedMessage.rawText());
        }
        if (hasChunkMetadata(cachedMessage)) {
            showChunkedPreview(cachedMessage);
        }
    }

    private static boolean isLocalSender(RichChatMessageData message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || message == null) {
            return false;
        }
        if (message.senderUuid() != null && client.player.getUuid() != null && message.senderUuid().equals(client.player.getUuid())) {
            return true;
        }
        return message.senderName() != null
                && client.player.getGameProfile() != null
                && message.senderName().equalsIgnoreCase(client.player.getGameProfile().getName());
    }

    private static List<byte[]> attachmentPayloads(List<RichChatAttachment> attachments) {
        List<byte[]> payloads = new ArrayList<>();
        if (attachments == null) {
            return payloads;
        }
        for (RichChatAttachment attachment : attachments) {
            payloads.add(readAttachmentBytes(attachment));
        }
        return payloads;
    }

    private static byte[] readAttachmentBytes(RichChatAttachment attachment) {
        if (attachment == null || attachment.metadata() == null) {
            return new byte[0];
        }
        String rawPath = attachment.metadata().getOrDefault("server_path", attachment.metadata().getOrDefault("client_cache_path", ""));
        if (rawPath.isBlank()) {
            return new byte[0];
        }
        try {
            Path path = Path.of(rawPath).toAbsolutePath().normalize();
            return Files.isRegularFile(path) ? Files.readAllBytes(path) : new byte[0];
        } catch (Exception ignored) {
            return new byte[0];
        }
    }

    private static RichChatAttachment cacheAttachment(RichChatAttachment attachment, byte[] payload) {
        if (attachment == null || payload == null || payload.length == 0) {
            return attachment;
        }
        try {
            Files.createDirectories(CLIENT_CACHE_ROOT);
            String fileName = safeFileName(attachment.fileName());
            Path target = CLIENT_CACHE_ROOT.resolve(attachment.attachmentId() + "-" + fileName);
            Files.write(target, payload);
            Map<String, String> metadata = new LinkedHashMap<>(attachment.metadata());
            metadata.put("client_cache_path", target.toAbsolutePath().normalize().toString());
            metadata.put("upload_state", "synced");
            return new RichChatAttachment(
                    attachment.attachmentId(),
                    attachment.type(),
                    attachment.fileName(),
                    attachment.mimeType(),
                    attachment.sizeBytes(),
                    attachment.sha256(),
                    attachment.width(),
                    attachment.height(),
                    attachment.durationMillis(),
                    metadata
            );
        } catch (Exception ignored) {
            return attachment;
        }
    }

    private static String safeFileName(String value) {
        String cleaned = value == null || value.isBlank() ? "attachment" : value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isBlank() ? "attachment" : cleaned;
    }

    private static boolean hasChunkMetadata(RichChatMessageData message) {
        if (message == null || message.metadata() == null) {
            return false;
        }
        return !message.metadata().getOrDefault("chunk_count", "").isBlank();
    }

    private static String firstChunk(RichChatMessageData message) {
        if (message == null || message.metadata() == null) {
            return "";
        }
        return message.metadata().getOrDefault("chunk_0", "");
    }

    private static void showChunkedPreview(RichChatMessageData message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.inGameHud == null) {
            return;
        }
        net.minecraft.text.Text preview = RichChatSyncedMessageBridge.immediateVisibleMessage(message);
        if (preview == null || preview.getString().isBlank()) {
            return;
        }
        net.minecraft.text.Text formattedPreview = RichChatPreviewFormatter.format(preview);
        client.inGameHud.getChatHud().addMessage(formattedPreview == null ? preview : formattedPreview);
        RichChatSyncedMessageBridge.discard(message.messageId());
        List<String> chunks = new ArrayList<>();
        try {
            String countKey = message.metadata().containsKey("sent_chunk_count") ? "sent_chunk_count" : "chunk_count";
            String itemKey = message.metadata().containsKey("sent_chunk_count") ? "sent_chunk_" : "chunk_";
            int count = Math.max(0, Integer.parseInt(message.metadata().getOrDefault(countKey, "0")));
            for (int i = 0; i < count; i++) {
                String chunk = message.metadata().getOrDefault(itemKey + i, "");
                if (!chunk.isBlank()) {
                    chunks.add(chunk);
                }
            }
        } catch (Exception ignored) {
            return;
        }
        LocalOverflowChatBridge.remember("", chunks);
    }
}
