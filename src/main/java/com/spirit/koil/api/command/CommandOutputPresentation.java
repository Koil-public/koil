package com.spirit.koil.api.command;

import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Shared command-feedback presentation. Ordinary command text is intentionally
 * limited to white, green, red, and dark gray; its separate message indicator
 * bar remains Koil orange. Command-block presentation stays outside this contract.
 */
public final class CommandOutputPresentation {
    public static final int WHITE = 0xFFFFFFFF;
    public static final int GREEN = 0xFF55FF55;
    public static final int RED = 0xFFFF5555;
    public static final int DARK_GRAY = 0xFF555555;
    public static final int COMMAND_BAR_ORANGE = 0xFFFFAA00;
    public static final int COMMAND_BAR_ORANGE_DIM = 0xFFA86F00;

    private CommandOutputPresentation() {
    }

    public static Formatting formatting(Tone tone) {
        return switch (tone == null ? Tone.PRIMARY : tone) {
            case PRIMARY -> Formatting.WHITE;
            case SUCCESS -> Formatting.GREEN;
            case ERROR -> Formatting.RED;
            case METADATA, LIMITED -> Formatting.DARK_GRAY;
        };
    }

    public static int rgb(Tone tone) {
        return switch (tone == null ? Tone.PRIMARY : tone) {
            case PRIMARY -> WHITE;
            case SUCCESS -> GREEN;
            case ERROR -> RED;
            case METADATA, LIMITED -> DARK_GRAY;
        };
    }

    public static MutableText text(String value, Tone tone) {
        return Text.literal(value == null ? "" : value).formatted(formatting(tone));
    }

    public static MutableText label(String label, String value, Tone valueTone) {
        return text((label == null ? "" : label) + ": ", Tone.METADATA)
                .append(text(value, valueTone));
    }

    /**
     * Re-bases an ordinary command row onto the restricted palette. Command
     * block rows intentionally do not call this method.
     */
    public static Text restyleRow(Text value, Tone tone) {
        if (value == null) {
            return Text.empty();
        }
        MutableText rewritten = MutableText.of(value.getContent())
                .setStyle(value.getStyle()
                        .withBold(false)
                        .withColor(formatting(tone)));
        for (Text sibling : value.getSiblings()) {
            rewritten.append(restyleRow(sibling, tone));
        }
        return rewritten;
    }

    public static MessageIndicator indicator(String tooltip, String loggedName, Tone tone) {
        return commandIndicator(tooltip, loggedName, tone, false);
    }

    public static MessageIndicator commandIndicator(String tooltip, String loggedName, Tone textTone, boolean dimmed) {
        return new MessageIndicator(
                (dimmed ? COMMAND_BAR_ORANGE_DIM : COMMAND_BAR_ORANGE) & 0x00FFFFFF,
                null,
                text(tooltip, textTone),
                loggedName == null ? "Command" : loggedName
        );
    }

    public enum Tone {
        PRIMARY,
        SUCCESS,
        ERROR,
        METADATA,
        LIMITED
    }
}
