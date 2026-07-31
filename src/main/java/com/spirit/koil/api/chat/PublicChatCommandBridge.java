package com.spirit.koil.api.chat;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Explicit public-chat escape path for modes that normally route typed chat
 * locally, such as private-message filtering and Automation Mode.
 */
public final class PublicChatCommandBridge {
    private static final int MAXIMUM_MESSAGE_LENGTH = 256;

    private PublicChatCommandBridge() {
    }

    public static void registerClientCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("chat")
                        .executes(context -> reject("Usage: /chat <public message>"))
                        .then(argument("message", StringArgumentType.greedyString())
                                .executes(context -> sendPublic(getString(context, "message"))))));
    }

    public static int sendPublic(String rawMessage) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.getNetworkHandler() == null) {
            return reject("Public chat is unavailable until a world or server connection is ready.");
        }
        String message = rawMessage == null ? "" : rawMessage.strip();
        if (message.isEmpty()) {
            return reject("Usage: /chat <public message>");
        }
        if (message.length() > MAXIMUM_MESSAGE_LENGTH) {
            return reject("Public chat messages may not exceed " + MAXIMUM_MESSAGE_LENGTH + " characters.");
        }
        for (int index = 0; index < message.length(); index++) {
            char character = message.charAt(index);
            if (character == '\n' || character == '\r' || character == '\0'
                    || Character.isISOControl(character)) {
                return reject("Public chat messages must be one visible line.");
            }
        }
        client.getNetworkHandler().sendChatMessage(message);
        return 1;
    }

    private static int reject(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(Text.literal(message).formatted(Formatting.RED));
        }
        return 0;
    }
}
