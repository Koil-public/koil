package com.spirit.koil.api.automation;

import net.minecraft.util.Identifier;

public final class AutomationPresenceNetwork {
    public static final Identifier STATE_SYNC_PACKET = new Identifier("koil", "automation_state_sync");
    public static final Identifier STATE_BROADCAST_PACKET = new Identifier("koil", "automation_state_broadcast");

    private AutomationPresenceNetwork() {
    }
}
