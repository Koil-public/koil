package com.spirit.koil.api.chat;

import java.util.List;

import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Executable contract for menu-chat draft classification. */
public final class MenuChatScreenProof {
    private MenuChatScreenProof() {
    }

    public static void main(String[] args) {
        require(MenuChatScreen.open(null, true) instanceof MenuChatScreen.OverlayChatScreen,
                "a loaded world must use the parent-overlay ChatScreen");
        require(MenuChatScreen.open(null, false) instanceof MenuChatScreen,
                "a menu without a world must use the offline fallback");
        require(MenuChatScreen.historySuggestions("he", List.of("hello", "help", "hello", "world"))
                        .equals(List.of("hello", "help")),
                "offline autocomplete must offer distinct recent matching drafts");
        require(MenuChatScreen.isOpeningChatCharacter('t') && MenuChatScreen.isOpeningChatCharacter('T'),
                "the opening key character must be consumed in either case");
        require(!MenuChatScreen.isOpeningChatCharacter('a'),
                "ordinary typed characters must not be consumed");
        TextFieldWidget focusedField = new TextFieldWidget(null, 0, 0, 1, 1, Text.empty());
        focusedField.setFocused(true);
        require(!MenuChatScreen.canOpenMenuChat(focusedField),
                "a focused text input must keep the T key instead of opening menu chat");
        require(MenuChatScreen.canOpenMenuChat((net.minecraft.client.gui.Element) null),
                "an unfocused screen must still allow menu chat");
        require(MenuChatScreen.outbound("  hello  ") instanceof MenuChatScreen.Message, "plain draft must be a message");
        require(MenuChatScreen.outbound(" /sprite heart_pop ") instanceof MenuChatScreen.Command, "slash draft must be a command");
        require(MenuChatScreen.outbound("  ") instanceof MenuChatScreen.Empty, "blank draft must be empty");
        require(MenuChatScreen.outbound("/") instanceof MenuChatScreen.Empty, "bare slash must be empty");
        System.out.println("Menu chat screen proof passed");
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new IllegalStateException(message);
        }
    }
}
