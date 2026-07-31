package com.spirit.koil.api.chat;

public enum RichChatRowType {
    PLAYER_CHAT,
    PRIVATE_MESSAGE,
    PLAYER_ACTIVITY,
    ADVANCEMENT_TASK,
    ADVANCEMENT_GOAL,
    ADVANCEMENT_CHALLENGE,
    COMMAND_OUTPUT,
    COMMAND_FAILURE,
    COMMAND_BLOCK_IMPULSE,
    COMMAND_BLOCK_CHAIN,
    COMMAND_BLOCK_REPEATING,
    ATTENTION,
    MODEL_RESPONSE,
    UNKNOWN;

    public boolean usesBodyIndent() {
        return this == PLAYER_CHAT || this == PRIVATE_MESSAGE || this == MODEL_RESPONSE;
    }

    public boolean usesStructuralSpacing() {
        return usesBodyIndent();
    }
}
