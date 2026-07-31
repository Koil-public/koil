package com.spirit.koil.api.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Client-owned process exit command. Plain {@code /exit} uses Minecraft's
 * normal title-screen shutdown path. An explicit code still requests that
 * graceful shutdown first, then returns the requested process status after
 * the client run loop has stopped.
 */
public final class ExitCommandBridge {
    private ExitCommandBridge() {
    }

    public static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("exit")
                        .executes(context -> exitNormally())
                        .then(argument("code", IntegerArgumentType.integer())
                                .executes(context -> exitWithCode(getInteger(context, "code"))))));
    }

    static int exitNormally() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return 0;
        }
        client.scheduleStop();
        return 1;
    }

    static int exitWithCode(int code) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            System.exit(code);
            return 1;
        }
        client.scheduleStop();
        Thread statusThread = new Thread(() -> {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30L);
            while (client.isRunning() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // Give Minecraft's normal close path a brief chance to finish
            // owned resources and shutdown callbacks before setting status.
            try {
                Thread.sleep(250L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            System.exit(code);
        }, "koil-requested-exit-status");
        statusThread.setDaemon(false);
        statusThread.start();
        return 1;
    }
}
