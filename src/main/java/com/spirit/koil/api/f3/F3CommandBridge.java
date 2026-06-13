package com.spirit.koil.api.f3;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

@Environment(EnvType.CLIENT)
public final class F3CommandBridge {
    private F3CommandBridge() {
    }

    public static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(literal("stats")
                .executes(context -> {
                    F3Controller.setOverlayMode(F3Mode.NORMAL);
                    return 1;
                })
                .then(literal("compact").executes(context -> {
                    F3LayoutState.mode(F3Mode.COMPACT);
                    F3LayoutState.compactOverlay(true);
                    F3LayoutState.overlayVisible(true);
                    send("Compact F3 overlay enabled.");
                    return 1;
                }))
                .then(literal("target").executes(context -> {
                    F3Controller.setOverlayMode(F3Mode.INSPECTOR);
                    return 1;
                }))
                .then(literal("performance").executes(context -> {
                    F3Controller.setOverlayMode(F3Mode.PERFORMANCE);
                    return 1;
                }))
                .then(literal("graphs").executes(context -> {
                    F3Controller.setOverlayMode(F3Mode.GRAPHS);
                    return 1;
                }))
                .then(literal("world").executes(context -> {
                    F3Controller.setOverlayMode(F3Mode.WORLD);
                    return 1;
                }))
                .then(literal("freeze").executes(context -> {
                    F3LayoutState.toggleFrozen();
                    send(F3LayoutState.frozen() ? "Snapshot frozen." : "Snapshot live updates resumed.");
                    return 1;
                }))
                .then(literal("report").executes(context -> {
                    F3Controller.writeReport();
                    return 1;
                }))
                .then(literal("copy").then(literal("target").executes(context -> {
                    F3Controller.copyTarget();
                    return 1;
                })))
                .then(literal("mode").then(argument("mode", StringArgumentType.word()).executes(context -> {
                    F3Mode mode = F3Mode.fromCommand(getString(context, "mode"));
                    F3Controller.setOverlayMode(mode);
                    return 1;
                })))
                .then(literal("reset").then(literal("layout").executes(context -> {
                    F3LayoutState.mode(F3Mode.NORMAL);
                    F3LayoutState.compactOverlay(false);
                    F3LayoutState.resetOverlayScroll();
                    send("F3 layout reset to Normal.");
                    return 1;
                })))
        ));
    }

    private static void send(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal("[Debug]: ").formatted(Formatting.YELLOW, Formatting.BOLD).append(Text.literal(message).formatted(Formatting.WHITE)), false);
        }
    }
}
