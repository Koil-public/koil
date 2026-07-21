package com.spirit.koil.api.screen;

import net.minecraft.util.Identifier;

/** Network contract for server-authorized Koil screen requests. */
public final class KoilRemoteScreenNetwork {
    public static final Identifier SCREEN_REQUEST_PACKET = new Identifier("koil", "screen_request");

    private KoilRemoteScreenNetwork() {
    }
}
