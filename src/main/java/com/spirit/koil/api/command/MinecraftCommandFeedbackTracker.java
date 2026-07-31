package com.spirit.koil.api.command;

import com.spirit.koil.api.chat.RichChatRowType;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bounded evidence windows for commands submitted by model tools.
 *
 * <p>The tracker uses Rich Chat's already-classified command rows. A command
 * failure row is authoritative rejection evidence; a normal command-output
 * row is positive server feedback. No row remains an honest submission-only
 * result.</p>
 */
public final class MinecraftCommandFeedbackTracker {
    private static final int MAXIMUM_ACTIVE_WINDOWS = 4;
    private static final int MAXIMUM_ROWS = 8;
    private static final int MAXIMUM_ROW_LENGTH = 512;
    private static final Map<UUID, Window> WINDOWS = new LinkedHashMap<>();
    private static final Map<UUID, Result> COMPLETED = new LinkedHashMap<>();

    private MinecraftCommandFeedbackTracker() {
    }

    public static synchronized void begin(UUID requestId, String command) {
        if (requestId == null) {
            return;
        }
        COMPLETED.remove(requestId);
        while (WINDOWS.size() >= MAXIMUM_ACTIVE_WINDOWS) {
            UUID first = WINDOWS.keySet().iterator().next();
            Window evicted = WINDOWS.remove(first);
            if (evicted != null) {
                evicted.result.complete(new Result("none", List.of()));
            }
        }
        WINDOWS.put(requestId, new Window());
    }

    public static synchronized void observe(Text message, RichChatRowType rowType) {
        if (message == null || rowType == null
                || (rowType != RichChatRowType.COMMAND_OUTPUT
                && rowType != RichChatRowType.COMMAND_FAILURE)) {
            return;
        }
        String text = message.getString().replace('\r', ' ').replace('\n', ' ').strip();
        if (text.isBlank()) {
            return;
        }
        if (text.length() > MAXIMUM_ROW_LENGTH) {
            text = text.substring(0, MAXIMUM_ROW_LENGTH);
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        boolean failure = rowType == RichChatRowType.COMMAND_FAILURE
                || lower.startsWith("unknown command")
                || lower.startsWith("unknown or incomplete command")
                || lower.startsWith("incorrect argument")
                || lower.startsWith("command failed")
                || lower.contains("you do not have permission")
                || lower.contains("requires player")
                || lower.contains("could not parse");
        boolean positive = !failure && looksLikePositiveCommandFeedback(lower);
        List<UUID> conclusive = new ArrayList<>();
        for (Map.Entry<UUID, Window> entry : WINDOWS.entrySet()) {
            Window window = entry.getValue();
            if (window.rows.size() < MAXIMUM_ROWS) {
                window.rows.add(new Feedback(
                        failure ? "failure" : positive ? "output" : "observed",
                        text
                ));
            }
            if (failure || positive) {
                conclusive.add(entry.getKey());
            }
        }
        for (UUID requestId : conclusive) {
            Window window = WINDOWS.remove(requestId);
            if (window != null) {
                Result result = result(window);
                rememberCompleted(requestId, result);
                window.result.complete(result);
            }
        }
    }

    public static synchronized Result finish(UUID requestId) {
        Window window = requestId == null ? null : WINDOWS.remove(requestId);
        if (window == null) {
            Result completed = requestId == null ? null : COMPLETED.remove(requestId);
            return completed == null ? new Result("none", List.of()) : completed;
        }
        Result result = result(window);
        rememberCompleted(requestId, result);
        window.result.complete(result);
        return result;
    }

    public static synchronized CompletableFuture<Result> await(UUID requestId, long timeoutMillis) {
        Window window = requestId == null ? null : WINDOWS.get(requestId);
        if (window == null) {
            Result completed = requestId == null ? null : COMPLETED.get(requestId);
            return CompletableFuture.completedFuture(
                    completed == null ? new Result("none", List.of()) : completed
            );
        }
        CompletableFuture.delayedExecutor(
                Math.max(1L, timeoutMillis),
                TimeUnit.MILLISECONDS
        ).execute(() -> {
            Result result = finish(requestId);
            window.result.complete(result);
        });
        return window.result;
    }

    private static void rememberCompleted(UUID requestId, Result result) {
        if (requestId == null || result == null) {
            return;
        }
        COMPLETED.put(requestId, result);
        while (COMPLETED.size() > 16) {
            COMPLETED.remove(COMPLETED.keySet().iterator().next());
        }
    }

    private static Result result(Window window) {
        List<Feedback> rows = List.copyOf(window.rows);
        String assessment = rows.stream().anyMatch(row -> "failure".equals(row.type()))
                ? "failed"
                : rows.stream().anyMatch(row -> "output".equals(row.type()))
                ? "succeeded"
                : rows.isEmpty() ? "none" : "observed";
        return new Result(assessment, rows);
    }

    private static boolean looksLikePositiveCommandFeedback(String lower) {
        return lower.startsWith("gave ")
                || lower.startsWith("cleared ")
                || lower.startsWith("summoned ")
                || lower.startsWith("teleported ")
                || lower.startsWith("killed ")
                || lower.startsWith("time set")
                || lower.startsWith("weather set")
                || lower.startsWith("difficulty set")
                || lower.startsWith("set own game mode")
                || lower.startsWith("set the time")
                || lower.startsWith("the time is")
                || lower.startsWith("current time")
                || lower.startsWith("game rule ")
                || lower.startsWith("reloaded")
                || lower.startsWith("reload complete")
                || lower.startsWith("applied effect")
                || lower.startsWith("removed effect");
    }

    private static final class Window {
        private final List<Feedback> rows = new ArrayList<>();
        private final CompletableFuture<Result> result = new CompletableFuture<>();
    }

    public record Feedback(String type, String text) {
    }

    public record Result(String assessment, List<Feedback> feedback) {
        public Result {
            assessment = assessment == null ? "none" : assessment;
            feedback = feedback == null ? List.of() : List.copyOf(feedback);
        }
    }
}
