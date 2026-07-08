package com.spirit.koil.chat.internal;

public interface ChatHudRefreshBridge {
    void koil$refreshPrivateMessageView();

    boolean koil$chatScrollbarContains(double mouseX, double mouseY);

    void koil$beginChatScrollbarDrag(double mouseY);

    void koil$dragChatScrollbar(double mouseY);

    void koil$endChatScrollbarDrag();
}
