package com.spirit.koil.api.automation;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A server-tick pause that resumes a command later with the original source. */
public final class KoilCommandPauseBridge {
    private static final List<DelayedCommand> PENDING = new ArrayList<>();
    private static long nextSequence;

    private KoilCommandPauseBridge() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(CommandManager.literal("sleep")
                .then(CommandManager.argument("ticks", IntegerArgumentType.integer(1, 72_000))
                        .then(CommandManager.literal("run")
                                .then(CommandManager.argument("command", StringArgumentType.greedyString())
                                        .executes(context -> schedule(context.getSource(), IntegerArgumentType.getInteger(context, "ticks"), StringArgumentType.getString(context, "command"))))))));
        ServerTickEvents.END_SERVER_TICK.register(KoilCommandPauseBridge::tick);
    }

    private static int schedule(ServerCommandSource source, int ticks, String command) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return 0;
        }
        synchronized (PENDING) {
            PENDING.add(new DelayedCommand(source, Math.max(1, ticks), normalized, nextSequence++));
        }
        return 1;
    }

    private static void tick(MinecraftServer server) {
        List<DelayedCommand> ready = new ArrayList<>();
        synchronized (PENDING) {
            for (int index = PENDING.size() - 1; index >= 0; index--) {
                DelayedCommand delayed = PENDING.get(index);
                if (delayed.remainingTicks() > 1) {
                    PENDING.set(index, delayed.withRemainingTicks(delayed.remainingTicks() - 1));
                    continue;
                }
                PENDING.remove(index);
                ready.add(delayed);
            }
        }
        // Iteration removes back-to-front, but equal-delay commands must run in
        // the order the player entered them.
        ready.sort(Comparator.comparingLong(DelayedCommand::sequence));
        for (DelayedCommand delayed : ready) {
            server.getCommandManager().executeWithPrefix(delayed.source(), delayed.command());
        }
    }

    private record DelayedCommand(ServerCommandSource source, int remainingTicks, String command, long sequence) {
        private DelayedCommand withRemainingTicks(int value) {
            return new DelayedCommand(source, value, command, sequence);
        }
    }
}
