package com.spirit.koil.api.util.console.log;

import com.spirit.koil.api.console.ConsoleLevel;

/** Single-file entry point for Koil subsystem logging. */
public final class KoilLog {
    public static final String MAIN_PATH = "koil/logs/latest.log";
    public static final String AUTOMATION_THREAD = "Automation Thread";
    public static final String PACKAGING_THREAD = "Packaging Thread";
    public static final String BRIDGE_THREAD = "Bridge Thread";
    public static final String EXTERNAL_WINDOW_THREAD = "External Window Thread";

    private KoilLog() {
    }

    public static void write(String thread, ConsoleLevel level, String category, String message) {
        SubFileLogger logger = mainLogger();
        String cleanCategory = clean(category, "event");
        String cleanMessage = clean(message, "");
        String detail = "[" + cleanCategory + "]" + (cleanMessage.isBlank() ? "" : " " + cleanMessage);
        ConsoleLevel safeLevel = level == null ? ConsoleLevel.PLAIN : level;
        switch (safeLevel) {
            case INFO -> logger.logI(thread, detail);
            case WARN -> logger.logW(thread, detail);
            case ERROR -> logger.logE(thread, detail);
            case FATAL -> logger.logF(thread, detail);
            case DEBUG -> logger.logD(thread, detail);
            case UPDATE -> logger.logU(thread, detail);
            case OTHER -> logger.logO(thread, detail);
            default -> logger.log(thread, detail);
        }
    }

    public static void info(String thread, String category, String message) {
        write(thread, ConsoleLevel.INFO, category, message);
    }

    public static void warning(String thread, String category, String message) {
        write(thread, ConsoleLevel.WARN, category, message);
    }

    public static void error(String thread, String category, String message) {
        write(thread, ConsoleLevel.ERROR, category, message);
    }

    private static SubFileLogger mainLogger() {
        try {
            return SubFileLogger.getInstance("mainLogger");
        } catch (IllegalStateException missing) {
            SubFileLogger.initialize("mainLogger", "koil/logs", "main");
            return SubFileLogger.getInstance("mainLogger");
        }
    }

    private static String clean(String value, String fallback) {
        String clean = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').strip();
        return clean.isBlank() ? fallback : clean;
    }
}
