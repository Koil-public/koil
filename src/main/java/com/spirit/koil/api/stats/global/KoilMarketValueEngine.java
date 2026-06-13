package com.spirit.koil.api.stats.global;

import java.util.*;

public final class KoilMarketValueEngine {
    public static final String MOMENS_ICON = "ƒ";
    public static final double BASE_MOMENS_VALUE = 1000.0D;

    private KoilMarketValueEngine() {
    }

    public static KoilMarketValueQuote quote(KoilGlobalMarketViewRow row, List<KoilGlobalMarketViewRow> marketRows) {
        if (row == null) {
            return emptyQuote();
        }

        KoilMarketValueConfig config = KoilMarketValueConfig.current();
        List<KoilGlobalMarketViewRow> rows = marketRows == null ? Collections.emptyList() : marketRows;
        KoilGlobalMarketViewRow reserveRow = selectedReserveBaseRow(rows, config);
        double reserveScore = selectedReserveBaseScore(rows, config);
        double diamondScore = reserveDiamondScore(rows);
        double rowScore = marketScore(row);
        double momens = clamp(BASE_MOMENS_VALUE * rowScore / Math.max(1.0D, reserveScore), 0.001D, 100000000.0D);
        double diamondMomens = clamp(BASE_MOMENS_VALUE * diamondScore / Math.max(1.0D, reserveScore), 0.001D, 100000000.0D);
        double diamondsPerUnit = momens / Math.max(0.001D, diamondMomens);
        double unitsPerDiamond = diamondMomens / Math.max(0.001D, momens);
        double reserveMomens = BASE_MOMENS_VALUE;
        String reserveTitle = reserveRow == null ? "Reserve Basket" : reserveRow.title();
        String reserveSubject = reserveRow == null ? "reserve unit" : reserveRow.subject();
        double reservePerUnit = momens / Math.max(0.001D, reserveMomens);
        double unitsPerReserve = reserveMomens / Math.max(0.001D, momens);
        ScoreParts parts = scoreParts(row);
        List<String> reasons = quoteReasons(row, rows, parts, momens, unitsPerReserve, config);

        return new KoilMarketValueQuote(row.id(), row.title(), config.momensIcon(), momens, diamondsPerUnit, unitsPerDiamond, reserveTitle, reserveSubject, reservePerUnit, unitsPerReserve, reserveMomens, parts.demand, parts.supply, parts.scarcity, parts.utility, parts.trend, parts.confidence, row.confidence(), row.authoritative(), reasons);
    }

    public static KoilMarketPriceSnapshot priceSnapshot(KoilGlobalMarketViewRow row, List<KoilGlobalMarketViewRow> marketRows) {
        KoilMarketValueQuote quote = quote(row, marketRows);
        int[] valuePoints = valueSeries(row, marketRows);
        return new KoilMarketPriceSnapshot(quote.marketId(), quote.marketTitle(), quote.momensIcon(), quote.momensValue(), quote.diamondsPerUnit(), quote.unitsPerDiamond(), valuePoints, quote.reasons(), System.currentTimeMillis(), quote.confidence(), quote.authoritative());
    }

    public static List<KoilMarketPriceSnapshot> priceSnapshots(List<KoilGlobalMarketViewRow> rows) {
        List<KoilGlobalMarketViewRow> safeRows = rows == null ? Collections.emptyList() : rows;
        List<KoilMarketPriceSnapshot> snapshots = new ArrayList<>();

        for (KoilGlobalMarketViewRow row : safeRows) {
            if (row != null) {
                snapshots.add(priceSnapshot(row, safeRows));
            }
        }

        snapshots.sort((a, b) -> Double.compare(b.momensValue(), a.momensValue()));
        return snapshots;
    }

