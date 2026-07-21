package com.spirit.koil.api.chat;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/** Server-owned attention ping for players who may be filtering public chat. */
public final class KoilAttentionCommandBridge {
    private KoilAttentionCommandBridge() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var target = CommandManager.argument("target", EntityArgumentType.player())
                    .executes(context -> send(context.getSource(), EntityArgumentType.getPlayer(context, "target"), ""))
                    .then(CommandManager.argument("message", StringArgumentType.greedyString())
                            .executes(context -> send(context.getSource(), EntityArgumentType.getPlayer(context, "target"), StringArgumentType.getString(context, "message"))));
            dispatcher.register(CommandManager.literal("mention").then(target));
            dispatcher.register(CommandManager.literal("m").then(target));
        });
    }

    private static int send(ServerCommandSource source, ServerPlayerEntity target, String message) {
        String body = message == null || message.isBlank() ? "is trying to reach you" : message.trim();
        target.sendMessage(Text.literal("[Attention] <" + source.getName() + "> " + body), false);
        target.playSound(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), SoundCategory.PLAYERS, 1.0F, 1.25F);
        return 1;
    }
}
