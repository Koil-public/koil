package com.spirit.koil.api.stats.global;

import java.util.*;

public final class KoilMarketPriceSnapshot {
    private final String marketId;
    private final String marketTitle;
    private final String momensIcon;
    private final double momensValue;
    private final double diamondsPerUnit;
    private final double unitsPerDiamond;
    private final int[] valuePoints;
    private final List<String> reasons;
    private final long createdAt;
    private final int confidence;
    private final boolean authoritative;

    public KoilMarketPriceSnapshot(String marketId, String marketTitle, String momensIcon, double momensValue, double diamondsPerUnit, double unitsPerDiamond, int[] valuePoints, List<String> reasons, long createdAt, int confidence, boolean authoritative) {
        this.marketId = safe(marketId, "unknown");
        this.marketTitle = safe(marketTitle, "Unknown Market");
        this.momensIcon = safe(momensIcon, "ƒ");
        this.momensValue = Math.max(0.0D, momensValue);
        this.diamondsPerUnit = Math.max(0.0D, diamondsPerUnit);
        this.unitsPerDiamond = Math.max(0.0D, unitsPerDiamond);
        this.valuePoints = valuePoints == null ? new int[0] : Arrays.copyOf(valuePoints, valuePoints.length);
        this.reasons = new ArrayList<>(reasons == null ? Collections.emptyList() : reasons);
        this.createdAt = Math.max(0L, createdAt);
        this.confidence = Math.max(0, Math.min(100, confidence));
        this.authoritative = authoritative;
    }

    public String marketId() {
        return this.marketId;
    }

    public String marketTitle() {
        return this.marketTitle;
    }

    public String momensIcon() {
        return this.momensIcon;
    }

    public double momensValue() {
        return this.momensValue;
    }

    public double diamondsPerUnit() {
        return this.diamondsPerUnit;
    }

    public double unitsPerDiamond() {
        return this.unitsPerDiamond;
    }

    public int[] valuePoints() {
        return Arrays.copyOf(this.valuePoints, this.valuePoints.length);
    }

    public int[] valuePointsView() {
        return this.valuePoints;
    }

    public List<String> reasons() {
        return new ArrayList<>(this.reasons);
    }

    public long createdAt() {
        return this.createdAt;
    }

    public int confidence() {
        return this.confidence;
    }

    public boolean authoritative() {
        return this.authoritative;
    }

    public String compactMomens() {
        return this.momensIcon + KoilMarketValueQuote.compactDecimal(this.momensValue);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
