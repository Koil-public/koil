package com.spirit.koil.api.console;

import java.util.ArrayList;
import java.util.List;

public final class ConsoleCommandHistory {
    private static final List<String> ENTRIES = new ArrayList<>();
    private static int cursor = -1;

    private ConsoleCommandHistory() {
    }

    public static synchronized void push(String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (!ENTRIES.isEmpty() && ENTRIES.get(ENTRIES.size() - 1).equals(trimmed)) {
            cursor = ENTRIES.size();
            return;
        }
        ENTRIES.add(trimmed);
        cursor = ENTRIES.size();
    }

    public static synchronized String previous() {
        if (ENTRIES.isEmpty()) {
            return null;
        }
        cursor = Math.max(0, cursor - 1);
        return ENTRIES.get(cursor);
    }

    public static synchronized String next() {
        if (ENTRIES.isEmpty()) {
            return "";
        }
        cursor = Math.min(ENTRIES.size(), cursor + 1);
        if (cursor >= ENTRIES.size()) {
            return "";
        }
        return ENTRIES.get(cursor);
    }

    public static synchronized List<String> snapshot() {
        return List.copyOf(ENTRIES);
    }
}
