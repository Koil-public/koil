package com.spirit.koil.api.console;

import java.awt.Color;

public final class ConsoleTheme {
    private static final int BACKGROUND = rgb(18, 20, 24);
    private static final int BORDER = rgb(68, 74, 84);
    private static final int HEADER = rgb(27, 31, 38);
    private static final int HEADER_STRIPE = rgb(128, 42, 42);
    private static final int PRIMARY_TEXT = rgb(232, 236, 242);
    private static final int SECONDARY_TEXT = rgb(156, 164, 178);

    private ConsoleTheme() {
    }

    public static int background() {
        return BACKGROUND;
    }

    public static int border() {
        return BORDER;
    }

    public static int header() {
        return HEADER;
    }

    public static int headerStripe() {
        return HEADER_STRIPE;
    }

    public static int primaryText() {
        return PRIMARY_TEXT;
    }

    public static int secondaryText() {
        return SECONDARY_TEXT;
    }

    public static int levelColor(ConsoleLevel level) {
        return switch (level) {
            case PLAIN -> rgb(122, 130, 145);
            case INFO -> rgb(210, 184, 44);
            case WARN -> rgb(208, 116, 40);
            case ERROR -> rgb(214, 58, 72);
            case FATAL -> rgb(150, 28, 42);
            case DEBUG -> rgb(86, 188, 104);
            case UPDATE -> rgb(64, 156, 214);
            case OTHER -> rgb(196, 201, 208);
        };
    }

    public static Color awt(int rgba) {
        return new Color(rgba, true);
    }

    private static int rgb(int red, int green, int blue) {
        return new Color(red, green, blue, 255).getRGB();
    }
}
