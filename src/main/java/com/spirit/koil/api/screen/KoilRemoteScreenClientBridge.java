package com.spirit.koil.api.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Applies only registry-backed screen requests received from a Koil server. */
public final class KoilRemoteScreenClientBridge {
    private KoilRemoteScreenClientBridge() {
    }

    public static void registerReceiver() {
        KoilRemoteScreenRegistry.registerBuiltins();
        ClientPlayNetworking.registerGlobalReceiver(KoilRemoteScreenNetwork.SCREEN_REQUEST_PACKET, (client, handler, buffer, responseSender) -> {
            boolean close = buffer.readBoolean();
            String id = buffer.readString(256);
            String data = buffer.readString(4096);
            client.execute(() -> {
                if (close) {
                    client.setScreen(null);
                    return;
                }
                net.minecraft.client.gui.screen.Screen requested = KoilRemoteScreenRegistry.create(client, client.currentScreen, id, data);
                if (requested != null) {
                    client.setScreen(requested);
                }
            });
        });
    }
}
