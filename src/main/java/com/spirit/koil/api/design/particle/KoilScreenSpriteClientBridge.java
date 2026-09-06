package com.spirit.koil.api.design.particle;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Receives bounded screen-sprite requests and installs the persistent HUD renderer. */
public final class KoilScreenSpriteClientBridge {
    private static boolean registered;

    private KoilScreenSpriteClientBridge() { }

    public static synchronized void registerReceiver() {
        if (registered) return;
        registered = true;
        KoilGameParticleRegistryBridge.registerAllAvailable();
        KoilScreenSpriteOverlay.registerHudRenderer();
        ClientPlayNetworking.registerGlobalReceiver(KoilScreenSpriteNetwork.SPRITE_PACKET, (client, handler, buffer, responseSender) -> {
            KoilScreenSpriteRequest request = new KoilScreenSpriteRequest(
                    buffer.readString(128), buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readFloat(),
                    buffer.readString(KoilScreenSpriteRequest.MAX_OVERRIDE_LENGTH)
            );
            client.execute(() -> KoilScreenSpriteOverlay.enqueue(request));
        });
    }
}
