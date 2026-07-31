package com.spirit.koil.api.chat;

import com.spirit.koil.api.design.uiColorVal;
import com.spirit.koil.api.model.chat.ModelChatIdentity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.text.Text;

public final class ModelChatMessageBridge {
    private static final String LOGGED_NAME = "Koil local model";

    private ModelChatMessageBridge() {
    }

    public static MessageIndicator indicator() {
        return new MessageIndicator(
                uiColorVal.uiColorLocalModelMessageBar & 0x00FFFFFF,
                null,
                Text.literal("Local model response"),
                LOGGED_NAME
        );
    }

    public static boolean isModelIndicator(MessageIndicator indicator) {
        return indicator != null && LOGGED_NAME.equals(indicator.loggedName());
    }

    public static void addToChat(MinecraftClient client, String finalizedText) {
        if (client == null || client.inGameHud == null || finalizedText == null || finalizedText.isBlank()) {
            return;
        }
        String visibleText = ModelChatIdentity.decorate(finalizedText);
        RichChatMessageData message = RichMessageBuilder.create()
                .scope(RichChatScope.SYSTEM)
                .type(RichChatMessageType.MODEL_RESPONSE)
                .rawText(finalizedText)
                .fallbackText(visibleText)
                .segment(RichChatSegment.multilineText(visibleText))
                .metadata("source", "local_model")
                .metadata("visible_identity", ModelChatIdentity.LABEL)
                .build();
        RichChatMessageStore.remember(message);
        client.inGameHud.getChatHud().addMessage(Text.literal(visibleText), null, indicator());
    }
}
