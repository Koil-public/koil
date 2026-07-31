package com.spirit.koil.api.model;

import java.util.Locale;

/**
 * Shared model-storage presentation contract.
 *
 * <p>Values use binary thresholds while intentionally presenting the compact
 * lowercase units used by Koil's user-facing model surfaces.</p>
 */
public final class BinaryStorageFormatter {
    private static final long MEBIBYTE = 1024L * 1024L;
    private static final long GIBIBYTE = MEBIBYTE * 1024L;
    private static final long TEBIBYTE = GIBIBYTE * 1024L;

    private BinaryStorageFormatter() {
    }

    public static String format(long bytes) {
        if (bytes < 0L) {
            return "unknown";
        }
        if (bytes < MEBIBYTE) {
            return bytes + " b";
        }
        if (bytes < GIBIBYTE) {
            return decimal(bytes / (double) MEBIBYTE) + " mb";
        }
        if (bytes < TEBIBYTE) {
            return decimal(bytes / (double) GIBIBYTE) + " gb";
        }
        return decimal(bytes / (double) TEBIBYTE) + " tb";
    }

    public static String formatAvailable(long bytes) {
        return bytes <= 0L ? "unknown" : format(bytes);
    }

    private static String decimal(double value) {
        if (value >= 100.0D) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        if (value >= 10.0D) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
