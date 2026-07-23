package com.spirit.koil.api.chat;

/** Placement edge used by registered chat-adjacent panels. */
public enum ChatHudPanelPlacement {
    /** Packed above the chat input area; visible chat rows are reserved above the stack. */
    BOTTOM,
    /** Packed immediately above the topmost visible message or the open chat viewport. */
    TOP
}
