package com.spirit.koil.api.model.deepthought;

import com.spirit.koil.api.model.LocalModelService;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.atomic.AtomicBoolean;

/** Reattaches the newest persisted investigation after the joined world/server has settled. */
public final class DeepThoughtSessionLifecycleBridge {
    private static final int JOIN_SETTLE_TICKS = 40;
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static volatile int pendingRestoreTicks = -1;

    private DeepThoughtSessionLifecycleBridge() {}

    public static void initialize() {
        if (!REGISTERED.compareAndSet(false, true)) return;
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> pendingRestoreTicks = JOIN_SETTLE_TICKS);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            pendingRestoreTicks = -1;
            LocalModelService.clearDeepThoughtLifecycleRestoreIdentity();
        });
    }

    public static void tick(MinecraftClient client) {
        if (client == null || client.world == null || pendingRestoreTicks < 0) return;
        if (pendingRestoreTicks-- > 0) return;
        pendingRestoreTicks = -1;
        LocalModelService.restoreDeepThoughtForCurrentScope();
    }
}
