package com.spirit.koil.api.macro;

public enum MacroActionType {
    COMMAND("Command"),
    KEY("Key Press"),
    MOUSE_MOVE("Mouse Look"),
    MOUSE_BUTTON("Mouse Button"),
    WAIT("Wait");

    private final String displayName;

    MacroActionType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return this.displayName;
    }

    public MacroActionType next() {
        MacroActionType[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }
}