    public static int[] valueSeries(KoilGlobalMarketViewRow row, List<KoilGlobalMarketViewRow> marketRows) {
        if (row == null || row.chartRow() == null) {
            return new int[0];
        }

        int[] points = row.chartRow().pointsView();
        double currentMomens = quote(row, marketRows).momensValue();
        double scaleUnits = 100.0D;

        if (points.length == 0) {
            return new int[]{Math.max(1, (int) Math.round(currentMomens * scaleUnits))};
        }

        int latest = Math.max(1, points[points.length - 1]);
        int[] values = new int[points.length];
        double previous = currentMomens;
        double smoothing = KoilMarketValueConfig.current().priceSmoothing();

        for (int i = points.length - 1; i >= 0; i--) {
            double scale = Math.max(0.05D, points[i] / (double) latest);
            double raw = currentMomens * scale;
            double smoothed = i == points.length - 1 ? currentMomens : raw * (1.0D - smoothing) + previous * smoothing;
            values[i] = Math.max(1, (int) Math.round(smoothed * scaleUnits));
            previous = smoothed;
        }

        return values;
    }

    public static KoilGlobalMarketViewRow findMarket(List<KoilGlobalMarketViewRow> rows, String query) {
        if (rows == null || query == null || query.isBlank()) {
            return null;
        }

        String clean = normalizeQuery(query);
        KoilGlobalMarketViewRow best = null;
        int bestScore = -1;

        for (KoilGlobalMarketViewRow row : rows) {
            if (row == null) {
                continue;
            }

            int score = matchScore(row, clean);

            if (score > bestScore) {
                bestScore = score;
                best = row;
            }
        }

        return bestScore <= 0 ? null : best;
    }

    public static KoilGlobalMarketViewRow findMarketExactId(List<KoilGlobalMarketViewRow> rows, String id) {
        if (rows == null || id == null || id.isBlank()) {
            return null;
        }

        String clean = normalizeId(id);
        KoilGlobalMarketViewRow best = null;
        int bestScore = -1;

        for (KoilGlobalMarketViewRow row : rows) {
            if (row == null) {
                continue;
            }

            int score = 0;

            if (normalizeId(row.subject()).equals(clean)) {
                score = 1000;
            }

            if (normalizeId(row.id()).contains(clean)) {
                score = Math.max(score, 850);
            }

            for (String metric : row.rawMetrics()) {
                if (normalizeId(metric).contains(clean)) {
                    score = Math.max(score, 750);
                }
            }

            if (score > 0 && "sub".equals(row.tier())) {
                score += 80;
            }

            if (score > 0 && "resource".equals(row.domain())) {
                score += 45;
            }

            if (score > 0 && "raw".equals(row.tier())) {
                score -= 20;
            }

            if (score > bestScore) {
                bestScore = score;
                best = row;
            }
        }

        return bestScore <= 0 ? findMarket(rows, id) : best;
    }

    public static String compare(KoilGlobalMarketViewRow first, KoilGlobalMarketViewRow second, List<KoilGlobalMarketViewRow> rows) {
        if (first == null || second == null) {
            return "Unable to compare markets";
        }

        KoilMarketValueQuote a = quote(first, rows);
        KoilMarketValueQuote b = quote(second, rows);
        double ratio = a.momensValue() / Math.max(0.001D, b.momensValue());

        if (ratio >= 1.0D) {
            return "1 " + first.title() + " -> " + KoilMarketValueQuote.compactDecimal(ratio) + " " + second.title();
        }

        return "1 " + second.title() + " -> " + KoilMarketValueQuote.compactDecimal(1.0D / Math.max(0.001D, ratio)) + " " + first.title();
    }

    public static List<String> compareLines(KoilGlobalMarketViewRow first, KoilGlobalMarketViewRow second, List<KoilGlobalMarketViewRow> rows) {
        List<String> lines = new ArrayList<>();

        if (first == null || second == null) {
            lines.add("Compare -> unable to resolve both markets");
            return lines;
        }

        KoilMarketValueQuote a = quote(first, rows);
        KoilMarketValueQuote b = quote(second, rows);
        double firstPerSecond = b.momensValue() / Math.max(0.001D, a.momensValue());
        double secondPerFirst = a.momensValue() / Math.max(0.001D, b.momensValue());
        lines.add(first.title() + " -> " + second.title());
        lines.add(first.title() + " -> " + a.compactMomens());
        lines.add(second.title() + " -> " + b.compactMomens());
        lines.add("1 " + second.subject() + " -> " + KoilMarketValueQuote.compactDecimal(firstPerSecond) + " " + first.subject());
        lines.add("1 " + first.subject() + " -> " + KoilMarketValueQuote.compactDecimal(secondPerFirst) + " " + second.subject());
        lines.add("Base -> " + selectedReserveBaseLabel(rows, KoilMarketValueConfig.current()));
        lines.add("Source -> " + sourceLabel(first, second));
        lines.add("Confidence -> " + Math.min(first.confidence(), second.confidence()) + "%");
        return lines;
    }

