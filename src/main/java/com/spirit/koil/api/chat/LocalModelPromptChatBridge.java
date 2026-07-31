package com.spirit.koil.api.chat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Adds a model-bound user prompt to the local ChatHud with normal player-chat
 * presentation without transmitting it to the server.
 */
public final class LocalModelPromptChatBridge {
    private LocalModelPromptChatBridge() {
    }

    public static void addLocalPrompt(MinecraftClient client, String prompt) {
        if (client == null || client.player == null || client.inGameHud == null || prompt == null || prompt.isBlank()) {
            return;
        }
        String playerName = client.player.getGameProfile().getName();
        String normalized = prompt.replace("\r\n", "\n").replace('\r', '\n').strip();
        String prefix = "<" + playerName + "> ";
        String visible = prefix + LocalMultilineChatBridge.indentContinuationLines(normalized, prefix);
        RichChatMessageData message = RichMessageBuilder.create()
                .sender(client.player.getUuid(), playerName)
                .scope(RichChatScope.SYSTEM)
                .type(normalized.indexOf('\n') >= 0 ? RichChatMessageType.MULTILINE_TEXT : RichChatMessageType.TEXT)
                .rawText(normalized)
                .fallbackText(normalized)
                .segment(normalized.indexOf('\n') >= 0
                        ? RichChatSegment.multilineText(normalized)
                        : RichChatSegment.text(normalized))
                .metadata("source", "local_model_prompt")
                .metadata("local_only", "true")
                .build();
        RichChatMessageStore.remember(message);
        client.inGameHud.getChatHud().addMessage(Text.literal(visible));
    }
}
