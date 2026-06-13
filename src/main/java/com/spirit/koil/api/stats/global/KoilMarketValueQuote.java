package com.spirit.koil.api.stats.global;

import java.util.*;

public final class KoilMarketValueQuote {
    private final String marketId;
    private final String marketTitle;
    private final String momensIcon;
    private final double momensValue;
    private final double diamondsPerUnit;
    private final double unitsPerDiamond;
    private final String reserveTitle;
    private final String reserveSubject;
    private final double reservePerUnit;
    private final double unitsPerReserve;
    private final double momensPerReserve;
    private final double demandScore;
    private final double supplyScore;
    private final double scarcityScore;
    private final double utilityScore;
    private final double trendScore;
    private final double confidenceScore;
    private final int confidence;
    private final boolean authoritative;
    private final List<String> reasons;

    public KoilMarketValueQuote(String marketId, String marketTitle, String momensIcon, double momensValue, double diamondsPerUnit, double unitsPerDiamond, double demandScore, double supplyScore, double scarcityScore, double utilityScore, double trendScore, double confidenceScore, int confidence, boolean authoritative, List<String> reasons) {
        this(marketId, marketTitle, momensIcon, momensValue, diamondsPerUnit, unitsPerDiamond, "Diamond Resource Market", "diamond", diamondsPerUnit, unitsPerDiamond, Math.max(0.001D, momensValue / Math.max(0.001D, diamondsPerUnit)), demandScore, supplyScore, scarcityScore, utilityScore, trendScore, confidenceScore, confidence, authoritative, reasons);
    }

    public KoilMarketValueQuote(String marketId, String marketTitle, String momensIcon, double momensValue, double diamondsPerUnit, double unitsPerDiamond, String reserveTitle, String reserveSubject, double reservePerUnit, double unitsPerReserve, double momensPerReserve, double demandScore, double supplyScore, double scarcityScore, double utilityScore, double trendScore, double confidenceScore, int confidence, boolean authoritative, List<String> reasons) {
        this.marketId = safe(marketId, "unknown");
        this.marketTitle = safe(marketTitle, "Unknown Market");
        this.momensIcon = safe(momensIcon, "ƒ");
        this.momensValue = Math.max(0.001D, momensValue);
        this.diamondsPerUnit = Math.max(0.0D, diamondsPerUnit);
        this.unitsPerDiamond = Math.max(0.0D, unitsPerDiamond);
        this.reserveTitle = safe(reserveTitle, "Reserve Resource Market");
        this.reserveSubject = cleanUnit(safe(reserveSubject, this.reserveTitle));
        this.reservePerUnit = Math.max(0.0D, reservePerUnit);
        this.unitsPerReserve = Math.max(0.0D, unitsPerReserve);
        this.momensPerReserve = Math.max(0.001D, momensPerReserve);
        this.demandScore = Math.max(0.0D, demandScore);
        this.supplyScore = Math.max(0.0D, supplyScore);
        this.scarcityScore = Math.max(0.0D, scarcityScore);
        this.utilityScore = Math.max(0.0D, utilityScore);
        this.trendScore = Math.max(0.0D, trendScore);
        this.confidenceScore = Math.max(0.0D, Math.min(1.0D, confidenceScore));
        this.confidence = Math.max(0, Math.min(100, confidence));
        this.authoritative = authoritative;
        this.reasons = new ArrayList<>(reasons == null ? Collections.emptyList() : reasons);
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

    public String reserveTitle() {
        return this.reserveTitle;
    }

    public String reserveSubject() {
        return this.reserveSubject;
    }

    public double reservePerUnit() {
        return this.reservePerUnit;
    }

    public double unitsPerReserve() {
        return this.unitsPerReserve;
    }

    public double momensPerReserve() {
        return this.momensPerReserve;
    }

    public double demandScore() {
        return this.demandScore;
    }

    public double supplyScore() {
        return this.supplyScore;
    }

    public double scarcityScore() {
        return this.scarcityScore;
    }

    public double utilityScore() {
        return this.utilityScore;
    }

    public double trendScore() {
        return this.trendScore;
    }

    public double confidenceScore() {
        return this.confidenceScore;
    }

    public int confidence() {
        return this.confidence;
    }

    public boolean authoritative() {
        return this.authoritative;
    }

    public List<String> reasons() {
        return new ArrayList<>(this.reasons);
    }

    public String compactMomens() {
        return this.momensIcon + compactDecimal(this.momensValue);
    }

    public String compactExchange(String subjectName) {
        String unit = cleanUnit(subjectName);

        if (this.unitsPerReserve >= 1.0D) {
            return "1 " + this.reserveSubject + " -> " + compactDecimal(this.unitsPerReserve) + " " + unit;
        }

        return "1 " + unit + " -> " + compactDecimal(this.reservePerUnit) + " " + this.reserveSubject;
    }

    public String longExchange(String subjectName) {
        String unit = cleanUnit(subjectName);
        return "Exchange: 1 " + this.reserveSubject + " -> " + compactDecimal(this.unitsPerReserve) + " " + unit + "  |  1 " + unit + " -> " + compactDecimal(this.reservePerUnit) + " " + this.reserveSubject;
    }

    public String oneMomenReserveLine() {
        double reservePerMomen = 1.0D / Math.max(0.001D, this.momensPerReserve);
        return "1 " + this.momensIcon + " -> " + compactDecimal(reservePerMomen) + " " + this.reserveSubject;
    }

    public String scoreSummary() {
        return "Demand " + compactDecimal(this.demandScore) + "  Supply " + compactDecimal(this.supplyScore) + "  Scarcity " + compactDecimal(this.scarcityScore) + "  Utility " + compactDecimal(this.utilityScore);
    }

    public static String compactDecimal(double value) {
        double safe = Math.max(0.0D, value);

        if (safe >= 1000000.0D) {
            return String.format(Locale.ROOT, "%.2fm", safe / 1000000.0D);
        }

        if (safe >= 1000.0D) {
            return String.format(Locale.ROOT, "%.2fk", safe / 1000.0D);
        }

        if (safe >= 100.0D) {
            return String.format(Locale.ROOT, "%.1f", safe);
        }

        if (safe >= 1.0D) {
            return String.format(Locale.ROOT, "%.2f", safe);
        }

        return String.format(Locale.ROOT, "%.4f", safe);
    }

    private static String cleanUnit(String subjectName) {
        String value = safe(subjectName, "unit").replace('_', ' ').replace('/', ' ').trim();

        if (value.isBlank() || "unknown".equalsIgnoreCase(value)) {
            return "unit";
        }

        return value;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