    public static List<String> reserveBasketLabels() {
        List<String> labels = new ArrayList<>();
        labels.add("Dynamic base -> " + selectedReserveBaseLabel(Collections.emptyList(), KoilMarketValueConfig.current()));
        labels.addAll(KoilMarketValueConfig.current().reserveItems());
        return labels;
    }

    public static String selectedReserveBaseSubject(List<KoilGlobalMarketViewRow> rows, KoilMarketValueConfig config) {
        KoilGlobalMarketViewRow row = selectedReserveBaseRow(rows, config);
        return row == null ? "reserve unit" : row.subject();
    }

    public static String oneMomenReserveLine(List<KoilGlobalMarketViewRow> rows) {
        KoilMarketValueConfig config = KoilMarketValueConfig.current();
        KoilGlobalMarketViewRow row = selectedReserveBaseRow(rows, config);
        String subject = row == null ? "reserve unit" : row.subject().replace('_', ' ').replace('/', ' ');
        double reservePerMomen = 1.0D / BASE_MOMENS_VALUE;
        return "1 " + config.momensIcon() + " -> " + KoilMarketValueQuote.compactDecimal(reservePerMomen) + " " + subject;
    }

    public static String baseExchangeLine(List<KoilGlobalMarketViewRow> rows) {
        KoilMarketValueConfig config = KoilMarketValueConfig.current();
        return "Base: " + selectedReserveBaseLabel(rows, config) + " | " + oneMomenReserveLine(rows);
    }

    public static double marketScore(KoilGlobalMarketViewRow row) {
        ScoreParts parts = scoreParts(row);
        double activity = Math.log1p(Math.max(0, row.value())) / 3.5D;
        double demandSupply = (0.65D + parts.demand) / Math.sqrt(0.85D + parts.supply * 0.42D);
        double score = parts.intrinsic * parts.scarcity * parts.utility * parts.trend * parts.confidence * parts.stability * (1.0D + activity) * demandSupply;
        return clamp(score, 0.001D, 100000000.0D);
    }

    public static double reserveDiamondScore(List<KoilGlobalMarketViewRow> rows) {
        double best = 0.0D;

        if (rows != null) {
            for (KoilGlobalMarketViewRow row : rows) {
                if (row == null) {
                    continue;
                }

                String haystack = (row.title() + " " + row.subject() + " " + row.id()).toLowerCase(Locale.ROOT);

                if (haystack.contains("diamond") && ("resource".equals(row.domain()) || "item".equals(row.domain()) || "raw".equals(row.tier()) || "sub".equals(row.tier()))) {
                    best = Math.max(best, marketScore(row));
                }
            }
        }

        return best > 0.0D ? best : 480.0D;
    }

    public static double reserveBasketScore(List<KoilGlobalMarketViewRow> rows, KoilMarketValueConfig config) {
        return selectedReserveBaseScore(rows, config);
    }

    public static double selectedReserveBaseScore(List<KoilGlobalMarketViewRow> rows, KoilMarketValueConfig config) {
        KoilGlobalMarketViewRow row = selectedReserveBaseRow(rows, config);

        if (row != null) {
            return Math.max(1.0D, marketScore(row));
        }

        List<String> reserveItems = config == null ? KoilMarketValueConfig.defaults().reserveItems() : config.reserveItems();
        double total = 0.0D;
        int count = 0;

        for (String reserve : reserveItems) {
            KoilGlobalMarketViewRow reserveRow = findReserveRow(rows, reserve);

            if (reserveRow != null) {
                total += marketScore(reserveRow);
                count++;
            }
        }

        if (count > 0) {
            return Math.max(1.0D, total / count);
        }

        return reserveDiamondScore(rows);
    }

