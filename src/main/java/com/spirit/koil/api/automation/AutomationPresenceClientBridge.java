package com.spirit.koil.api.automation;

import com.spirit.koil.api.automation.cli.AutomationPresenceState;
import com.spirit.koil.api.model.presence.ModelPresenceNetwork;
import com.spirit.koil.api.model.presence.ModelPresenceState;
import com.spirit.koil.api.model.presence.ModelPresenceWireCodec;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;

public final class AutomationPresenceClientBridge {
    private static ModelPresenceState.ActivityKind lastSentKind = ModelPresenceState.ActivityKind.NONE;
    private static String lastSentState = "idle";
    private static boolean lastSentActive;
    private static long lastSentAt;

    private AutomationPresenceClientBridge() {
    }

    public static void registerReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(ModelPresenceNetwork.STATE_BROADCAST_V1, (client, handler, buffer, responseSender) -> {
            String uuid = buffer.readString(64);
            ModelPresenceWireCodec.Decoded decoded = ModelPresenceWireCodec.read(buffer);
            if (!decoded.supported()) {
                return;
            }
            client.execute(() -> {
                try {
                    ModelPresenceState.receiveRemote(
                            java.util.UUID.fromString(uuid),
                            decoded.snapshot()
                    );
                } catch (IllegalArgumentException ignored) {
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(AutomationPresenceNetwork.STATE_BROADCAST_PACKET, (client, handler, buffer, responseSender) -> {
            String uuid = buffer.readString(64);
            boolean automationMode = buffer.readBoolean();
            String state = buffer.readString(64);
            String detail = buffer.readString(256);
            long updatedAt = buffer.readLong();
            client.execute(() -> {
                try {
                    AutomationPresenceState.receiveRemote(java.util.UUID.fromString(uuid), automationMode, state, detail, updatedAt);
                } catch (IllegalArgumentException ignored) {
                }
            });
        });
    }

    public static void tick(MinecraftClient client) {
        if (client == null || client.player == null || client.getNetworkHandler() == null) {
            return;
        }
        boolean newChannel = ClientPlayNetworking.canSend(ModelPresenceNetwork.STATE_SYNC_V1);
        boolean legacyChannel = ClientPlayNetworking.canSend(AutomationPresenceNetwork.STATE_SYNC_PACKET);
        if (!newChannel && !legacyChannel) {
            return;
        }
        ModelPresenceState.Snapshot snapshot = ModelPresenceState.localSnapshot();
        String state = snapshot.semanticState();
        long now = System.currentTimeMillis();
        boolean changed = snapshot.kind() != lastSentKind
                || !state.equals(lastSentState)
                || snapshot.active() != lastSentActive;
        if (!changed && (snapshot.kind() == ModelPresenceState.ActivityKind.NONE || now - lastSentAt < 1000L)) {
            return;
        }
        if (newChannel) {
            PacketByteBuf buffer = PacketByteBufs.create();
            ModelPresenceWireCodec.write(buffer, snapshot, now);
            ClientPlayNetworking.send(ModelPresenceNetwork.STATE_SYNC_V1, buffer);
        }
        if (legacyChannel) {
            PacketByteBuf legacy = PacketByteBufs.create();
            legacy.writeBoolean(snapshot.kind() != ModelPresenceState.ActivityKind.NONE);
            legacy.writeString(state, 64);
            legacy.writeString("", 256);
            legacy.writeLong(now);
            ClientPlayNetworking.send(AutomationPresenceNetwork.STATE_SYNC_PACKET, legacy);
        }
        lastSentKind = snapshot.kind();
        lastSentState = state;
        lastSentActive = snapshot.active();
        lastSentAt = now;
    }
}
