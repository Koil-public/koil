package com.spirit.koil.api.macro;

public record MacroAction(
        MacroActionType type,
        String text,
        int code,
        int durationTicks,
        double x,
        double y
) {
    public MacroAction {
        type = type == null ? MacroActionType.WAIT : type;
        text = text == null ? "" : text;
        durationTicks = Math.max(1, Math.min(1200, durationTicks));
        x = clamp(x);
        y = clamp(y);
    }

    public static MacroAction defaults(MacroActionType type) {
        MacroActionType safeType = type == null ? MacroActionType.WAIT : type;
        return switch (safeType) {
            case COMMAND -> new MacroAction(safeType, "/say Hello from Koil", -1, 1, 0.0D, 0.0D);
            case KEY -> new MacroAction(safeType, "", -1, 1, 0.0D, 0.0D);
            case MOUSE_MOVE -> new MacroAction(safeType, "", -1, 1, 10.0D, 0.0D);
            case MOUSE_BUTTON -> new MacroAction(safeType, "", 0, 1, 0.0D, 0.0D);
            case WAIT -> new MacroAction(safeType, "", -1, 10, 0.0D, 0.0D);
        };
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.max(-10000.0D, Math.min(10000.0D, value));
    }
}
