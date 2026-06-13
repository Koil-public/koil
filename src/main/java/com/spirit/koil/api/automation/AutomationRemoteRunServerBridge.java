package com.spirit.koil.api.automation;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class AutomationRemoteRunServerBridge {
    private AutomationRemoteRunServerBridge() {
    }

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(CommandManager.literal("run")
                .then(CommandManager.argument("input", StringArgumentType.greedyString())
                        .executes(context -> {
                            String input = StringArgumentType.getString(context, "input");
                            Entity entity = context.getSource().getEntity();
                            ServerPlayerEntity target = entity instanceof ServerPlayerEntity player ? player : context.getSource().getPlayerOrThrow();
                            PacketByteBuf buffer = PacketByteBufs.create();
                            buffer.writeString(input, 32767);
                            buffer.writeString(target.getName().getString(), 256);
                            ServerPlayNetworking.send(target, AutomationRemoteRunNetwork.RUN_AS_PACKET, buffer);
                            context.getSource().sendFeedback(() -> Text.literal("Koil automation sent to " + target.getName().getString()), false);
                            return 1;
                        }))
        ));
    }
}
