package com.spirit.koil.api.automation;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import com.spirit.koil.api.model.presence.ModelPresenceNetwork;
import com.spirit.koil.api.model.presence.ModelPresenceState;
import com.spirit.koil.api.model.presence.ModelPresenceWireCodec;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AutomationPresenceServerBridge {
    private static final Map<UUID, PresenceSnapshot> STATES = new ConcurrentHashMap<>();

    private AutomationPresenceServerBridge() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(ModelPresenceNetwork.STATE_SYNC_V1, (server, player, handler, buffer, responseSender) -> {
            ModelPresenceWireCodec.Decoded decoded = ModelPresenceWireCodec.read(buffer);
            if (!decoded.supported()) {
                return;
            }
            server.execute(() -> {
                PresenceSnapshot snapshot = new PresenceSnapshot(
                        player.getUuid(),
                        decoded.snapshot().kind(),
                        decoded.snapshot().semanticState(),
                        decoded.snapshot().active(),
                        decoded.snapshot().updatedAtMillis()
                );
                remember(snapshot);
                broadcast(server, snapshot);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(AutomationPresenceNetwork.STATE_SYNC_PACKET, (server, player, handler, buffer, responseSender) -> {
            boolean automationMode = buffer.readBoolean();
            String state = buffer.readString(64);
            String detail = buffer.readString(256);
            long updatedAt = buffer.readLong();
            if (ServerPlayNetworking.canSend(player, ModelPresenceNetwork.STATE_BROADCAST_V1)) {
                // A v1-capable client also emits the legacy packet for old
                // servers. Its v1 packet is authoritative on this server.
                return;
            }
            server.execute(() -> {
                PresenceSnapshot snapshot = new PresenceSnapshot(
                        player.getUuid(),
                        automationMode ? ModelPresenceState.ActivityKind.AUTOMATION : ModelPresenceState.ActivityKind.NONE,
                        state,
                        automationMode && !"idle".equals(state),
                        updatedAt <= 0L ? System.currentTimeMillis() : updatedAt
                );
                remember(snapshot);
                broadcast(server, snapshot);
            });
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity joining = handler.player;
            server.execute(() -> STATES.values().forEach(snapshot -> send(joining, snapshot)));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.player;
            server.execute(() -> {
                STATES.remove(player.getUuid());
                broadcast(server, new PresenceSnapshot(
                        player.getUuid(),
                        ModelPresenceState.ActivityKind.NONE,
                        "idle",
                        false,
                        System.currentTimeMillis()
                ));
            });
        });
    }

    private static void remember(PresenceSnapshot snapshot) {
        if (snapshot.kind() == ModelPresenceState.ActivityKind.NONE) {
            STATES.remove(snapshot.uuid());
        } else {
            STATES.put(snapshot.uuid(), snapshot);
        }
    }

    private static void broadcast(MinecraftServer server, PresenceSnapshot snapshot) {
        for (ServerPlayerEntity target : server.getPlayerManager().getPlayerList()) {
            send(target, snapshot);
        }
    }

    private static void send(ServerPlayerEntity target, PresenceSnapshot snapshot) {
        if (ServerPlayNetworking.canSend(target, ModelPresenceNetwork.STATE_BROADCAST_V1)) {
            ServerPlayNetworking.send(target, ModelPresenceNetwork.STATE_BROADCAST_V1, modelPacket(snapshot));
        }
        if (ServerPlayNetworking.canSend(target, AutomationPresenceNetwork.STATE_BROADCAST_PACKET)) {
            ServerPlayNetworking.send(target, AutomationPresenceNetwork.STATE_BROADCAST_PACKET, legacyPacket(snapshot));
        }
    }

    private static PacketByteBuf modelPacket(PresenceSnapshot snapshot) {
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeString(snapshot.uuid().toString(), 64);
        ModelPresenceWireCodec.write(
                buffer,
                new ModelPresenceState.Snapshot(
                        snapshot.kind(),
                        snapshot.state(),
                        snapshot.updatedAt(),
                        snapshot.active()
                ),
                snapshot.updatedAt()
        );
        return buffer;
    }

    private static PacketByteBuf legacyPacket(PresenceSnapshot snapshot) {
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeString(snapshot.uuid().toString(), 64);
        buffer.writeBoolean(snapshot.kind() != ModelPresenceState.ActivityKind.NONE);
        buffer.writeString(snapshot.state(), 64);
        buffer.writeString("", 256);
        buffer.writeLong(snapshot.updatedAt());
        return buffer;
    }

    private record PresenceSnapshot(
            UUID uuid,
            ModelPresenceState.ActivityKind kind,
            String state,
            boolean active,
            long updatedAt
    ) {
    }
}
