package com.spirit.koil.api.console;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class ConsoleLogBridge {
    private ConsoleLogBridge() {
    }

    public static void publish(ConsoleChannel channel, ConsoleLevel level, String timestamp, String thread, String category, String message, String rawLine) {
        ConsoleRepository.getInstance().publish(channel, level, timestamp, thread, category, message, rawLine);
        if (channel == ConsoleChannel.CLI) {
            appendCliLog(level, timestamp, thread, category, message);
        }
    }

    private static void appendCliLog(ConsoleLevel level, String timestamp, String thread, String category, String message) {
        try {
            Path path = Path.of("koil/logs/cli/latest.log");
            Files.createDirectories(path.getParent());
            String suffix = category == null || category.isBlank() ? "" : "/" + category;
            String line = level.marker() + " | [" + timestamp + "] [" + thread + suffix + "]: " + message + System.lineSeparator();
            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
