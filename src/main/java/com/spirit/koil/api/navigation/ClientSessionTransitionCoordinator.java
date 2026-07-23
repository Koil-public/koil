package com.spirit.koil.api.navigation;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.Text;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns transitions from an active world or server session into another client
 * navigation flow. Minecraft must disconnect the client world before opening a
 * world selector, server selector, or connection screen.
 */
@Environment(EnvType.CLIENT)
public final class ClientSessionTransitionCoordinator {
    private static final int SETTLE_TICKS = 2;
    private static final int DISCONNECT_TIMEOUT_TICKS = 600;
    private static PendingTransition pendingTransition;

    private ClientSessionTransitionCoordinator() {
    }

    public static boolean openScreen(MinecraftClient client, Supplier<Screen> destination) {
        if (destination == null) {
            return false;
        }
        return run(client, ignored -> client.setScreen(destination.get()));
    }

    public static boolean run(MinecraftClient client, Consumer<MinecraftClient> completion) {
        if (client == null || completion == null || pendingTransition != null) {
            return false;
        }

        boolean activeSession = hasActiveSession(client);
        if (!activeSession) {
            completion.accept(client);
            return true;
        }

        pendingTransition = new PendingTransition(completion, 0, 0);
        boolean integrated = client.isInSingleplayer();
        Screen transitionScreen = integrated
                ? new MessageScreen(Text.translatable("menu.savingLevel"))
                : new TitleScreen();

        // Match vanilla's Game Menu disconnect lifecycle. Calling only
        // MinecraftClient.disconnect(Screen) leaves the ClientWorld alive long
        // enough for a second world/server flow to start against stale state.
        if (client.world != null) {
            client.world.disconnect();
        }
        client.disconnect(transitionScreen);
        return true;
    }

    public static void tick(MinecraftClient client) {
        PendingTransition pending = pendingTransition;
        if (client == null || pending == null) {
            return;
        }

        // ClientPlayNetworkHandler may intentionally outlive ClientWorld for a
        // few frames. Vanilla's safe handoff boundary is the world/integrated
        // server teardown, not immediate handler collection.
        if (hasLiveWorldOrServer(client)) {
            int waitTicks = pending.waitTicks() + 1;
            if (waitTicks >= DISCONNECT_TIMEOUT_TICKS) {
                pendingTransition = null;
                client.setScreen(new TitleScreen());
                return;
            }
            pendingTransition = pending.withWaitTicks(waitTicks);
            return;
        }

        if (pending.settleTicks() < SETTLE_TICKS) {
            pendingTransition = pending.withSettleTicks(pending.settleTicks() + 1);
            return;
        }

        pendingTransition = null;
        pending.completion().accept(client);
    }

    private static boolean hasActiveSession(MinecraftClient client) {
        return client.world != null || client.getServer() != null || client.getNetworkHandler() != null;
    }

    private static boolean hasLiveWorldOrServer(MinecraftClient client) {
        return client.world != null || client.getServer() != null;
    }

    private record PendingTransition(Consumer<MinecraftClient> completion, int waitTicks, int settleTicks) {
        private PendingTransition withWaitTicks(int nextWaitTicks) {
            return new PendingTransition(completion, nextWaitTicks, settleTicks);
        }

        private PendingTransition withSettleTicks(int nextSettleTicks) {
            return new PendingTransition(completion, waitTicks, nextSettleTicks);
        }
    }
}
