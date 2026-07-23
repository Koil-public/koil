package com.spirit.koil.api.chat;

import net.minecraft.client.util.math.Rect2i;

public interface ChatSuggestionAnchor {
    boolean koil$useCustomChatSuggestionAnchor();

    int koil$chatSuggestionAnchorX(Rect2i vanillaArea, int popupWidth, boolean commandSuggestion);

    int koil$chatSuggestionAnchorY(int popupHeight);
}
