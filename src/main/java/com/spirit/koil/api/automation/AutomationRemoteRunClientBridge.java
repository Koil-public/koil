package com.spirit.koil.api.automation;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class AutomationRemoteRunClientBridge {
    private AutomationRemoteRunClientBridge() {
    }

    public static void registerReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(AutomationRemoteRunNetwork.RUN_AS_PACKET, (client, handler, buffer, responseSender) -> {
            String input = buffer.readString(32767);
            String actor = buffer.readString(256);
            client.execute(() -> {
                if (client.player == null) {
                    return;
                }
                String trimmed = input == null ? "" : input.trim();
                AutomationRouter.handleInput(new AutomationRequest(trimmed, true, trimmed.endsWith(".ktl") || trimmed.contains(".ktl ")), actor);
                AutomationReporter.info("[info]", "remote.actor = " + actor + "  runtime = target_client");
            });
        });
    }
}
