package com.spirit.koil.api.performance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compares performance setting identifiers without guessing from arbitrary
 * substrings. Provider/file prefixes may be omitted, but every remaining path
 * segment must match exactly.
 */
public final class PerformanceSettingKeyMatcher {
    private PerformanceSettingKeyMatcher() {
    }

    public static boolean matches(String first, String second) {
        List<String> firstPath = path(first);
        List<String> secondPath = path(second);
        if (firstPath.isEmpty() || secondPath.isEmpty()) {
            return false;
        }
        return endsWith(firstPath, secondPath) || endsWith(secondPath, firstPath);
    }

    public static String canonical(String value) {
        return String.join("/", path(value));
    }

    private static boolean endsWith(List<String> full, List<String> suffix) {
        if (suffix.size() > full.size()) {
            return false;
        }
        int offset = full.size() - suffix.size();
        for (int index = 0; index < suffix.size(); index++) {
            if (!full.get(offset + index).equals(suffix.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> path(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String normalized = value.toLowerCase(Locale.ROOT)
                .replace('\\', '/')
                .replace("::", "/");
        List<String> segments = new ArrayList<>();
        for (String rawSegment : normalized.split("[./]+")) {
            String segment = rawSegment.replaceAll("[^a-z0-9]+", "");
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        return List.copyOf(segments);
    }
}
