package com.spirit.koil.api.console;

public record ConsoleRecord(
        long sequence,
        ConsoleChannel channel,
        ConsoleLevel level,
        String timestamp,
        String thread,
        String category,
        String message,
        String rawLine
) {
}
