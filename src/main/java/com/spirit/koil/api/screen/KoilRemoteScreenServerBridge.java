package com.spirit.koil.api.screen;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Collection;

/** Server-admin command bridge for registry-backed remote UI requests. */
public final class KoilRemoteScreenServerBridge {
    private KoilRemoteScreenServerBridge() {
    }

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var root = CommandManager.literal("screen").requires(source -> source.hasPermissionLevel(2));
            root.then(CommandManager.literal("close")
                    .then(CommandManager.argument("targets", EntityArgumentType.players())
                            .executes(context -> sendClose(context.getSource(), EntityArgumentType.getPlayers(context, "targets")))));
            root.then(CommandManager.literal("open")
                    .then(CommandManager.argument("targets", EntityArgumentType.players())
                            .then(CommandManager.argument("screen_and_data", StringArgumentType.greedyString())
                                    .executes(context -> sendOpenPayload(context.getSource(), EntityArgumentType.getPlayers(context, "targets"), StringArgumentType.getString(context, "screen_and_data"))))));
            dispatcher.register(root);
        });
    }

    private static int sendClose(net.minecraft.server.command.ServerCommandSource source, Collection<ServerPlayerEntity> targets) {
        return send(source, targets, true, "", "");
    }

    private static int sendOpen(net.minecraft.server.command.ServerCommandSource source, Collection<ServerPlayerEntity> targets, String id, String data) {
        return send(source, targets, false, id, data);
    }

    private static int sendOpenPayload(net.minecraft.server.command.ServerCommandSource source, Collection<ServerPlayerEntity> targets, String payload) {
        String value = payload == null ? "" : payload.trim();
        if (value.isBlank()) {
            source.sendError(Text.literal("Give /screen open a screen id."));
            return 0;
        }
        int split = value.indexOf(' ');
        String id = split < 0 ? value : value.substring(0, split);
        String data = split < 0 ? "" : value.substring(split + 1).stripLeading();
        return sendOpen(source, targets, id, data);
    }

    private static int send(net.minecraft.server.command.ServerCommandSource source, Collection<ServerPlayerEntity> targets, boolean close, String id, String data) {
        int sent = 0;
        for (ServerPlayerEntity target : targets) {
            if (!ServerPlayNetworking.canSend(target, KoilRemoteScreenNetwork.SCREEN_REQUEST_PACKET)) {
                continue;
            }
            PacketByteBuf buffer = PacketByteBufs.create();
            buffer.writeBoolean(close);
            buffer.writeString(id == null ? "" : id, 256);
            buffer.writeString(data == null ? "" : data, 4096);
            ServerPlayNetworking.send(target, KoilRemoteScreenNetwork.SCREEN_REQUEST_PACKET, buffer);
            sent++;
        }
        source.sendFeedback(() -> Text.literal("Screen request sent!"), false);
        return sent;
    }
}
