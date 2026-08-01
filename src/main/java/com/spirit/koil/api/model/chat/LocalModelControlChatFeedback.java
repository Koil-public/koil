package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.command.CommandOutputPresentation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Structured local feedback for model setup and lifecycle commands. Its
 * indicator keeps these rows visible when the private-chat view rebuilds.
 */
public final class LocalModelControlChatFeedback {
    private static final String LOGGED_NAME = "Koil local model control";

    private LocalModelControlChatFeedback() {
    }

    public static void header(String value) {
        add(CommandOutputPresentation.text(value, CommandOutputPresentation.Tone.PRIMARY), Level.INFO);
    }

    public static void info(String value) {
        add(CommandOutputPresentation.text(value, CommandOutputPresentation.Tone.PRIMARY), Level.INFO);
    }

    public static void success(String value) {
        add(Text.literal(value).formatted(Formatting.GREEN), Level.SUCCESS);
    }

    public static void warning(String value) {
        add(CommandOutputPresentation.text(value, CommandOutputPresentation.Tone.LIMITED), Level.WARNING);
    }

    public static void uninstall(String value) {
        add(CommandOutputPresentation.text(value, CommandOutputPresentation.Tone.ERROR), Level.UNINSTALL);
    }

    public static void error(String value) {
        add(Text.literal(value).formatted(Formatting.RED), Level.ERROR);
    }

    public static void add(Text value, Level level) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.inGameHud == null || value == null) {
            return;
        }
        client.inGameHud.getChatHud().addMessage(value, null, indicator(level));
    }

    public static MutableText label(String label, String value, Formatting valueColor) {
        return Text.literal(label + ": ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(value == null ? "" : value)
                        .formatted(valueColor == null ? Formatting.WHITE : valueColor));
    }

    public static boolean isControlIndicator(MessageIndicator indicator) {
        return indicator != null && LOGGED_NAME.equals(indicator.loggedName());
    }

    private static MessageIndicator indicator(Level level) {
        CommandOutputPresentation.Tone tone = switch (level) {
            case SUCCESS -> CommandOutputPresentation.Tone.SUCCESS;
            case ERROR, UNINSTALL -> CommandOutputPresentation.Tone.ERROR;
            case INFO -> CommandOutputPresentation.Tone.PRIMARY;
            case WARNING -> CommandOutputPresentation.Tone.LIMITED;
        };
        return CommandOutputPresentation.commandIndicator("Local model command output", LOGGED_NAME, tone, false);
    }

    public enum Level {
        INFO,
        SUCCESS,
        WARNING,
        UNINSTALL,
        ERROR
    }
}
