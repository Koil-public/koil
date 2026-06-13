package com.spirit.koil.api.automation;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AutomationPresenceServerBridge {
    private static final Map<UUID, PresenceSnapshot> STATES = new ConcurrentHashMap<>();

    private AutomationPresenceServerBridge() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(AutomationPresenceNetwork.STATE_SYNC_PACKET, (server, player, handler, buffer, responseSender) -> {
            boolean automationMode = buffer.readBoolean();
            String state = buffer.readString(64);
            String detail = buffer.readString(256);
            long updatedAt = buffer.readLong();
            server.execute(() -> {
                PresenceSnapshot snapshot = new PresenceSnapshot(player.getUuid(), automationMode, state, detail, updatedAt <= 0L ? System.currentTimeMillis() : updatedAt);
                if (automationMode) {
                    STATES.put(player.getUuid(), snapshot);
                } else {
                    STATES.remove(player.getUuid());
                }
                broadcast(server, snapshot);
            });
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity joining = handler.player;
            server.execute(() -> STATES.values().forEach(snapshot -> ServerPlayNetworking.send(joining, AutomationPresenceNetwork.STATE_BROADCAST_PACKET, packet(snapshot))));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.player;
            server.execute(() -> {
                STATES.remove(player.getUuid());
                broadcast(server, new PresenceSnapshot(player.getUuid(), false, "idle", "", System.currentTimeMillis()));
            });
        });
    }

    private static void broadcast(MinecraftServer server, PresenceSnapshot snapshot) {
        for (ServerPlayerEntity target : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(target, AutomationPresenceNetwork.STATE_BROADCAST_PACKET, packet(snapshot));
        }
    }

    private static PacketByteBuf packet(PresenceSnapshot snapshot) {
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeString(snapshot.uuid().toString(), 64);
        buffer.writeBoolean(snapshot.automationMode());
        buffer.writeString(snapshot.state(), 64);
        buffer.writeString(snapshot.detail(), 256);
        buffer.writeLong(snapshot.updatedAt());
        return buffer;
    }

    private record PresenceSnapshot(UUID uuid, boolean automationMode, String state, String detail, long updatedAt) {
    }
}
