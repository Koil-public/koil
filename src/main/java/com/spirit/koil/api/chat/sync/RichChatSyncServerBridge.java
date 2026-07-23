package com.spirit.koil.api.chat.sync;

import com.spirit.koil.api.chat.RichChatAttachment;
import com.spirit.koil.api.chat.RichChatMessageData;
import com.spirit.koil.api.chat.RichChatScope;
import com.spirit.koil.api.chat.upload.RichChatUploadStorage;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RichChatSyncServerBridge {
    private RichChatSyncServerBridge() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(RichChatSyncNetwork.CLIENT_UPLOAD_PACKET, (server, player, handler, buffer, responseSender) -> {
            RichChatSyncNetwork.SyncedMessage synced = RichChatSyncNetwork.read(buffer);
            server.execute(() -> accept(server, player, synced));
        });
    }

    private static void accept(MinecraftServer server, ServerPlayerEntity player, RichChatSyncNetwork.SyncedMessage synced) {
        if (server == null || player == null || synced == null || synced.message() == null) {
            return;
        }
        RichChatMessageData sanitized = sanitize(player, synced.message(), synced.attachmentPayloads());
        if (sanitized == null) {
            return;
        }
        List<byte[]> payloads = loadServerPayloads(sanitized.attachments());
        if (sanitized.scope() == RichChatScope.PRIVATE) {
            ServerPlayerEntity target = targetPlayer(server, sanitized.metadata().get("pm_target"));
            if (target != null && !target.getUuid().equals(player.getUuid()) && ServerPlayNetworking.canSend(target, RichChatSyncNetwork.SERVER_DELIVER_PACKET)) {
                PacketByteBuf deliver = PacketByteBufs.create();
                RichChatSyncNetwork.write(deliver, sanitized, payloads);
                ServerPlayNetworking.send(target, RichChatSyncNetwork.SERVER_DELIVER_PACKET, deliver);
            }
            return;
        }
        for (ServerPlayerEntity target : server.getPlayerManager().getPlayerList()) {
            if (target == null || target.getUuid().equals(player.getUuid()) || !ServerPlayNetworking.canSend(target, RichChatSyncNetwork.SERVER_DELIVER_PACKET)) {
                continue;
            }
            PacketByteBuf deliver = PacketByteBufs.create();
            RichChatSyncNetwork.write(deliver, sanitized, payloads);
            ServerPlayNetworking.send(target, RichChatSyncNetwork.SERVER_DELIVER_PACKET, deliver);
        }
    }

    private static RichChatMessageData sanitize(ServerPlayerEntity player, RichChatMessageData message, List<byte[]> payloads) {
        List<RichChatAttachment> attachments = new ArrayList<>();
        List<RichChatAttachment> incoming = message.attachments();
        boolean privateScope = message.scope() == RichChatScope.PRIVATE;
        for (int i = 0; i < incoming.size(); i++) {
            RichChatAttachment attachment = incoming.get(i);
            byte[] payload = payloads != null && i < payloads.size() ? payloads.get(i) : new byte[0];
            RichChatAttachment stored = storeAttachment(attachment, payload, player);
            attachments.add(privateScope ? privateAttachmentForRecipient(stored, player) : stored);
        }
        Map<String, String> metadata = new LinkedHashMap<>(message.metadata());
        if (privateScope) {
            String target = metadata.getOrDefault("pm_target", "");
            if (target.isBlank()) {
                return null;
            }
            metadata.put("pm_target", target);
        }
        return new RichChatMessageData(
                message.messageId(),
                player.getUuid(),
                player.getGameProfile().getName(),
                message.scope() == RichChatScope.PRIVATE ? RichChatScope.PRIVATE : RichChatScope.PUBLIC,
                message.type(),
                message.fallbackText(),
                message.rawText(),
                message.segments(),
                attachments,
                message.createdAtMillis() <= 0L ? System.currentTimeMillis() : message.createdAtMillis(),
                message.expiresAtMillis(),
                metadata
        );
    }

    private static RichChatAttachment privateAttachmentForRecipient(RichChatAttachment attachment, ServerPlayerEntity sender) {
        if (attachment == null) {
            return null;
        }
        Map<String, String> metadata = new LinkedHashMap<>(attachment.metadata());
        metadata.put("chat_scope", "private");
        if (sender != null && sender.getGameProfile() != null && sender.getGameProfile().getName() != null) {
            metadata.put("chat_sender", sender.getGameProfile().getName());
            metadata.put("chat_partner", sender.getGameProfile().getName());
        }
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
    }

    private static RichChatAttachment storeAttachment(RichChatAttachment attachment, byte[] payload, ServerPlayerEntity player) {
        if (attachment == null || payload == null || payload.length == 0) {
            return attachment;
        }
        try {
            RichChatUploadStorage.StoredAttachment stored = RichChatUploadStorage.stageTransferredBytes(
                    attachment.fileName(),
                    payload,
                    player.getUuid(),
                    player.getGameProfile().getName()
            );
            RichChatAttachment serverAttachment = stored == null ? null : stored.attachment();
            if (serverAttachment == null) {
                return attachment;
            }
            Map<String, String> metadata = new LinkedHashMap<>(serverAttachment.metadata());
            metadata.putAll(attachment.metadata());
            metadata.put("server_path", serverAttachment.metadata().getOrDefault("server_path", ""));
            return new RichChatAttachment(
                    serverAttachment.attachmentId(),
                    serverAttachment.type(),
                    serverAttachment.fileName(),
                    serverAttachment.mimeType(),
                    serverAttachment.sizeBytes(),
                    serverAttachment.sha256(),
                    serverAttachment.width(),
                    serverAttachment.height(),
                    serverAttachment.durationMillis(),
                    metadata
            );
        } catch (Exception ignored) {
            return attachment;
        }
    }

    private static List<byte[]> loadServerPayloads(List<RichChatAttachment> attachments) {
        List<byte[]> payloads = new ArrayList<>();
        for (RichChatAttachment attachment : attachments) {
            payloads.add(readServerBytes(attachment));
        }
        return payloads;
    }

    private static byte[] readServerBytes(RichChatAttachment attachment) {
        if (attachment == null || attachment.metadata() == null) {
            return new byte[0];
        }
        String rawPath = attachment.metadata().getOrDefault("server_path", "");
        if (rawPath.isBlank()) {
            return new byte[0];
        }
        try {
            java.nio.file.Path path = java.nio.file.Path.of(rawPath).toAbsolutePath().normalize();
            return java.nio.file.Files.isRegularFile(path) ? java.nio.file.Files.readAllBytes(path) : new byte[0];
        } catch (Exception ignored) {
            return new byte[0];
        }
    }

    private static ServerPlayerEntity targetPlayer(MinecraftServer server, String targetName) {
        if (server == null || targetName == null || targetName.isBlank()) {
            return null;
        }
        for (ServerPlayerEntity candidate : server.getPlayerManager().getPlayerList()) {
            if (candidate != null && candidate.getGameProfile() != null
                    && candidate.getGameProfile().getName() != null
                    && candidate.getGameProfile().getName().toLowerCase(Locale.ROOT).equals(targetName.trim().toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
        return null;
    }
}
