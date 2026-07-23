package com.spirit.koil.api.chat;

import com.spirit.koil.api.chat.input.KoilCommandAnalysisService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.List;

/** Applies the same Brigadier palette used by Koil's live command editor to
 * command feedback which includes a saved or echoed command. */
public final class RichChatCommandFeedbackFormatter {
    private RichChatCommandFeedbackFormatter() {
    }

    public static boolean hasHighlightableCommand(String text) {
        return text != null && (text.indexOf('/') >= 0 || commandEchoStart(text) >= 0);
    }

    public static int render(DrawContext context, TextRenderer renderer, String text, int x, int y, int fallbackColor) {
        if (context == null || renderer == null || text == null) {
            return x;
        }
        int slash = text.indexOf('/');
        int commandStart = slash >= 0 ? slash : commandEchoStart(text);
        if (commandStart < 0) {
            return context.drawTextWithShadow(renderer, Text.literal(text), x, y, fallbackColor);
        }
        int cursor = context.drawTextWithShadow(renderer, Text.literal(text.substring(0, commandStart)), x, y, fallbackColor);
        String command = text.substring(commandStart);
        boolean syntheticSlash = !command.startsWith("/");
        if (syntheticSlash) command = "/" + command;
        List<KoilCommandAnalysisService.StyledChunk> chunks = KoilCommandAnalysisService.highlightLine(MinecraftClient.getInstance(), command);
        for (KoilCommandAnalysisService.StyledChunk chunk : chunks) {
            if (chunk == null || chunk.text() == null || chunk.text().isEmpty()) {
                continue;
            }
            String chunkText = chunk.text();
            if (syntheticSlash && chunkText.startsWith("/")) {
                chunkText = chunkText.substring(1);
            }
            if (chunkText.isEmpty()) continue;
            int alpha = fallbackColor & 0xFF000000;
            int color = alpha | (chunk.color() & 0x00FFFFFF);
            cursor = context.drawTextWithShadow(renderer, Text.literal(chunkText).setStyle(chunk.style()), cursor, y, color);
        }
        return cursor;
    }

    /** Applies command colors before ChatHud performs native line wrapping, so
     * continuation rows keep the full echoed-command context even without a
     * repeated slash. */
    public static Text styleBeforeNativeWrap(Text message) {
        return styleBeforeNativeWrap(message, false);
    }

    public static Text styleBeforeNativeWrap(Text message, boolean assumeWholeMessageIsCommand) {
        if (message == null) {
            return null;
        }
        String text = message.getString();
        int slash = text.indexOf('/');
        int commandStart = slash >= 0 ? slash : commandEchoStart(text);
        if (commandStart < 0 && assumeWholeMessageIsCommand) {
            commandStart = 0;
        }
        if (commandStart < 0) {
            return message;
        }
        MutableText styled = Text.literal(text.substring(0, commandStart));
        String command = text.substring(commandStart);
        boolean syntheticSlash = !command.startsWith("/");
        if (syntheticSlash) {
            command = "/" + command;
        }
        for (KoilCommandAnalysisService.StyledChunk chunk : KoilCommandAnalysisService.highlightLine(MinecraftClient.getInstance(), command)) {
            if (chunk == null || chunk.text() == null || chunk.text().isEmpty()) {
                continue;
            }
            String piece = chunk.text();
            if (syntheticSlash && piece.startsWith("/")) {
                piece = piece.substring(1);
            }
            if (piece.isEmpty()) {
                continue;
            }
            Style style = (chunk.style() == null ? Style.EMPTY : chunk.style()).withColor(chunk.color() & 0x00FFFFFF);
            styled.append(Text.literal(piece).setStyle(style));
        }
        return styled;
    }

    private static int commandEchoStart(String text) {
        if (text == null) return -1;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String prefix : List.of("set command block command to: ", "command block command: ", "command: ")) {
            int at = lower.indexOf(prefix);
            if (at >= 0) return at + prefix.length();
        }
        int colon = text.lastIndexOf(": ");
        if (lower.contains("command") && colon >= 0 && colon + 2 < text.length()) {
            return colon + 2;
        }
        return -1;
    }
}
