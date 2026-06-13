package com.spirit.koil.api.stats.global;

import java.util.Arrays;

public final class KoilMarketSeriesWindow {
    public static final String TODAY = "today";
    public static final String THREE_DAYS = "3d";
    public static final String SEVEN_DAYS = "7d";
    public static final String ALL = "all";
    private static final int MARKET_SAMPLE_INTERVAL_TICKS = 200;
    private static final int DAY_TICKS = 24000;
    private static final int SAMPLES_PER_DAY = DAY_TICKS / MARKET_SAMPLE_INTERVAL_TICKS;
    private static String activeKey = TODAY;

    private KoilMarketSeriesWindow() {
    }

    public static String defaultKey() {
        return TODAY;
    }

    public static String activeKey() {
        return normalize(activeKey);
    }

    public static void setActiveKey(String key) {
        activeKey = normalize(key);
    }

    public static String normalize(String key) {
        String clean = key == null ? "" : key.trim().toLowerCase();

        if (clean.equals("day") || clean.equals("daily") || clean.equals("today") || clean.equals("mc_day") || clean.equals("minecraft_day")) {
            return TODAY;
        }

        if (clean.equals("3") || clean.equals("3d") || clean.equals("three") || clean.equals("three_days") || clean.equals("3_mc_days")) {
            return THREE_DAYS;
        }

        if (clean.equals("7") || clean.equals("7d") || clean.equals("week") || clean.equals("seven_days") || clean.equals("7_mc_days")) {
            return SEVEN_DAYS;
        }

        if (clean.equals("all") || clean.equals("full") || clean.equals("history")) {
            return ALL;
        }

        return TODAY;
    }

    public static String label(String key) {
        return switch (normalize(key)) {
            case THREE_DAYS -> "3 Tick Days";
            case SEVEN_DAYS -> "7 Tick Days";
            case ALL -> "All";
            default -> "Tick Day";
        };
    }

    public static int sampleIntervalTicks() {
        return MARKET_SAMPLE_INTERVAL_TICKS;
    }

    public static int minecraftDayTicks() {
        return DAY_TICKS;
    }

    public static int samplesPerMinecraftDay() {
        return SAMPLES_PER_DAY;
    }

    public static int historySampleLimit() {
        return SAMPLES_PER_DAY * 7;
    }

    public static int daySampleIndex(long worldTime) {
        long safeTime = Math.max(0L, worldTime);
        int dayTick = (int) (safeTime % DAY_TICKS);
        return Math.max(1, Math.min(SAMPLES_PER_DAY, dayTick / MARKET_SAMPLE_INTERVAL_TICKS + 1));
    }

    public static int targetSamples(String key, long worldTime) {
        return switch (normalize(key)) {
            case THREE_DAYS -> SAMPLES_PER_DAY * 2 + daySampleIndex(worldTime);
            case SEVEN_DAYS -> SAMPLES_PER_DAY * 6 + daySampleIndex(worldTime);
            case ALL -> Integer.MAX_VALUE;
            default -> daySampleIndex(worldTime);
        };
    }

    public static Window filter(int[] source, String key, long worldTime) {
        if (source == null || source.length == 0) {
            return new Window(new int[0], 0, label(key), normalize(key), 0, 0, 0);
        }

        String normalized = normalize(key);
        int[] copy = Arrays.copyOf(source, source.length);

        if (normalized.equals(ALL)) {
            return new Window(copy, copy.length == 0 ? 0 : copy[0], label(normalized), normalized, 0, copy.length, Integer.MAX_VALUE);
        }

        int target = Math.max(1, targetSamples(normalized, worldTime));
        int start = Math.max(0, copy.length - target);
        int baselineIndex = Math.max(0, start - 1);
        int baseline = copy[baselineIndex];
        int length = Math.max(1, copy.length - start + 1);
        int[] out = new int[length];
        out[0] = 0;

        for (int i = start; i < copy.length; i++) {
            out[i - start + 1] = copy[i] - baseline;
        }

        return new Window(out, baseline, label(normalized), normalized, start, copy.length, target);
    }

    public static int[] points(int[] source, String key, long worldTime) {
        return filter(source, key, worldTime).points();
    }

    public record Window(int[] points, int baseline, String label, String key, int sourceStart, int sourceLength, int targetSamples) {
        public Window {
            points = points == null ? new int[0] : points.clone();
            label = label == null || label.isBlank() ? "Tick Day" : label;
            key = key == null || key.isBlank() ? TODAY : key;
            sourceStart = Math.max(0, sourceStart);
            sourceLength = Math.max(0, sourceLength);
            targetSamples = Math.max(0, targetSamples);
        }
    }
}
