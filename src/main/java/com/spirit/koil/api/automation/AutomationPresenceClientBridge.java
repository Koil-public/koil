package com.spirit.koil.api.automation;

import com.spirit.koil.api.automation.cli.AutomationPresenceState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;

public final class AutomationPresenceClientBridge {
    private static boolean lastSentAutomationMode;
    private static String lastSentState = "idle";
    private static String lastSentDetail = "";
    private static long lastSentAt;

    private AutomationPresenceClientBridge() {
    }

    public static void registerReceiver() {
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
        if (client == null || client.player == null || client.getNetworkHandler() == null || !ClientPlayNetworking.canSend(AutomationPresenceNetwork.STATE_SYNC_PACKET)) {
            return;
        }
        boolean automationMode = AutomationPresenceState.localAutomationMode();
        String state = AutomationPresenceState.localState();
        String detail = AutomationPresenceState.localDetail();
        long now = System.currentTimeMillis();
        boolean changed = automationMode != lastSentAutomationMode || !state.equals(lastSentState) || !detail.equals(lastSentDetail);
        if (!changed && (!automationMode || now - lastSentAt < 1000L)) {
            return;
        }
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeBoolean(automationMode);
        buffer.writeString(state, 64);
        buffer.writeString(detail, 256);
        buffer.writeLong(AutomationPresenceState.localUpdatedAt());
        ClientPlayNetworking.send(AutomationPresenceNetwork.STATE_SYNC_PACKET, buffer);
        lastSentAutomationMode = automationMode;
        lastSentState = state;
        lastSentDetail = detail;
        lastSentAt = now;
    }
}
