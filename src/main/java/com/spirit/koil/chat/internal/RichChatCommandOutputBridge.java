package com.spirit.koil.chat.internal;

import net.minecraft.client.gui.hud.MessageIndicator;

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

    private RichChatCommandOutputBridge() {
    }

    public static synchronized void rememberOutgoingChatCommand(String command) {
        String normalized = normalizeCommand(command);
        if (normalized.isBlank() || isPrivateMessageCommand(normalized)) {
            return;
        }
        enqueue(RichChatRowType.COMMAND_OUTPUT, DEFAULT_PENDING_LINES);
    }

    public static synchronized void rememberOutgoingCommandBlockPacket(Object packet) {
        if (packet == null) {
            return;
        }
        String simpleName = packet.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (!simpleName.contains("commandblock")) {
            return;
        }
        enqueue(RichChatRowType.COMMAND_BLOCK_IMPULSE, DEFAULT_PENDING_LINES);
    }

    public static synchronized RichChatRowType pendingType(String firstLine, MessageIndicator indicator) {
        long now = System.currentTimeMillis();
        expire(now);
        if (firstLine == null || firstLine.isBlank() || indicator == null) {
            return null;
        }
        String metadata = indicatorMetadata(indicator);
        if (metadata.isBlank() || !looksLikeServerSourced(metadata)) {
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