    public static String selectedReserveBaseLabel(List<KoilGlobalMarketViewRow> rows, KoilMarketValueConfig config) {
        KoilGlobalMarketViewRow row = selectedReserveBaseRow(rows, config);

        if (row != null) {
            return row.title() + " (" + KoilMarketValueQuote.compactDecimal(marketScore(row)) + " reserve score)";
        }

        return "reserve basket fallback";
    }

    public static KoilGlobalMarketViewRow selectedReserveBaseRow(List<KoilGlobalMarketViewRow> rows, KoilMarketValueConfig config) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }

        KoilMarketValueConfig safeConfig = config == null ? KoilMarketValueConfig.current() : config;
        KoilGlobalMarketViewRow best = null;
        double bestScore = -1.0D;
        List<String> configured = safeConfig.reserveItems();

        for (KoilGlobalMarketViewRow row : rows) {
            if (row == null) {
                continue;
            }

            boolean configuredReserve = false;

            for (String reserve : configured) {
                if (reserveMatches(row, reserve)) {
                    configuredReserve = true;
                    break;
                }
            }

            boolean dynamicCandidate = safeConfig.dynamicReserveBase() && "resource".equals(row.domain()) && ("sub".equals(row.tier()) || "head".equals(row.tier()));

            if (!configuredReserve && !dynamicCandidate) {
                continue;
            }

            double score = reserveQualityScore(row, configuredReserve);

            if (score > bestScore) {
                bestScore = score;
                best = row;
            }
        }

        return best;
    }

    public static boolean isReserveAsset(KoilGlobalMarketViewRow row) {
        if (row == null) {
            return false;
        }

        for (String reserve : KoilMarketValueConfig.current().reserveItems()) {
            if (reserveMatches(row, reserve)) {
                return true;
            }
        }

        return false;
    }

    public static KoilMarketValueQuote emptyQuote() {
        return new KoilMarketValueQuote("unknown", "Unknown Market", KoilMarketValueConfig.current().momensIcon(), 0.001D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0, false, Collections.emptyList());
    }

    private static ScoreParts scoreParts(KoilGlobalMarketViewRow row) {
        Map<String, Integer> components = row.components();
        KoilMarketValueConfig config = KoilMarketValueConfig.current();
        double positive = 0.0D;
        double negative = 0.0D;
        double supply = 0.0D;
        double neutral = 0.0D;
        double ignored = 0.0D;

        for (Map.Entry<String, Integer> entry : components.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            int rawValue = Math.max(0, entry.getValue());
            String category = KoilMarketLedger.categoryForMetric(key);
            double weighted = rawValue * KoilMarketLedger.priceWeight(category, row.authoritative());

            if (!KoilMarketLedger.shouldAffectPrice(category)) {
                ignored += rawValue;
                continue;
            }

            if (KoilMarketLedger.isSupply(category, key)) {
                supply += weighted;
            } else if (KoilMarketLedger.isNegative(category, key)) {
                negative += weighted;
            } else if (KoilMarketLedger.isDemand(category, key)) {
                positive += weighted;
            } else {
                neutral += weighted;
            }
        }

        if (components.isEmpty()) {
            neutral = Math.max(0, row.value());
        }

        double total = Math.max(1.0D, positive + negative + supply + neutral);
        double demand = 0.45D + positive / total + neutral * 0.18D / total;
        double supplyScore = 0.45D + supply / total;
        double scarcity = intrinsicScarcity(row.domain(), row.subject(), row.title());
        double utility = domainUtility(row.domain(), row.subject(), row.title());
        double trend = 1.0D + clamp(row.chartRow() == null ? 0.0D : row.chartRow().change() / Math.max(1.0D, row.value() + 16.0D), -0.22D, 0.22D);
        double confidence = 0.35D + Math.max(0, Math.min(100, row.confidence())) / 100.0D * 0.65D;
        double intrinsic = intrinsicBase(row.domain(), row.subject(), row.title());
        double sampleStrength = Math.min(1.0D, Math.log1p(total) / 6.5D);
        double lowSamplePenalty = 1.0D - (1.0D - sampleStrength) * config.lowSamplePenalty();
        double volatilityPenalty = 1.0D;

        if (row.chartRow() != null) {
            double volatility = Math.max(0, row.chartRow().volatility()) / Math.max(1.0D, row.value() + 12.0D);
            volatilityPenalty = 1.0D - clamp(volatility, 0.0D, 0.6D) * config.volatilityPenalty();
        }

        double spamPenalty = 1.0D;

        if (row.leaderValue() > 0 && row.value() > 0) {
            double leaderShare = row.leaderValue() / (double) Math.max(1, row.value());
            spamPenalty = 1.0D - Math.max(0.0D, leaderShare - 0.42D) * config.antiSpamStrength();
        }

        double transferPenalty = ignored <= 0.0D ? 1.0D : 1.0D - Math.min(0.18D, ignored / Math.max(1.0D, ignored + total) * 0.18D);
        double uniqueWeight = row.leaderValue() > 0 && row.value() > row.leaderValue() ? 1.0D + config.uniquePlayerWeight() * 0.08D : 1.0D;
        double stability = clamp(lowSamplePenalty * volatilityPenalty * spamPenalty * transferPenalty * uniqueWeight, 0.12D, 1.18D);

        if (negative > 0.0D) {
            if ("entity".equals(row.domain()) || "combat".equals(row.domain())) {
                demand *= 1.0D - Math.min(0.35D, negative / total * 0.45D);
                trend *= 1.0D - Math.min(0.28D, negative / total * 0.38D);
            } else {
                scarcity *= 1.0D + Math.min(0.42D, negative / total * 0.55D);
                demand *= 1.0D + Math.min(0.16D, negative / total * 0.22D);
            }
        }

        if (!row.authoritative() && !config.allowClientEstimatedMarkets()) {
            confidence *= 0.25D;
        }

        return new ScoreParts(intrinsic, demand, supplyScore, scarcity, utility, trend, confidence, positive, negative, supply, neutral, ignored, stability);
    }

    private static List<String> quoteReasons(KoilGlobalMarketViewRow row, List<KoilGlobalMarketViewRow> rows, ScoreParts parts, double momens, double unitsPerBase, KoilMarketValueConfig config) {
        List<String> lines = new ArrayList<>();
        lines.add("Momens: " + config.momensIcon() + KoilMarketValueQuote.compactDecimal(momens));
        lines.add("Base resource: " + selectedReserveBaseLabel(rows, config));
        lines.add("Base exchange: 1 base -> " + KoilMarketValueQuote.compactDecimal(unitsPerBase) + " units");
        lines.add("Demand score: " + KoilMarketValueQuote.compactDecimal(parts.demand));
        lines.add("Supply score: " + KoilMarketValueQuote.compactDecimal(parts.supply));
        lines.add("Scarcity score: " + KoilMarketValueQuote.compactDecimal(parts.scarcity));
        lines.add("Utility score: " + KoilMarketValueQuote.compactDecimal(parts.utility));
        lines.add("Trend score: " + KoilMarketValueQuote.compactDecimal(parts.trend));
        lines.add("Stability score: " + KoilMarketValueQuote.compactDecimal(parts.stability));
        lines.add("Pressure: +" + KoilMarketValueQuote.compactDecimal(parts.positive) + " demand / -" + KoilMarketValueQuote.compactDecimal(parts.negative) + " loss / " + KoilMarketValueQuote.compactDecimal(parts.supplyRaw) + " supply");

        if (parts.ignored > 0.0D) {
            lines.add("Transfer/admin ignored: " + KoilMarketValueQuote.compactDecimal(parts.ignored));
        }

        lines.add("Confidence: " + row.confidence() + "% " + (row.authoritative() ? "server exact" : "estimated"));

        if (config.physicalMomensCurrency()) {
            lines.add(KoilPhysicalMomensCurrency.summary());
        }

        return lines;
    }

    private static int matchScore(KoilGlobalMarketViewRow row, String cleanQuery) {
        String title = normalizeQuery(row.title());
        String subject = normalizeQuery(row.subject());
        String id = normalizeQuery(row.id());
        String domain = normalizeQuery(row.domain());
        String slashQuery = normalizeId(cleanQuery);
        String slashSubject = normalizeId(row.subject());
        String slashId = normalizeId(row.id());
        StringBuilder raw = new StringBuilder();
        StringBuilder slashRaw = new StringBuilder();

        for (String metric : row.rawMetrics()) {
            raw.append(' ').append(normalizeQuery(metric));
            slashRaw.append(' ').append(normalizeId(metric));
        }

        String rawText = raw.toString();
        String slashRawText = slashRaw.toString();
        int score = 0;

        if (title.equals(cleanQuery) || subject.equals(cleanQuery) || id.equals(cleanQuery) || slashSubject.equals(slashQuery)) {
            score = 1000;
        } else if (!slashQuery.isBlank() && (slashId.contains(slashQuery) || slashRawText.contains(slashQuery))) {
            score = 900;
        } else if (title.contains(cleanQuery)) {
            score = 820;
        } else if (subject.contains(cleanQuery)) {
            score = 760;
        } else if (rawText.contains(cleanQuery)) {
            score = 680;
        } else {
            String[] terms = cleanQuery.split("\\s+");
            int matched = 0;
            String haystack = title + " " + subject + " " + id + " " + domain + " " + rawText;

            for (String term : terms) {
                if (!term.isBlank() && haystack.contains(term)) {
                    matched++;
                }
            }

            if (matched == terms.length && matched > 0) {
                score = 620 + matched * 15;
            } else if (domain.contains(cleanQuery) || id.contains(cleanQuery)) {
                score = 500;
            }
        }

        return score <= 0 ? 0 : score + marketTierPreference(row);
    }

    private static int marketTierPreference(KoilGlobalMarketViewRow row) {
        if (row == null) {
            return 0;
        }

        if ("sub".equals(row.tier())) {
            return 80;
        }

        if ("head".equals(row.tier())) {
            return 45;
        }

        if ("raw".equals(row.tier())) {
            return 20;
        }

        return 0;
    }

    private static KoilGlobalMarketViewRow findReserveRow(List<KoilGlobalMarketViewRow> rows, String reserve) {
        if (rows == null || reserve == null || reserve.isBlank()) {
            return null;
        }

        KoilGlobalMarketViewRow best = null;
        double bestScore = -1.0D;

        for (KoilGlobalMarketViewRow row : rows) {
            if (row == null || !reserveMatches(row, reserve)) {
                continue;
            }

            double score = reserveQualityScore(row, true);

            if (score > bestScore) {
                bestScore = score;
                best = row;
            }
        }

        return best;
    }

    private static boolean reserveMatches(KoilGlobalMarketViewRow row, String reserve) {
        String normalized = normalizeReserveName(reserve);
        String slash = normalizeId(reserve);
        String haystack = normalizeQuery(row.title() + " " + row.subject() + " " + row.id() + " " + row.rawMetrics());
        String slashHaystack = normalizeId(row.title() + " " + row.subject() + " " + row.id() + " " + row.rawMetrics());
        return (!normalized.isBlank() && haystack.contains(normalized)) || (!slash.isBlank() && slashHaystack.contains(slash));
    }

    private static double reserveQualityScore(KoilGlobalMarketViewRow row, boolean configuredReserve) {
        ScoreParts parts = scoreParts(row);
        double score = marketScore(row);
        double confidence = Math.max(0.08D, row.confidence() / 100.0D);
        double authority = row.authoritative() ? 1.18D : 0.72D;
        double resource = "resource".equals(row.domain()) ? 1.16D : 0.82D;
        double tier = "sub".equals(row.tier()) ? 1.12D : "head".equals(row.tier()) ? 0.96D : 0.84D;
        double configured = configuredReserve ? 1.1D : 1.0D;
        double stable = clamp(parts.stability, 0.25D, 1.2D);
        return score * confidence * authority * resource * tier * configured * stable;
    }

    private static String normalizeReserveName(String reserve) {
        String clean = normalizeQuery(reserve);
        String[] parts = clean.split("\\s+");
        return parts.length == 0 ? clean : parts[parts.length - 1];
    }

    private static String normalizeQuery(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace(':', ' ').replace('/', ' ').replace('_', ' ').replace('-', ' ').trim().replaceAll("\\s+", " ");
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace(':', '/').replace('_', ' ').replace('-', ' ').trim();
    }

    private static String sourceLabel(KoilGlobalMarketViewRow first, KoilGlobalMarketViewRow second) {
        if (first.authoritative() && second.authoritative()) {
            return "server exact";
        }

        if (!first.authoritative() && !second.authoritative()) {
            return "estimated";
        }

        return "mixed exact/estimated";
    }

    private static double intrinsicBase(String domain, String subject, String title) {
        String key = key(subject, title);

        if (key.contains("netherite")) {
            return 2500.0D;
        }

        if (key.contains("diamond")) {
            return 1000.0D;
        }

        if (key.contains("emerald")) {
            return 650.0D;
        }

        if (key.contains("ancient debris")) {
            return 1600.0D;
        }

        if (key.contains("gold")) {
            return 320.0D;
        }

        if (key.contains("iron")) {
            return 220.0D;
        }

        if (key.contains("redstone") || key.contains("lapis")) {
            return 180.0D;
        }

        if (key.contains("coal") || key.contains("charcoal")) {
            return 90.0D;
        }

        if (key.contains("bread")) {
            return 12.0D;
        }

        if ("resource".equals(domain)) {
            return 150.0D;
        }

        if ("food".equals(domain)) {
            return 16.0D;
        }

        if ("block".equals(domain)) {
            return 22.0D;
        }

        if ("item".equals(domain)) {
            return 28.0D;
        }

        if ("entity".equals(domain) || "combat".equals(domain)) {
            return 40.0D;
        }

        return 18.0D;
    }

    private static double intrinsicScarcity(String domain, String subject, String title) {
        String key = key(subject, title);

        if (key.contains("netherite")) {
            return 5.0D;
        }

        if (key.contains("diamond")) {
            return 3.3D;
        }

        if (key.contains("emerald")) {
            return 2.5D;
        }

        if (key.contains("ancient debris")) {
            return 4.2D;
        }

        if (key.contains("gold")) {
            return 1.8D;
        }

        if (key.contains("iron")) {
            return 1.35D;
        }

        if (key.contains("dirt") || key.contains("stone") || key.contains("sand")) {
            return 0.55D;
        }

        if ("resource".equals(domain)) {
            return 1.25D;
        }

        if ("food".equals(domain)) {
            return 0.75D;
        }

        return 1.0D;
    }

    private static double domainUtility(String domain, String subject, String title) {
        String key = key(subject, title);

        if (key.contains("fuel") || key.contains("coal") || key.contains("charcoal")) {
            return 1.45D;
        }

        if (key.contains("diamond") || key.contains("iron") || key.contains("redstone") || key.contains("netherite")) {
            return 1.6D;
        }

        if (key.contains("bread") || key.contains("food")) {
            return 1.12D;
        }

        if ("resource".equals(domain)) {
            return 1.35D;
        }

        if ("food".equals(domain)) {
            return 1.05D;
        }

        if ("block".equals(domain)) {
            return 0.9D;
        }

        if ("entity".equals(domain) || "combat".equals(domain)) {
            return 0.85D;
        }

        return 1.0D;
    }

    private static String key(String subject, String title) {
        return (safe(subject) + " " + safe(title)).toLowerCase(Locale.ROOT).replace('_', ' ').replace('/', ' ');
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class ScoreParts {
        private final double intrinsic;
        private final double demand;
        private final double supply;
        private final double scarcity;
        private final double utility;
        private final double trend;
        private final double confidence;
        private final double positive;
        private final double negative;
        private final double supplyRaw;
        private final double neutral;
        private final double ignored;
        private final double stability;

        private ScoreParts(double intrinsic, double demand, double supply, double scarcity, double utility, double trend, double confidence, double positive, double negative, double supplyRaw, double neutral, double ignored, double stability) {
            this.intrinsic = intrinsic;
            this.demand = demand;
            this.supply = supply;
            this.scarcity = scarcity;
            this.utility = utility;
            this.trend = trend;
            this.confidence = confidence;
            this.positive = positive;
            this.negative = negative;
            this.supplyRaw = supplyRaw;
            this.neutral = neutral;
            this.ignored = ignored;
            this.stability = stability;
        }
    }
}
