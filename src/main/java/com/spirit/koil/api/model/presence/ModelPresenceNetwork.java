package com.spirit.koil.api.model.presence;

import net.minecraft.util.Identifier;

public final class ModelPresenceNetwork {
    public static final Identifier STATE_SYNC_V1 = new Identifier("koil", "model_presence_sync_v1");
    public static final Identifier STATE_BROADCAST_V1 = new Identifier("koil", "model_presence_broadcast_v1");

    private ModelPresenceNetwork() {
    }
}
