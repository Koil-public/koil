package com.spirit.koil.api.chat.input;

import com.mojang.brigadier.suggestion.Suggestions;

import java.util.concurrent.CompletableFuture;

/**
 * Reads Brigadier completion results only after they are ready. Player-name
 * and server-backed providers may complete asynchronously and must never make
 * a render/input path wait.
 */
public final class CommandSuggestionFuturePoller {
    private CommandSuggestionFuturePoller() {
    }

    public static Suggestions readyOrNull(CompletableFuture<Suggestions> future) {
        if (future == null || !future.isDone()) {
            return null;
        }
        try {
            return future.getNow(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
