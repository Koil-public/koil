package com.spirit.koil.api.console;

public final class ConsoleLogBridge {
    private ConsoleLogBridge() {
    }

    public static void publish(ConsoleChannel channel, ConsoleLevel level, String timestamp, String thread, String category, String message, String rawLine) {
        ConsoleRepository.getInstance().publish(channel, level, timestamp, thread, category, message, rawLine);
    }
}
