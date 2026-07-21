package com.spirit.koil.chat.internal;

import com.spirit.koil.chat.internal.input.KoilCommandAnalysisService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

public final class RichChatCommandOutputBridge {
    private static final long PENDING_WINDOW_MS = 2500L;
    private static final long RECENT_MATCH_WINDOW_MS = 1200L;
    private static final int DEFAULT_PENDING_LINES = 12;

    private static final Deque<PendingOutput> PENDING = new ArrayDeque<>();
    private static String recentMatchedLine = "";
    private static String recentMatchedMetadata = "";
    private static RichChatRowType recentMatchedType;
    private static long recentMatchedUntil;
    private static String lastOutgoingCommand = "";
    private static long lastOutgoingCommandAt;

    private RichChatCommandOutputBridge() {
    }

    public static synchronized void rememberOutgoingChatCommand(String command) {
        String normalized = normalizeCommand(command);
        if (normalized.isBlank() || isPrivateMessageCommand(normalized)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (normalized.equals(lastOutgoingCommand) && now - lastOutgoingCommandAt <= 250L) {
            return;
        }
        lastOutgoingCommand = normalized;
        lastOutgoingCommandAt = now;
        enqueue(RichChatRowType.COMMAND_OUTPUT, DEFAULT_PENDING_LINES);
    }

    /** Replaces Minecraft's terse syntax row with the exact failed token,
     * parser reason, cursor column, and valid completions while preserving the
     * original server response for context. */
    public static synchronized Text enhanceSyntaxFailure(Text message) {
        if (message == null || lastOutgoingCommand.isBlank() || System.currentTimeMillis() - lastOutgoingCommandAt > PENDING_WINDOW_MS) {
            return message;
        }
        String original = message.getString();
        if (!looksLikeSyntaxFailure(original)) {
            return message;
        }
        KoilCommandAnalysisService.Analysis analysis = KoilCommandAnalysisService.analyzeDraft(
                MinecraftClient.getInstance(),
                "/" + lastOutgoingCommand,
                false,
                true
        );
        if (analysis == null || analysis.status() == null) {
            return message;
        }
        MutableText enhanced = Text.literal("Command failed: ").formatted(Formatting.RED)
                .append(Text.literal(analysis.status().text()).formatted(Formatting.WHITE));
        enhanced.append(Text.literal("\nCommand: /" + lastOutgoingCommand).formatted(Formatting.GRAY));
        for (String detail : analysis.details()) {
            if (detail != null && !detail.isBlank()) {
                enhanced.append(Text.literal("\n- " + detail).formatted(Formatting.GRAY));
            }
        }
        if (!original.isBlank()) {
            enhanced.append(Text.literal("\nServer: " + original.replace('\n', ' ').replace('\r', ' ').trim()).formatted(Formatting.DARK_GRAY));
        }
        return enhanced;
    }

    public static synchronized void rememberOutgoingCommandBlockPacket(Object packet) {
        if (packet == null) {
            return;
        }
        String simpleName = packet.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        // In production the packet's JVM name is intermediary, so the caller
        // also supplies a direct instanceof signal. Keep this fallback for
        // development and third-party compatible packet wrappers.
        if (!simpleName.contains("commandblock")) {
            return;
        }
        // Saving a command block produces one feedback line. Reserving a broad
        // command-output window lets one repeating save recolor later blocks.
        enqueue(RichChatRowType.COMMAND_BLOCK_IMPULSE, 1);
    }

    /** Direct packet-type hook used in production, where class names are remapped. */
    public static synchronized void rememberOutgoingCommandBlockUpdate(Object packet) {
        enqueue(commandBlockType(packet), 1);
    }

    /** Uses the packet's public mode getter when the network hook has it. */
    public static synchronized void rememberOutgoingCommandBlockMode(String mode) {
        // Minecraft's command-block enum uses the storage names SEQUENCE and
        // AUTO, not the UI labels Chain and Repeating.
        if ("CHAIN".equalsIgnoreCase(mode) || "SEQUENCE".equalsIgnoreCase(mode)) {
            enqueue(RichChatRowType.COMMAND_BLOCK_CHAIN, 1);
        } else if ("REPEATING".equalsIgnoreCase(mode) || "AUTO".equalsIgnoreCase(mode)) {
            enqueue(RichChatRowType.COMMAND_BLOCK_REPEATING, 1);
        } else {
            enqueue(RichChatRowType.COMMAND_BLOCK_IMPULSE, 1);
        }
    }

    public static synchronized RichChatRowType pendingType(String firstLine, MessageIndicator indicator) {
        long now = System.currentTimeMillis();
        expire(now);
        if (firstLine == null || firstLine.isBlank()) {
            return null;
        }
        String metadata = indicatorMetadata(indicator);
        // Vanilla does not consistently attach an indicator to command feedback. A
        // pending entry is created only by a local non-chat command or a command
        // block update, so the short empty-metadata path is safe and prevents the
        // generic "Server message" indicator from swallowing real command output.
        if (!metadata.isBlank() && !looksLikeServerSourced(metadata)) {
            return null;
        }
        if (recentMatchedType != null
                && now <= recentMatchedUntil
                && firstLine.equals(recentMatchedLine)
                && metadata.equals(recentMatchedMetadata)) {
            return recentMatchedType;
        }
        while (!PENDING.isEmpty()) {
            PendingOutput pending = PENDING.peekFirst();
            if (pending == null || pending.expiresAt() < now || pending.remainingLines() <= 0) {
                PENDING.pollFirst();
                continue;
            }
            PendingOutput consumed = new PendingOutput(pending.type(), pending.remainingLines() - 1, pending.expiresAt());
            PENDING.pollFirst();
            if (consumed.remainingLines() > 0) {
                PENDING.addFirst(consumed);
            }
            recentMatchedLine = firstLine;
            recentMatchedMetadata = metadata;
            recentMatchedType = pending.type();
            recentMatchedUntil = now + RECENT_MATCH_WINDOW_MS;
            return pending.type();
        }
        return null;
    }

    private static void enqueue(RichChatRowType type, int lines) {
        long now = System.currentTimeMillis();
        expire(now);
        PENDING.addLast(new PendingOutput(type, Math.max(1, lines), now + PENDING_WINDOW_MS));
    }

    private static RichChatRowType commandBlockType(Object packet) {
        if (packet == null) {
            return RichChatRowType.COMMAND_BLOCK_IMPULSE;
        }
        // UpdateCommandBlockC2SPacket carries its mode as a private enum. Reading
        // the enum value keeps the public bridge mapping-resilient across named
        // and intermediary runtimes without guessing from the screen that sent it.
        for (java.lang.reflect.Field field : packet.getClass().getDeclaredFields()) {
            try {
                if (!field.trySetAccessible()) {
                    continue;
                }
                Object value = field.get(packet);
                String name = value instanceof Enum<?> mode ? mode.name() : "";
                if ("CHAIN".equalsIgnoreCase(name) || "SEQUENCE".equalsIgnoreCase(name)) {
                    return RichChatRowType.COMMAND_BLOCK_CHAIN;
                }
                if ("REPEATING".equalsIgnoreCase(name) || "AUTO".equalsIgnoreCase(name)) {
                    return RichChatRowType.COMMAND_BLOCK_REPEATING;
                }
            } catch (Throwable ignored) {
                // The default impulse color remains a safe fallback.
            }
        }
        return RichChatRowType.COMMAND_BLOCK_IMPULSE;
    }

    private static void expire(long now) {
        while (!PENDING.isEmpty()) {
            PendingOutput pending = PENDING.peekFirst();
            if (pending == null || pending.expiresAt() < now || pending.remainingLines() <= 0) {
                PENDING.pollFirst();
            } else {
                break;
            }
        }
        if (recentMatchedUntil < now) {
            recentMatchedLine = "";
            recentMatchedMetadata = "";
            recentMatchedType = null;
            recentMatchedUntil = 0L;
        }
    }

    private static boolean looksLikeServerSourced(String metadata) {
        return metadata.contains("server")
                || metadata.contains("system")
                || metadata.contains("command")
                || metadata.contains("message");
    }

    private static boolean looksLikeSyntaxFailure(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.contains("unknown or incomplete command")
                || lower.contains("unknown command")
                || lower.contains("incorrect argument")
                || lower.contains("expected ")
                || lower.contains("syntax error")
                || lower.contains("error at position")
                || lower.contains("could not parse");
    }

    private static boolean isPrivateMessageCommand(String command) {
        String lower = command.toLowerCase(Locale.ROOT);
        return lower.startsWith("msg ")
                || lower.startsWith("tell ")
                || lower.startsWith("w ")
                || lower.matches("^execute\\s+as\\s+\\S+\\s+run\\s+(msg|tell|w)\\s+.*$");
    }

    private static String normalizeCommand(String command) {
        if (command == null) {
            return "";
        }
        String normalized = command.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized;
    }

    private static String indicatorMetadata(MessageIndicator indicator) {
        if (indicator == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        String loggedName = indicator.loggedName();
        if (loggedName != null) {
            builder.append(loggedName.toLowerCase(Locale.ROOT)).append(' ');
        }
        if (indicator.text() != null) {
            builder.append(indicator.text().getString().toLowerCase(Locale.ROOT));
        }
        return builder.toString().trim();
    }

    private record PendingOutput(RichChatRowType type, int remainingLines, long expiresAt) {
    }
}
