package com.spirit.koil.chat.internal;

public enum RichChatRowType {
    PLAYER_CHAT,
    PRIVATE_MESSAGE,
    PLAYER_ACTIVITY,
    ADVANCEMENT_TASK,
    ADVANCEMENT_GOAL,
    ADVANCEMENT_CHALLENGE,
    COMMAND_OUTPUT,
    COMMAND_BLOCK_IMPULSE,
    COMMAND_BLOCK_CHAIN,
    COMMAND_BLOCK_REPEATING,
    UNKNOWN;

    public boolean usesBodyIndent() {
        return this == PLAYER_CHAT || this == PRIVATE_MESSAGE;
    }
}
