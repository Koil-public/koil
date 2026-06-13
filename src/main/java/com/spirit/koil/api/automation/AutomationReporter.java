package com.spirit.koil.api.automation;

import com.spirit.koil.api.automation.cli.AutomationCliViewModel;
import com.spirit.koil.api.console.ConsoleChannel;
import com.spirit.koil.api.console.ConsoleLevel;
import com.spirit.koil.api.console.ConsoleLogBridge;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class AutomationReporter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private AutomationReporter() {
    }

    public static void row(ConsoleLevel level, String stage, String message) {
        AutomationCliViewModel.appendFromReporter(level, stage, message);
        ConsoleLogBridge.publish(ConsoleChannel.CLI, level, LocalTime.now().format(TIME), "CLI", stage, message, message);
    }

    public static void pipeline(String stage, String message) {
        row(ConsoleLevel.INFO, stage, message);
    }

    public static void cache(String stage, String message) {
        row(ConsoleLevel.UPDATE, stage, message);
    }

    public static void ok(String stage, String message) {
        row(ConsoleLevel.UPDATE, stage, message);
    }

    public static void run(String stage, String message) {
        row(ConsoleLevel.INFO, stage, message);
    }

    public static void info(String stage, String message) {
        row(ConsoleLevel.PLAIN, stage, message);
    }

    public static void bind(String key, Object value) {
        row(ConsoleLevel.UPDATE, "[bind]", key + " = " + value);
    }

    public static void mem(String key, Object value) {
        row(ConsoleLevel.DEBUG, "[mem ]", key + " = " + value);
    }

    public static void debug(String stage, String message) {
        row(ConsoleLevel.DEBUG, stage, message);
    }

    public static void block(String stage, String message) {
        row(ConsoleLevel.WARN, stage, message);
    }

    public static void fail(String stage, String message) {
        row(ConsoleLevel.ERROR, stage, message);
    }

    public static void done(String stage, String message) {
        row(ConsoleLevel.UPDATE, stage, message);
    }
}
