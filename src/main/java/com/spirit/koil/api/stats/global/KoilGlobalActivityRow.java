package com.spirit.koil.api.stats.global;

import java.util.*;

public final class KoilGlobalActivityRow {
    private final String type;
    private final String id;
    private final String metric;
    private final String title;
    private final int value;
    private final String leader;
    private final int leaderValue;
    private final int[] points;
    private final String source;
    private final int base;
    private final int change;
    private final int volume;
    private final int[] candleOpen;
    private final int[] candleHigh;
    private final int[] candleLow;
    private final int[] candleClose;
    private final int[] movingAverage;
    private final int trend;
    private final int volatility;
    private final String contextKey;
    private final int confidence;
    private final boolean authoritative;

    public KoilGlobalActivityRow(String type, String id, String metric, String title, int value, String leader, int leaderValue, int[] points, String source, int base, int change, int volume) {
        this(type, id, metric, title, value, leader, leaderValue, points, source, base, change, volume, "server", 100, true);
    }

    public KoilGlobalActivityRow(String type, String id, String metric, String title, int value, String leader, int leaderValue, int[] points, String source, int base, int change, int volume, String contextKey, int confidence, boolean authoritative) {
        this.type = safe(type, "activity");
        this.id = safe(id, metric);
        this.metric = safe(metric, id);
        this.title = safe(title, this.metric);
        this.value = Math.max(0, value);
        this.leader = safe(leader, "none");
        this.leaderValue = Math.max(0, leaderValue);
        this.points = points == null ? new int[0] : Arrays.copyOf(points, points.length);
        this.source = safe(source, "server");
        this.base = Math.max(0, base);
        this.change = change;
        this.volume = Math.max(0, volume);
        this.contextKey = safe(contextKey, "unknown");
        this.confidence = Math.max(0, Math.min(100, confidence));
        this.authoritative = authoritative;
        int[][] candles = candlesFrom(this.points);
        this.candleOpen = candles[0];
        this.candleHigh = candles[1];
        this.candleLow = candles[2];
        this.candleClose = candles[3];
        this.movingAverage = movingAverageFrom(this.points, 5);
        this.trend = trendFrom(this.points);
        this.volatility = volatilityFrom(this.candleHigh, this.candleLow);
    }

    public String type() {
        return this.type;
    }

    public String id() {
        return this.id;
    }

    public String metric() {
        return this.metric;
    }

    public String title() {
        return this.title;
    }

    public int value() {
        return this.value;
    }

    public String leader() {
        return this.leader;
    }

    public int leaderValue() {
        return this.leaderValue;
    }

    public int[] points() {
        return Arrays.copyOf(this.points, this.points.length);
    }

    public int[] pointsView() {
        return this.points;
    }

    public String source() {
        return this.source;
    }

    public int base() {
        return this.base;
    }

    public int change() {
        return this.change;
    }

    public int volume() {
        return this.volume;
    }

    public int[] candleOpen() {
        return Arrays.copyOf(this.candleOpen, this.candleOpen.length);
    }

    public int[] candleOpenView() {
        return this.candleOpen;
    }

    public int[] candleHigh() {
        return Arrays.copyOf(this.candleHigh, this.candleHigh.length);
    }

    public int[] candleHighView() {
        return this.candleHigh;
    }

    public int[] candleLow() {
        return Arrays.copyOf(this.candleLow, this.candleLow.length);
    }

    public int[] candleLowView() {
        return this.candleLow;
    }

    public int[] candleClose() {
        return Arrays.copyOf(this.candleClose, this.candleClose.length);
    }

    public int[] candleCloseView() {
        return this.candleClose;
    }

    public int[] movingAverage() {
        return Arrays.copyOf(this.movingAverage, this.movingAverage.length);
    }

    public int[] movingAverageView() {
        return this.movingAverage;
    }

    public int trend() {
        return this.trend;
    }

    public int volatility() {
        return this.volatility;
    }

    public String contextKey() {
        return this.contextKey;
    }

    public int confidence() {
        return this.confidence;
    }

    public boolean authoritative() {
        return this.authoritative;
    }

    public boolean market() {
        return "market".equals(this.type);
    }

    public boolean activity() {
        return !market();
    }

    public boolean rising() {
        return this.change > 0 || this.trend > 0;
    }

    public boolean falling() {
        return this.change < 0 || this.trend < 0;
    }

    private static int[][] candlesFrom(int[] source) {
        int[] safeSource = source == null ? new int[0] : source;

        if (safeSource.length == 0) {
            return new int[][]{new int[0], new int[0], new int[0], new int[0]};
        }

        int window = safeSource.length > 48 ? 4 : safeSource.length > 24 ? 3 : 2;
        int candleCount = Math.max(1, (int) Math.ceil(safeSource.length / (float) window));
        int[] open = new int[candleCount];
        int[] high = new int[candleCount];
        int[] low = new int[candleCount];
        int[] close = new int[candleCount];

        for (int candle = 0; candle < candleCount; candle++) {
            int start = candle * window;
            int end = Math.min(safeSource.length, start + window);
            int candleOpen = Math.max(0, safeSource[start]);
            int candleClose = candleOpen;
            int candleHigh = candleOpen;
            int candleLow = candleOpen;

            for (int i = start; i < end; i++) {
                int value = Math.max(0, safeSource[i]);
                candleHigh = Math.max(candleHigh, value);
                candleLow = Math.min(candleLow, value);
                candleClose = value;
            }

            open[candle] = candleOpen;
            high[candle] = candleHigh;
            low[candle] = candleLow;
            close[candle] = candleClose;
        }

        return new int[][]{open, high, low, close};
    }

    private static int[] movingAverageFrom(int[] source, int window) {
        int[] safeSource = source == null ? new int[0] : source;
        int[] average = new int[safeSource.length];

        for (int i = 0; i < safeSource.length; i++) {
            int start = Math.max(0, i - Math.max(1, window) + 1);
            int total = 0;
            int count = 0;

            for (int value = start; value <= i; value++) {
                total += Math.max(0, safeSource[value]);
                count++;
            }

            average[i] = count == 0 ? 0 : total / count;
        }

        return average;
    }

    private static int trendFrom(int[] source) {
        if (source == null || source.length < 2) {
            return 0;
        }

        int start = Math.max(0, source[source.length - 2]);
        int end = Math.max(0, source[source.length - 1]);
        return end - start;
    }

    private static int volatilityFrom(int[] high, int[] low) {
        if (high == null || low == null || high.length == 0 || low.length == 0) {
            return 0;
        }

        int count = Math.min(high.length, low.length);
        int total = 0;

        for (int i = 0; i < count; i++) {
            total += Math.max(0, high[i] - low[i]);
        }

        return total / Math.max(1, count);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback == null ? "" : fallback : value;
    }
}
