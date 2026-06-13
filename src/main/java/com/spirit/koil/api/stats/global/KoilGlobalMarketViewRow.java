package com.spirit.koil.api.stats.global;

import java.util.*;

public final class KoilGlobalMarketViewRow {
    private final String tier;
    private final String id;
    private final String domain;
    private final String subject;
    private final String action;
    private final String title;
    private final String description;
    private final int value;
    private final String leader;
    private final int leaderValue;
    private final int confidence;
    private final boolean authoritative;
    private final Map<String, Integer> components;
    private final List<String> rawMetrics;
    private final KoilGlobalActivityRow chartRow;

    private KoilGlobalMarketViewRow(String tier, String id, String domain, String subject, String action, String title, String description, int value, String leader, int leaderValue, int confidence, boolean authoritative, Map<String, Integer> components, List<String> rawMetrics, KoilGlobalActivityRow chartRow) {
        this.tier = safe(tier, "raw");
        this.id = safe(id, this.tier + "/unknown");
        this.domain = safe(domain, "developer");
        this.subject = safe(subject, "unknown");
        this.action = safe(action, "activity");
        this.title = safe(title, "Unknown Market");
        this.description = safe(description, "No description available");
        this.value = Math.max(0, value);
        this.leader = safe(leader, "none");
        this.leaderValue = Math.max(0, leaderValue);
        this.confidence = Math.max(0, Math.min(100, confidence));
        this.authoritative = authoritative;
        this.components = new LinkedHashMap<>(components == null ? Collections.emptyMap() : components);
        this.rawMetrics = new ArrayList<>(rawMetrics == null ? Collections.emptyList() : rawMetrics);
        this.chartRow = chartRow;
    }

    public String tier() {
        return this.tier;
    }

    public String id() {
        return this.id;
    }

    public String domain() {
        return this.domain;
    }

    public String subject() {
        return this.subject;
    }

    public String action() {
        return this.action;
    }

    public String title() {
        return this.title;
    }

    public String description() {
        return this.description;
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

    public int confidence() {
        return this.confidence;
    }

    public boolean authoritative() {
        return this.authoritative;
    }

    public Map<String, Integer> components() {
        return new LinkedHashMap<>(this.components);
    }

    public List<String> rawMetrics() {
        return new ArrayList<>(this.rawMetrics);
    }

    public KoilGlobalActivityRow chartRow() {
        return this.chartRow;
    }


    public String stockPortfolioHeader() {
        return "Market | portfolio | " + titleCase(this.tier) + " | " + this.confidence + "%";
    }

    public String stockPortfolioPrompt() {
        return "ƒ_:" + this.title;
    }

    public String stockPortfolioSubPrompt() {
        String source = this.authoritative ? "server exact" : "mixed/estimated";
        return this.domain + " market  |  " + this.action + "  |  " + source;
    }

    public List<String> stockPortfolioReceiptGridLines() {
        List<String> lines = new ArrayList<>();
        KoilGlobalActivityRow row = safeChartRow();
        int change = row == null ? 0 : row.change();
        int volume = row == null ? 0 : row.volume();
        int trend = row == null ? change : row.trend();
        int volatility = row == null ? 0 : row.volatility();
        lines.add(stockReceiptCell("Market", this.title));
        lines.add(stockReceiptCell("Tier", titleCase(this.tier)) + "   " + stockReceiptCell("Domain", titleCase(this.domain)));
        lines.add(stockReceiptCell("Price", compact(this.value)) + "   " + stockReceiptCell("Change", signedCompact(change)));
        lines.add(stockReceiptCell("Trend", trendLabel(trend)) + "   " + stockReceiptCell("Volume", compact(volume)));
        lines.add(stockReceiptCell("Leader", this.leader) + "   " + stockReceiptCell("Top", compact(this.leaderValue)));
        lines.add(stockReceiptCell("Source", this.authoritative ? "server exact" : "mixed estimate") + "   " + stockReceiptCell("Conf", this.confidence + "%"));
        String ohlc = latestOhlcSummary(row);

        if (!ohlc.isBlank()) {
            lines.add(stockReceiptCell("OHLC", ohlc));
        }

        if (volatility > 0) {
            lines.add(stockReceiptCell("Volatility", compact(volatility)));
        }

        return lines;
    }

    public List<String> stockPortfolioComponentGridLines(int limit) {
        List<String> lines = new ArrayList<>();

        if (this.components.isEmpty()) {
            lines.add(stockReceiptCell("Components", "none"));
            return lines;
        }

        int count = 0;

        for (Map.Entry<String, Integer> entry : sortedComponentEntries(this.components)) {
            if (count >= Math.max(1, limit)) {
                lines.add(stockReceiptCell("More", String.valueOf(this.components.size() - count)));
                break;
            }

            lines.add(stockReceiptCell(titleCase(entry.getKey()), compact(entry.getValue())));
            count++;
        }

        return lines;
    }

    public List<String> stockPortfolioPopupLines() {
        List<String> lines = new ArrayList<>();
        lines.add(stockPortfolioHeader());
        lines.add(stockPortfolioPrompt());
        lines.add(stockPortfolioSubPrompt());
        lines.addAll(stockPortfolioReceiptGridLines());
        lines.addAll(stockPortfolioComponentGridLines(5));
        return lines;
    }

    public List<String> stockPortfolioPopupLines(String hoveredElement, int hoveredIndex, int hoveredValue) {
        List<String> lines = stockPortfolioPopupLines();
        String element = hoveredElement == null || hoveredElement.isBlank() ? "chart element" : hoveredElement;
        lines.add(stockReceiptCell("Hover", element));

        if (hoveredIndex >= 0) {
            lines.add(stockReceiptCell("Capture", String.valueOf(hoveredIndex)) + "   " + stockReceiptCell("Value", signedCompact(hoveredValue)));
        }

        return lines;
    }

    public Map<String, String> stockPortfolioReceiptGrid() {
        Map<String, String> grid = new LinkedHashMap<>();
        KoilGlobalActivityRow row = safeChartRow();
        int change = row == null ? 0 : row.change();
        grid.put("Market", this.title);
        grid.put("Tier", titleCase(this.tier));
        grid.put("Domain", titleCase(this.domain));
        grid.put("Action", titleCase(this.action));
        grid.put("Price", compact(this.value));
        grid.put("Change", signedCompact(change));
        grid.put("Trend", trendLabel(row == null ? change : row.trend()));
        grid.put("Volume", compact(row == null ? 0 : row.volume()));
        grid.put("Leader", this.leader);
        grid.put("Leader value", compact(this.leaderValue));
        grid.put("Confidence", this.confidence + "%");
        grid.put("Source", this.authoritative ? "server exact" : "mixed estimate");
        String ohlc = latestOhlcSummary(row);

        if (!ohlc.isBlank()) {
            grid.put("OHLC", ohlc);
        }

        return grid;
    }

    public int stockPortfolioAccentColor() {
        if ("block".equals(this.domain)) {
            return 0xFF8FC5FF;
        }

        if ("resource".equals(this.domain)) {
            return 0xFFE8C86F;
        }

        if ("entity".equals(this.domain) || "combat".equals(this.domain)) {
            return 0xFFE06A6A;
        }

        if ("food".equals(this.domain)) {
            return 0xFFE6A15C;
        }

        if ("trade".equals(this.domain)) {
            return 0xFFB7A8FF;
        }

        if ("equipment".equals(this.domain)) {
            return 0xFF8FD5D0;
        }

        return 0xFFB8C7D6;
    }

    public int[] stockPortfolioGraphPoints() {
        KoilGlobalActivityRow row = safeChartRow();
        return row == null ? new int[0] : row.points();
    }

    public int[] stockPortfolioGraphPointsView() {
        KoilGlobalActivityRow row = safeChartRow();
        return row == null ? new int[0] : row.pointsView();
    }

    public int[] stockPortfolioCandleOpenView() {
        KoilGlobalActivityRow row = safeChartRow();
        return row == null ? new int[0] : row.candleOpenView();
    }

    public int[] stockPortfolioCandleHighView() {
        KoilGlobalActivityRow row = safeChartRow();
        return row == null ? new int[0] : row.candleHighView();
    }

    public int[] stockPortfolioCandleLowView() {
        KoilGlobalActivityRow row = safeChartRow();
        return row == null ? new int[0] : row.candleLowView();
    }

    public int[] stockPortfolioCandleCloseView() {
        KoilGlobalActivityRow row = safeChartRow();
        return row == null ? new int[0] : row.candleCloseView();
    }

    public int[] stockPortfolioMovingAverageView() {
        KoilGlobalActivityRow row = safeChartRow();
        return row == null ? new int[0] : row.movingAverageView();
    }

    public String stockPortfolioSparkline() {
        return sparkline(stockPortfolioGraphPointsView(), 18);
    }

    public String stockPortfolioSparkline(int width) {
        return sparkline(stockPortfolioGraphPointsView(), width);
    }

    private KoilGlobalActivityRow safeChartRow() {
        return this.chartRow;
    }

    public String compactComponentSummary() {
        if (this.components.isEmpty()) {
            return "no component data";
        }

        StringBuilder builder = new StringBuilder();
        int count = 0;

        for (Map.Entry<String, Integer> entry : sortedComponentEntries(this.components)) {
            if (count >= 4) {
                builder.append(" +").append(this.components.size() - count).append(" more");
                break;
            }

            if (builder.length() > 0) {
                builder.append("  |  ");
            }

            builder.append(titleCase(entry.getKey())).append(": ").append(compact(entry.getValue()));
            count++;
        }

        return builder.length() == 0 ? "no component data" : builder.toString();
    }

    public List<String> componentTooltipLines(int limit) {
        List<String> lines = new ArrayList<>();

        if (this.components.isEmpty()) {
            lines.add("Components: none");
            return lines;
        }

        lines.add("Components:");
        if ("entity".equals(this.domain)) {
            int killed = this.components.getOrDefault("killed", 0);
            int killedBy = this.components.getOrDefault("killed by entity", 0) + this.components.getOrDefault("global killed by entity", 0) + this.components.getOrDefault("deaths", 0);
            lines.add("- Kill/death ratio: " + killed + " killed / " + killedBy + " killed by");
        }
        int count = 0;

        for (Map.Entry<String, Integer> entry : sortedComponentEntries(this.components)) {
            if (count >= Math.max(1, limit)) {
                lines.add("+ " + (this.components.size() - count) + " more component groups");
                break;
            }

            lines.add("- " + titleCase(entry.getKey()) + ": " + entry.getValue());
            count++;
        }

        return lines;
    }

    public static Map<String, List<KoilGlobalMarketViewRow>> buildHierarchy(List<KoilGlobalActivityRow> rows, String query) {
        List<ParsedRow> parsed = parsePreferredRows(rows);
        Map<String, Group> headGroups = new LinkedHashMap<>();
        Map<String, Group> subGroups = new LinkedHashMap<>();
        List<KoilGlobalMarketViewRow> raw = new ArrayList<>();
        List<KoilGlobalMarketViewRow> dev = new ArrayList<>();

        for (ParsedRow parsedRow : parsed) {
            if (parsedRow.developer) {
                dev.add(rawView(parsedRow, "dev"));
                continue;
            }

            Group head = headGroups.computeIfAbsent(parsedRow.domain, key -> new Group("head", key, parsedRow.domain, "all", "combined"));
            head.add(parsedRow);
            Group sub = subGroups.computeIfAbsent(parsedRow.domain + "/" + parsedRow.subjectKey, key -> new Group("sub", key, parsedRow.domain, parsedRow.subjectKey, "combined"));
            sub.add(parsedRow);
            raw.add(rawView(parsedRow, "raw"));
        }

        List<KoilGlobalMarketViewRow> head = buildGroups(headGroups.values());
        List<KoilGlobalMarketViewRow> sub = buildGroups(subGroups.values());
        sortViewRows(head);
        sortViewRows(sub);
        sortViewRows(raw);
        sortViewRows(dev);
        Map<String, List<KoilGlobalMarketViewRow>> result = new LinkedHashMap<>();
        result.put("head", filter(head, query));
        result.put("sub", filter(sub, query));
        result.put("raw", filter(raw, query));
        result.put("dev", filter(dev, query));
        return result;
    }

    private static List<KoilGlobalMarketViewRow> buildGroups(Collection<Group> groups) {
        List<KoilGlobalMarketViewRow> rows = new ArrayList<>();

        for (Group group : groups) {
            rows.add(group.toView());
        }

        return rows;
    }

    private static KoilGlobalMarketViewRow rawView(ParsedRow parsed, String tier) {
        Map<String, Integer> components = new LinkedHashMap<>();
        components.put(componentKey(parsed.action, parsed.metric), Math.max(0, parsed.row.value()));
        String title = rawTitle(parsed);
        String description = "Exact signal from " + parsed.metric + " for " + domainTitle(parsed.domain).toLowerCase(Locale.ROOT) + " data.";
        KoilGlobalActivityRow source = parsed.row;
        KoilGlobalActivityRow chartRow = new KoilGlobalActivityRow("market", tier + "/" + parsed.metric, parsed.metric, title, source.value(), source.leader(), source.leaderValue(), source.pointsView(), source.source(), source.base(), source.change(), source.volume(), source.contextKey(), source.confidence(), source.authoritative());
        return new KoilGlobalMarketViewRow(tier, tier + "/" + parsed.metric, parsed.domain, parsed.subjectKey, parsed.action, title, description, source.value(), source.leader(), source.leaderValue(), source.confidence(), source.authoritative(), components, Collections.singletonList(parsed.metric), chartRow);
    }

    private static List<ParsedRow> parsePreferredRows(List<KoilGlobalActivityRow> rows) {
        Map<String, KoilGlobalActivityRow> activity = new LinkedHashMap<>();
        Map<String, KoilGlobalActivityRow> market = new LinkedHashMap<>();

        if (rows != null) {
            for (KoilGlobalActivityRow row : rows) {
                if (row == null || row.metric() == null || row.metric().isBlank() || row.value() <= 0) {
                    continue;
                }

                String key = normalizeMetric(row.metric());

                if (row.market()) {
                    market.putIfAbsent(key, row);
                } else {
                    activity.put(key, row);
                }
            }
        }

        List<ParsedRow> parsed = new ArrayList<>();
        Set<String> used = new HashSet<>();

        for (Map.Entry<String, KoilGlobalActivityRow> entry : activity.entrySet()) {
            parsed.add(parse(entry.getValue()));
            used.add(entry.getKey());
        }

        for (Map.Entry<String, KoilGlobalActivityRow> entry : market.entrySet()) {
            if (!used.contains(entry.getKey())) {
                parsed.add(parse(entry.getValue()));
            }
        }

        parsed = removeDuplicateAggregateRows(parsed);

        parsed.sort((a, b) -> {
            int priority = Integer.compare(domainPriority(b.domain), domainPriority(a.domain));

            if (priority != 0) {
                return priority;
            }

            return Integer.compare(b.row.value(), a.row.value());
        });
        return parsed;
    }

    private static List<ParsedRow> removeDuplicateAggregateRows(List<ParsedRow> source) {
        List<ParsedRow> result = new ArrayList<>();
        Map<String, List<ParsedRow>> details = new LinkedHashMap<>();

        for (ParsedRow row : source) {
            String key = aggregateKeyForDetail(row.metric);

            if (!key.isBlank()) {
                details.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
        }

        for (ParsedRow row : source) {
            String key = aggregateKeyForAggregate(row.metric);

            if (key.isBlank()) {
                result.add(row);
                continue;
            }

            List<ParsedRow> matchingDetails = details.getOrDefault(key, Collections.emptyList());

            if (matchingDetails.isEmpty()) {
                result.add(row);
                continue;
            }

            int aggregateValue = Math.max(0, row.row.value());
            int detailValue = 0;

            for (ParsedRow detail : matchingDetails) {
                detailValue += Math.max(0, detail.row.value());
            }

            if (aggregateValue <= detailValue) {
                continue;
            }

            int residualValue = aggregateValue - detailValue;
            int[] residualPoints = subtractAligned(row.row.pointsView(), matchingDetails);
            int lastPoint = residualPoints.length == 0 ? residualValue : Math.max(0, residualPoints[residualPoints.length - 1]);

            if (lastPoint <= 0 && residualValue <= 0) {
                continue;
            }

            int change = residualPoints.length < 2 ? 0 : residualPoints[residualPoints.length - 1] - residualPoints[residualPoints.length - 2];
            String residualMetric = residualMetricForAggregate(row.metric);
            KoilGlobalActivityRow residualRow = new KoilGlobalActivityRow(row.row.type(), row.row.id() + "/residual", residualMetric, row.row.title() + " Other", Math.max(residualValue, lastPoint), row.row.leader(), Math.min(row.row.leaderValue(), Math.max(residualValue, lastPoint)), residualPoints, row.row.source() + " residual", row.row.base(), change, Math.max(0, change), row.row.contextKey(), row.row.confidence(), row.row.authoritative());
            result.add(parse(residualRow));
        }

        return result;
    }

    private static int[] subtractAligned(int[] aggregatePoints, List<ParsedRow> details) {
        int length = aggregatePoints == null ? 0 : aggregatePoints.length;

        for (ParsedRow detail : details) {
            length = Math.max(length, detail.row.pointsView().length);
        }

        if (length <= 0) {
            return new int[0];
        }

        int[] result = new int[length];

        for (int i = 0; i < length; i++) {
            int value = alignedValue(aggregatePoints, i, length);

            for (ParsedRow detail : details) {
                value -= alignedValue(detail.row.pointsView(), i, length);
            }

            result[i] = Math.max(0, value);
        }

        return result;
    }

    private static String aggregateKeyForAggregate(String metric) {
        String clean = metric == null ? "" : metric;

        if (clean.equals("blocks_broken") || clean.equals("packet/blocks_broken")) {
            return clean.startsWith("packet/") ? "packet:block:broken" : "block:broken";
        }

        if (clean.equals("blocks_placed") || clean.equals("packet/blocks_placed")) {
            return clean.startsWith("packet/") ? "packet:block:placed" : "block:placed";
        }

        if (clean.equals("items_used")) {
            return "item:used";
        }

        if (clean.equals("items_crafted")) {
            return "item:crafted";
        }

        if (clean.equals("items_picked_up")) {
            return "item:picked_up";
        }

        if (clean.equals("items_dropped")) {
            return "item:dropped";
        }

        if (clean.equals("food_eaten")) {
            return "food:eaten";
        }

        if (clean.equals("mobs_killed")) {
            return "entity:killed";
        }

        if (clean.equals("entity_deaths") || clean.equals("killed_by_entities")) {
            return "entity:killed_by";
        }

        return "";
    }

    private static String aggregateKeyForDetail(String metric) {
        String clean = metric == null ? "" : metric;

        if (clean.startsWith("packet/block_broken/")) {
            return "packet:block:broken";
        }

        if (clean.startsWith("packet/block_place_intent/")) {
            return "packet:block:placed";
        }

        if (clean.startsWith("block_broken/")) {
            return "block:broken";
        }

        if (clean.startsWith("block_placed/") || clean.startsWith("block_place_intent/")) {
            return "block:placed";
        }

        if (clean.startsWith("item_used/") || clean.startsWith("item_use_intent/")) {
            return "item:used";
        }

        if (clean.startsWith("item_crafted/")) {
            return "item:crafted";
        }

        if (clean.startsWith("item_picked_up/")) {
            return "item:picked_up";
        }

        if (clean.startsWith("item_dropped/")) {
            return "item:dropped";
        }

        if (clean.startsWith("food_eaten/") || clean.startsWith("food_use_intent/")) {
            return "food:eaten";
        }

        if (clean.startsWith("mob_killed/") || clean.startsWith("entity_killed/")) {
            return "entity:killed";
        }

        if (clean.startsWith("killed_by/") || clean.startsWith("mob_death/")) {
            return "entity:killed_by";
        }

        return "";
    }

    private static String residualMetricForAggregate(String metric) {
        String clean = metric == null ? "" : metric;

        if (clean.equals("blocks_broken") || clean.equals("packet/blocks_broken")) {
            return clean.startsWith("packet/") ? "packet/block_broken/other" : "block_broken/other";
        }

        if (clean.equals("blocks_placed") || clean.equals("packet/blocks_placed")) {
            return clean.startsWith("packet/") ? "packet/block_place_intent/other" : "block_placed/other";
        }

        if (clean.equals("items_used")) {
            return "item_used/other";
        }

        if (clean.equals("items_crafted")) {
            return "item_crafted/other";
        }

        if (clean.equals("items_picked_up")) {
            return "item_picked_up/other";
        }

        if (clean.equals("items_dropped")) {
            return "item_dropped/other";
        }

        if (clean.equals("food_eaten")) {
            return "food_eaten/other";
        }

        if (clean.equals("mobs_killed")) {
            return "mob_killed/other";
        }

        if (clean.equals("entity_deaths") || clean.equals("killed_by_entities")) {
            return "killed_by/other";
        }

        return clean + "/other";
    }

    private static ParsedRow parse(KoilGlobalActivityRow row) {
        String metric = normalizeMetric(row.metric());
        String classifiedMetric = metric.startsWith("packet/") ? metric.substring("packet/".length()) : metric;
        String domain = "developer";
        String action = "activity";
        String subject = "unknown";
        boolean developer = false;

        if (classifiedMetric.startsWith("processing/fuel_burned/")) {
            action = "fuel burned";
            subject = subjectAfter(classifiedMetric, "processing/fuel_burned/");
            domain = isResourceSubject("resource", subject, classifiedMetric) ? "resource" : "item";
        } else if (classifiedMetric.startsWith("processing/smelt_input/")) {
            action = "processed input";
            subject = subjectAfter(classifiedMetric, "processing/smelt_input/");
            domain = isResourceSubject("resource", subject, classifiedMetric) ? "resource" : "item";
        } else if (classifiedMetric.startsWith("processing/smelt_output/")) {
            action = "processed output";
            subject = subjectAfter(classifiedMetric, "processing/smelt_output/");
            domain = isResourceSubject("resource", subject, classifiedMetric) ? "resource" : "item";
        } else if (classifiedMetric.startsWith("processing/fuel_supported_output/")) {
            action = "fuel supported output";
            subject = firstIdentifierSubject(subjectAfter(classifiedMetric, "processing/fuel_supported_output/"));
            domain = isResourceSubject("resource", subject, classifiedMetric) ? "resource" : "item";
        } else if (classifiedMetric.startsWith("processing/brew_ingredient/")) {
            action = "brewing ingredient";
            subject = subjectAfter(classifiedMetric, "processing/brew_ingredient/");
            domain = isResourceSubject("resource", subject, classifiedMetric) ? "resource" : isFoodMetric(subject) ? "food" : "item";
        } else if (classifiedMetric.startsWith("processing/brew_fuel/")) {
            action = "brewing fuel";
            subject = subjectAfter(classifiedMetric, "processing/brew_fuel/");
            domain = isResourceSubject("resource", subject, classifiedMetric) ? "resource" : "item";
        } else if (classifiedMetric.startsWith("processing/brew_output/")) {
            action = "brewing output";
            subject = subjectAfter(classifiedMetric, "processing/brew_output/");
            domain = isResourceSubject("resource", subject, classifiedMetric) ? "resource" : isFoodMetric(subject) ? "food" : "item";
        } else if (classifiedMetric.startsWith("processing/compost_input/")) {
            action = "compost input";
            subject = subjectAfter(classifiedMetric, "processing/compost_input/");
            domain = isResourceSubject("resource", subject, classifiedMetric) ? "resource" : isFoodMetric(subject) ? "food" : "item";
        } else if (classifiedMetric.startsWith("processing/compost_level_gain/")) {
            domain = "developer";
            action = "compost level gain";
            subject = subjectAfter(classifiedMetric, "processing/compost_level_gain/");
            developer = true;
        } else if (classifiedMetric.startsWith("processing/station_interact/")) {
            domain = "developer";
            action = "processing station use";
            subject = subjectAfter(classifiedMetric, "processing/station_interact/");
            developer = true;
        } else if (classifiedMetric.startsWith("container_in/") || classifiedMetric.startsWith("container_out/")) {
            boolean input = classifiedMetric.startsWith("container_in/");
            action = input ? "container in" : "container out";
            subject = containerSubject(classifiedMetric);
            String containerType = containerType(classifiedMetric);
            domain = "block".equals(containerType) ? "block" : isResourceSubject("resource", subject, classifiedMetric) ? "resource" : isFoodMetric(subject) ? "food" : "item";
        } else if (classifiedMetric.startsWith("station/")) {
            String stationAction = stationAction(classifiedMetric);
            action = stationActionLabel(stationAction);
            subject = stationSubject(classifiedMetric);
            domain = stationAction.contains("interact") || subject.equals("unknown") ? "developer" : isResourceSubject("resource", subject, classifiedMetric) ? "resource" : isFoodMetric(subject) ? "food" : "item";
            developer = domain.equals("developer");
        } else if (classifiedMetric.startsWith("resource/")) {
            action = "resource activity";
            subject = subjectAfter(classifiedMetric, "resource/");
            domain = isResourceSubject("resource", subject, classifiedMetric) ? "resource" : "item";
        } else if (classifiedMetric.startsWith("block_broken/")) {
            domain = "block";
            action = "broken";
            subject = subjectAfter(classifiedMetric, "block_broken/");
        } else if (classifiedMetric.startsWith("block_place_intent/")) {
            domain = "block";
            action = "placed";
            subject = subjectAfter(classifiedMetric, "block_place_intent/");
        } else if (classifiedMetric.startsWith("block_placed/")) {
            domain = "block";
            action = "placed";
            subject = subjectAfter(classifiedMetric, "block_placed/");
        } else if (classifiedMetric.startsWith("block_used/") || classifiedMetric.startsWith("block_use/")) {
            domain = "block";
            action = "used";
            subject = subjectAfterAny(classifiedMetric, "block_used/", "block_use/");
        } else if (classifiedMetric.equals("blocks_broken") || classifiedMetric.equals("block_breaks")) {
            domain = "block";
            action = "broken";
            subject = "all_blocks";
        } else if (classifiedMetric.equals("blocks_placed") || classifiedMetric.equals("block_places")) {
            domain = "block";
            action = "placed";
            subject = "all_blocks";
        } else if (classifiedMetric.startsWith("item_use_intent/")) {
            domain = "item";
            action = "used";
            subject = subjectAfter(classifiedMetric, "item_use_intent/");
        } else if (classifiedMetric.startsWith("item_used/")) {
            domain = "item";
            action = "used";
            subject = subjectAfter(classifiedMetric, "item_used/");
        } else if (classifiedMetric.startsWith("item_crafted/")) {
            domain = "item";
            action = "crafted";
            subject = subjectAfter(classifiedMetric, "item_crafted/");
        } else if (classifiedMetric.startsWith("item_picked_up/")) {
            domain = "item";
            action = "picked up";
            subject = subjectAfter(classifiedMetric, "item_picked_up/");
        } else if (classifiedMetric.startsWith("item_dropped/")) {
            domain = "item";
            action = "dropped";
            subject = subjectAfter(classifiedMetric, "item_dropped/");
        } else if (classifiedMetric.startsWith("item_traded/")) {
            domain = "trade";
            action = "traded";
            subject = subjectAfter(classifiedMetric, "item_traded/");
        } else if (classifiedMetric.startsWith("food_eaten/") || classifiedMetric.startsWith("item_eaten/") || classifiedMetric.startsWith("food_use_intent/")) {
            domain = "food";
            action = classifiedMetric.startsWith("food_use_intent/") ? "use intent" : "eaten";
            subject = classifiedMetric.startsWith("food_use_intent/") ? subjectAfter(classifiedMetric, "food_use_intent/") : subjectAfterAny(classifiedMetric, "food_eaten/", "item_eaten/");
        } else if (classifiedMetric.startsWith("mainhand/") || classifiedMetric.startsWith("self_mainhand/") || classifiedMetric.startsWith("client_observed/mainhand/") || classifiedMetric.startsWith("client_observed/self_mainhand/")) {
            domain = "equipment";
            action = "main-hand demand";
            subject = subjectAfterAny(classifiedMetric.replace("client_observed/", ""), "mainhand/", "self_mainhand/");
        } else if (classifiedMetric.startsWith("offhand/") || classifiedMetric.startsWith("self_offhand/") || classifiedMetric.startsWith("client_observed/offhand/") || classifiedMetric.startsWith("client_observed/self_offhand/")) {
            domain = "equipment";
            action = "off-hand demand";
            subject = subjectAfterAny(classifiedMetric.replace("client_observed/", ""), "offhand/", "self_offhand/");
        } else if (classifiedMetric.startsWith("mob_killed/") || classifiedMetric.startsWith("entity_killed/")) {
            domain = "entity";
            action = "killed";
            subject = subjectAfterAny(classifiedMetric, "mob_killed/", "entity_killed/");
        } else if (classifiedMetric.startsWith("mob_death/") || classifiedMetric.startsWith("killed_by/")) {
            domain = "entity";
            action = "killed by entity";
            subject = subjectAfterAny(classifiedMetric, "mob_death/", "killed_by/");
        } else if (classifiedMetric.equals("entity_deaths") || classifiedMetric.equals("killed_by_entities")) {
            domain = "entity";
            action = "global killed by entity";
            subject = "all_entities";
        } else if (classifiedMetric.contains("mob") || classifiedMetric.contains("entity")) {
            domain = "entity";
            action = classifiedMetric.contains("death") ? "deaths" : classifiedMetric.contains("kill") ? "killed" : "activity";
            subject = "all_entities";
        } else if (classifiedMetric.contains("damage") || classifiedMetric.contains("death") || classifiedMetric.contains("kill")) {
            domain = "combat";
            action = classifiedMetric.contains("death") ? "deaths" : classifiedMetric.contains("kill") ? "kills" : "damage";
            subject = "all_combat";
        } else if (classifiedMetric.contains("trade") || classifiedMetric.contains("market") || classifiedMetric.contains("buy") || classifiedMetric.contains("sell")) {
            domain = "trade";
            action = "traded";
            subject = "all_trades";
        } else if (isFoodMetric(classifiedMetric)) {
            domain = "food";
            action = classifiedMetric.contains("use_intent") ? "use intent" : "eaten";
            subject = "all_food";
        } else if (classifiedMetric.contains("item")) {
            domain = "item";
            action = "activity";
            subject = "all_items";
        } else if (classifiedMetric.contains("block")) {
            domain = "block";
            action = "activity";
            subject = "all_blocks";
        } else {
            developer = true;
        }

        if (!developer && isResourceSubject(domain, subject, classifiedMetric)) {
            domain = "resource";
        }

        subject = cleanSubject(subject, domain);
        return new ParsedRow(row, metric, domain, subject, action, developer);
    }

    private static List<KoilGlobalMarketViewRow> filter(List<KoilGlobalMarketViewRow> rows, String query) {
        String cleanQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        if (cleanQuery.isBlank()) {
            return rows;
        }

        List<KoilGlobalMarketViewRow> filtered = new ArrayList<>();

        for (KoilGlobalMarketViewRow row : rows) {
            StringBuilder haystack = new StringBuilder();
            haystack.append(row.title).append(' ').append(row.description).append(' ').append(row.domain).append(' ').append(row.subject).append(' ').append(row.action).append(' ').append(row.leader);

            for (String metric : row.rawMetrics) {
                haystack.append(' ').append(metric);
            }

            for (Map.Entry<String, Integer> entry : row.components.entrySet()) {
                haystack.append(' ').append(entry.getKey()).append(' ').append(entry.getValue());
            }

            if (haystack.toString().toLowerCase(Locale.ROOT).contains(cleanQuery)) {
                filtered.add(row);
            }
        }

        return filtered;
    }

    private static void sortViewRows(List<KoilGlobalMarketViewRow> rows) {
        rows.sort((a, b) -> {
            int tier = Integer.compare(tierPriority(b.tier), tierPriority(a.tier));

            if (tier != 0) {
                return tier;
            }

            int domain = Integer.compare(domainPriority(b.domain), domainPriority(a.domain));

            if (domain != 0) {
                return domain;
            }

            int value = Integer.compare(b.value, a.value);

            if (value != 0) {
                return value;
            }

            return a.title.compareToIgnoreCase(b.title);
        });
    }

    private static int tierPriority(String tier) {
        if ("head".equals(tier)) {
            return 400;
        }

        if ("sub".equals(tier)) {
            return 300;
        }

        if ("raw".equals(tier)) {
            return 200;
        }

        return 100;
    }

    private static int domainPriority(String domain) {
        if ("block".equals(domain)) {
            return 100;
        }

        if ("item".equals(domain)) {
            return 90;
        }

        if ("resource".equals(domain)) {
            return 85;
        }

        if ("entity".equals(domain)) {
            return 80;
        }

        if ("combat".equals(domain)) {
            return 70;
        }

        if ("food".equals(domain)) {
            return 60;
        }

        if ("equipment".equals(domain)) {
            return 50;
        }

        if ("trade".equals(domain)) {
            return 40;
        }

        return 10;
    }


    private static String headDescription(String domain) {
        if ("entity".equals(domain)) {
            return "Combined entity market using global entity kills against global killed-by-entity deaths, so deaths can pull the market down.";
        }

        if ("resource".equals(domain)) {
            return "Combined valuable-resource market using mining, collection, crafting, fuel, smelting, processing input, and processing output signals.";
        }

        return "Combined summary of all " + domainTitle(domain).toLowerCase(Locale.ROOT) + " markets and raw signals.";
    }

    private static String subDescription(String domain, String subject) {
        if ("entity".equals(domain)) {
            return "Kill/death market for " + subjectTitle(domain, subject) + ", using kills against killed-by-this-entity deaths.";
        }

        if ("resource".equals(domain)) {
            return "Resource market for " + subjectTitle(domain, subject) + ", combining supply, processing, fuel, output, and demand signals.";
        }

        return "Combined summary for " + subjectTitle(domain, subject) + " across all matching raw signals.";
    }

    private static String rawTitle(ParsedRow parsed) {
        String subject = subjectTitle(parsed.domain, parsed.subjectKey);
        String action = titleCase(parsed.action);

        if ("block".equals(parsed.domain)) {
            return subject + " " + action;
        }

        if ("item".equals(parsed.domain)) {
            return subject + " " + action;
        }

        if ("resource".equals(parsed.domain)) {
            return subject + " Resource " + action;
        }

        if ("entity".equals(parsed.domain)) {
            return subject + " " + action;
        }

        if ("combat".equals(parsed.domain)) {
            return "Combat " + action;
        }

        if ("food".equals(parsed.domain)) {
            return subject + " " + action;
        }

        if ("equipment".equals(parsed.domain)) {
            return subject + " " + action;
        }

        if ("trade".equals(parsed.domain)) {
            return subject + " " + action;
        }

        return "Dev Market: " + titleCase(parsed.metric.replace('/', ' '));
    }

    private static String headTitle(String domain) {
        return domainTitle(domain) + " Market";
    }

    private static String subTitle(String domain, String subject) {
        return subjectTitle(domain, subject) + " Market";
    }

    private static String domainTitle(String domain) {
        if ("block".equals(domain)) {
            return "Block";
        }

        if ("item".equals(domain)) {
            return "Item";
        }

        if ("resource".equals(domain)) {
            return "Resource";
        }

        if ("entity".equals(domain)) {
            return "Entity";
        }

        if ("combat".equals(domain)) {
            return "Combat";
        }

        if ("food".equals(domain)) {
            return "Food";
        }

        if ("equipment".equals(domain)) {
            return "Equipment";
        }

        if ("trade".equals(domain)) {
            return "Trade";
        }

        return "Developer";
    }

    private static String subjectTitle(String domain, String subject) {
        String clean = subject == null || subject.isBlank() ? "unknown" : subject;

        if (clean.startsWith("all_")) {
            return titleCase(clean.replace('_', ' '));
        }

        String title = titleCase(clean.replace('/', ' ').replace('_', ' '));

        if ("block".equals(domain) && !title.toLowerCase(Locale.ROOT).endsWith("block")) {
            return title + " Block";
        }

        if ("item".equals(domain) && !title.toLowerCase(Locale.ROOT).endsWith("item")) {
            return title + " Item";
        }

        if ("resource".equals(domain) && !title.toLowerCase(Locale.ROOT).endsWith("resource")) {
            return title + " Resource";
        }

        if ("entity".equals(domain) && !title.toLowerCase(Locale.ROOT).endsWith("entity")) {
            return title + " Entity";
        }

        if ("food".equals(domain) && !title.toLowerCase(Locale.ROOT).endsWith("food")) {
            return title + " Food";
        }

        return title;
    }

    private static List<Map.Entry<String, Integer>> sortedComponentEntries(Map<String, Integer> components) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(components.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return entries;
    }

    private static String normalizeMetric(String metric) {
        return metric == null ? "" : metric.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static String subjectAfter(String metric, String prefix) {
        return metric.length() <= prefix.length() ? "unknown" : metric.substring(prefix.length());
    }

    private static String subjectAfterAny(String metric, String first, String second) {
        if (metric.startsWith(first)) {
            return subjectAfter(metric, first);
        }

        if (metric.startsWith(second)) {
            return subjectAfter(metric, second);
        }

        return "unknown";
    }

    private static String stationAction(String metric) {
        String safe = metric == null ? "" : metric;
        String[] parts = safe.split("/");
        return parts.length >= 2 ? parts[1] : "unknown";
    }

    private static String stationSubject(String metric) {
        String safe = metric == null ? "" : metric;
        String[] parts = safe.split("/");

        if (parts.length >= 5) {
            return parts[parts.length - 2] + "/" + parts[parts.length - 1];
        }

        return "unknown";
    }

    private static String containerType(String metric) {
        String safe = metric == null ? "" : metric;
        String[] parts = safe.split("/");
        return parts.length >= 2 && !parts[1].isBlank() ? parts[1] : "item";
    }

    private static String containerSubject(String metric) {
        String safe = metric == null ? "" : metric;
        String[] parts = safe.split("/");

        if (parts.length >= 2) {
            return parts[parts.length - 2] + "/" + parts[parts.length - 1];
        }

        return "unknown";
    }

    private static String stationActionLabel(String action) {
        String safe = action == null ? "unknown" : action.toLowerCase(Locale.ROOT);

        if (safe.contains("output")) {
            return "station output";
        }

        if (safe.contains("input")) {
            return "station input";
        }

        if (safe.contains("brew")) {
            return "brewing";
        }

        if (safe.contains("smith")) {
            return "smithing";
        }

        if (safe.contains("stonecut")) {
            return "stonecutting";
        }

        if (safe.contains("craft")) {
            return "crafting";
        }

        if (safe.contains("storage")) {
            return "storage interaction";
        }

        return safe.replace('_', ' ');
    }

    private static String lastIdentifierSubject(String subject) {
        String safe = subject == null ? "unknown" : subject;
        String[] parts = safe.split("/");

        if (parts.length >= 4) {
            return parts[parts.length - 2] + "/" + parts[parts.length - 1];
        }

        if (parts.length >= 2) {
            return parts[parts.length - 2] + "/" + parts[parts.length - 1];
        }

        return safe;
    }

    private static String firstIdentifierSubject(String subject) {
        String safe = subject == null ? "unknown" : subject;
        String[] parts = safe.split("/");

        if (parts.length >= 2) {
            return parts[0] + "/" + parts[1];
        }

        return safe;
    }

    private static boolean isResourceSubject(String domain, String subject, String metric) {
        if ("food".equals(domain) || "entity".equals(domain) || "combat".equals(domain) || "equipment".equals(domain) || "trade".equals(domain)) {
            return false;
        }

        String safeSubject = safe(subject, "").toLowerCase(Locale.ROOT);
        String path = safeSubject.contains("/") ? safeSubject.substring(safeSubject.lastIndexOf('/') + 1) : safeSubject;

        if (path.isBlank() || isFinishedResourceProductName(path)) {
            return false;
        }

        return isRawResourceName(path);
    }

    private static boolean isRawResourceName(String path) {
        String safe = path == null ? "" : path.toLowerCase(Locale.ROOT);

        return safe.equals("coal")
                || safe.equals("charcoal")
                || safe.equals("flint")
                || safe.equals("clay_ball")
                || safe.equals("amethyst_shard")
                || safe.equals("quartz")
                || safe.equals("nether_quartz")
                || safe.equals("redstone")
                || safe.equals("glowstone_dust")
                || safe.equals("gunpowder")
                || safe.equals("blaze_powder")
                || safe.equals("prismarine_shard")
                || safe.equals("prismarine_crystals")
                || safe.equals("echo_shard")
                || safe.equals("ancient_debris")
                || safe.equals("netherite_scrap")
                || safe.equals("iron_ingot")
                || safe.equals("gold_ingot")
                || safe.equals("copper_ingot")
                || safe.equals("netherite_ingot")
                || safe.equals("iron_nugget")
                || safe.equals("gold_nugget")
                || safe.equals("diamond")
                || safe.equals("emerald")
                || safe.equals("lapis_lazuli")
                || safe.endsWith("_ore")
                || safe.endsWith("_ingot")
                || safe.endsWith("_nugget")
                || safe.endsWith("_gem")
                || safe.endsWith("_dust")
                || safe.endsWith("_crystal")
                || safe.endsWith("_crystals")
                || safe.endsWith("_shard")
                || safe.endsWith("_scrap")
                || safe.endsWith("_alloy")
                || safe.startsWith("raw_")
                || safe.endsWith("_raw")
                || safe.endsWith("_raw_material")
                || safe.endsWith("_material")
                || safe.equals("steel")
                || safe.equals("silver")
                || safe.equals("lead")
                || safe.equals("tin")
                || safe.equals("nickel")
                || safe.equals("zinc")
                || safe.equals("aluminum")
                || safe.equals("platinum")
                || safe.equals("uranium")
                || safe.equals("osmium")
                || safe.equals("bronze")
                || safe.equals("brass")
                || safe.equals("ruby")
                || safe.equals("sapphire")
                || safe.equals("steel_ingot")
                || safe.equals("silver_ingot")
                || safe.equals("lead_ingot")
                || safe.equals("tin_ingot")
                || safe.equals("nickel_ingot")
                || safe.equals("zinc_ingot")
                || safe.equals("aluminum_ingot")
                || safe.equals("platinum_ingot")
                || safe.equals("uranium_ingot")
                || safe.equals("osmium_ingot")
                || safe.equals("bronze_ingot")
                || safe.equals("brass_ingot")
                || isResourceStorageBlockName(safe);
    }

    private static boolean isResourceStorageBlockName(String path) {
        return path.equals("coal_block")
                || path.equals("charcoal_block")
                || path.equals("iron_block")
                || path.equals("gold_block")
                || path.equals("copper_block")
                || path.equals("diamond_block")
                || path.equals("emerald_block")
                || path.equals("lapis_block")
                || path.equals("redstone_block")
                || path.equals("netherite_block")
                || path.equals("raw_iron_block")
                || path.equals("raw_gold_block")
                || path.equals("raw_copper_block")
                || path.endsWith("_ingot_block")
                || path.endsWith("_gem_block")
                || path.endsWith("_storage_block")
                || path.endsWith("_raw_block")
                || path.endsWith("_ore_block");
    }

    private static boolean isFinishedResourceProductName(String value) {
        String safe = value == null ? "" : value.toLowerCase(Locale.ROOT);
        String path = safe.contains("/") ? safe.substring(safe.lastIndexOf('/') + 1) : safe;

        return path.endsWith("_sword")
                || path.endsWith("_pickaxe")
                || path.endsWith("_axe")
                || path.endsWith("_shovel")
                || path.endsWith("_hoe")
                || path.endsWith("_helmet")
                || path.endsWith("_chestplate")
                || path.endsWith("_leggings")
                || path.endsWith("_boots")
                || path.endsWith("_horse_armor")
                || path.endsWith("_shield")
                || path.endsWith("_bow")
                || path.endsWith("_crossbow")
                || path.endsWith("_trident")
                || path.endsWith("_bucket")
                || path.endsWith("_minecart")
                || path.endsWith("_boat")
                || path.endsWith("_stairs")
                || path.endsWith("_slab")
                || path.endsWith("_wall")
                || path.endsWith("_door")
                || path.endsWith("_trapdoor")
                || path.endsWith("_fence")
                || path.endsWith("_fence_gate")
                || path.endsWith("_button")
                || path.endsWith("_pressure_plate")
                || path.endsWith("_sign")
                || path.endsWith("_hanging_sign")
                || path.endsWith("_pane")
                || path.endsWith("_bars")
                || path.endsWith("_chain")
                || path.endsWith("_rail")
                || path.endsWith("_lamp")
                || path.endsWith("_lantern")
                || path.endsWith("_bell")
                || path.endsWith("_compass")
                || path.endsWith("_clock")
                || path.endsWith("_shears")
                || path.endsWith("_rod")
                || path.endsWith("_gear")
                || path.endsWith("_plate")
                || path.endsWith("_wire")
                || path.endsWith("_cable")
                || path.contains("sword_")
                || path.contains("pickaxe_")
                || path.contains("helmet_")
                || path.contains("chestplate_")
                || path.contains("leggings_")
                || path.contains("boots_")
                || path.contains("armor")
                || path.contains("tool")
                || path.contains("machine")
                || path.contains("generator")
                || path.contains("processor")
                || path.contains("furnace")
                || path.contains("smelter")
                || path.contains("crusher")
                || path.contains("grinder")
                || path.contains("hopper")
                || path.contains("barrel")
                || path.contains("chest")
                || path.contains("crafting_table")
                || path.contains("anvil")
                || path.contains("beacon");
    }

    private static boolean isFoodMetric(String metric) {
        String safe = metric == null ? "" : metric.toLowerCase(Locale.ROOT);
        return safe.startsWith("food_") || safe.startsWith("food/") || safe.contains("/food") || safe.contains("food_eaten") || safe.contains("item_eaten") || safe.contains("eaten");
    }

    private static String cleanSubject(String subject, String domain) {
        String clean = subject == null ? "unknown" : subject.trim().toLowerCase(Locale.ROOT);

        if (clean.isBlank()) {
            return "unknown";
        }

        String[] parts = clean.split("/");

        if (parts.length >= 2 && parts[0].matches("[a-z0-9_.-]+")) {
            clean = parts[parts.length - 1];
        }

        if ("all".equals(clean)) {
            return "all_" + domain + "s";
        }

        return clean;
    }


    private static String componentKey(String action, String metric) {
        String safeAction = safe(action, "activity");
        String safeMetric = safe(metric, "unknown");
        return safeAction + " | " + safeMetric;
    }

    private static int[] marketPressurePoints(List<ParsedRow> rows, String domain) {
        int length = 0;

        for (ParsedRow row : rows) {
            length = Math.max(length, row.row.pointsView().length);
        }

        if (length <= 0) {
            int net = 100;

            for (ParsedRow row : rows) {
                net += marketPressureDirection(row) * Math.max(0, row.row.value());
            }

            return new int[]{Math.max(1, net)};
        }

        int[] result = new int[length];
        int current = 100;

        for (int i = 0; i < length; i++) {
            int delta = 0;

            for (ParsedRow row : rows) {
                int[] points = row.row.pointsView();

                if (points.length == 0) {
                    continue;
                }

                int value = alignedValue(points, i, length);
                int previous = i <= 0 ? 0 : alignedValue(points, i - 1, length);
                int movement = Math.max(0, value - previous);
                delta += marketPressureDirection(row) * movement;
            }

            current = Math.max(1, current + delta);
            result[i] = current;
        }

        return result;
    }

    private static int marketPressureDirection(ParsedRow row) {
        String metric = safe(row.metric, "").toLowerCase(Locale.ROOT);
        String action = safe(row.action, "").toLowerCase(Locale.ROOT);
        String key = action + " " + metric;
        String category = KoilMarketLedger.categoryForMetric(key);

        if (!KoilMarketLedger.shouldAffectPrice(category)) {
            return 0;
        }

        if (KoilMarketLedger.isSupply(category, key)) {
            return -1;
        }

        if (KoilMarketLedger.isNegative(category, key)) {
            return 1;
        }

        if (KoilMarketLedger.isDemand(category, key)) {
            return 1;
        }

        return 0;
    }

    private static int[] sumPoints(List<ParsedRow> rows) {
        int length = 0;

        for (ParsedRow row : rows) {
            length = Math.max(length, row.row.pointsView().length);
        }

        if (length <= 0) {
            int total = 0;

            for (ParsedRow row : rows) {
                total += Math.max(0, row.row.value());
            }

            return new int[]{total};
        }

        int[] result = new int[length];

        for (ParsedRow row : rows) {
            int[] points = row.row.pointsView();

            if (points.length == 0) {
                continue;
            }

            int offset = length - points.length;

            for (int i = 0; i < length; i++) {
                int sourceIndex = i - offset;

                if (sourceIndex >= 0 && sourceIndex < points.length) {
                    result[i] += Math.max(0, points[sourceIndex]);
                }
            }
        }

        return result;
    }


    private static int[] entityKillDeathRatioPoints(List<ParsedRow> rows) {
        int length = 0;

        for (ParsedRow row : rows) {
            length = Math.max(length, row.row.pointsView().length);
        }

        if (length <= 0) {
            int kills = 0;
            int killedBy = 0;

            for (ParsedRow row : rows) {
                int value = Math.max(0, row.row.value());

                if (isEntityDeathAction(row.action, row.metric)) {
                    killedBy += value;
                } else if (isEntityKillAction(row.action, row.metric)) {
                    kills += value;
                }
            }

            return new int[]{entityRatioIndex(kills, killedBy)};
        }

        int[] result = new int[length];

        for (int i = 0; i < length; i++) {
            int kills = 0;
            int killedBy = 0;
            int neutral = 0;

            for (ParsedRow row : rows) {
                int[] points = row.row.pointsView();

                if (points.length == 0) {
                    continue;
                }

                int value = Math.max(0, alignedValue(points, i, length));

                if (isEntityDeathAction(row.action, row.metric)) {
                    killedBy += value;
                } else if (isEntityKillAction(row.action, row.metric)) {
                    kills += value;
                } else {
                    neutral += value;
                }
            }

            result[i] = entityRatioIndex(kills, killedBy) + Math.round(neutral * 0.12F);
        }

        return result;
    }

    private static int alignedValue(int[] points, int targetIndex, int targetLength) {
        if (points == null || points.length == 0) {
            return 0;
        }

        int offset = Math.max(0, targetLength - points.length);
        int sourceIndex = targetIndex - offset;

        if (sourceIndex < 0) {
            return points[0];
        }

        if (sourceIndex >= points.length) {
            return points[points.length - 1];
        }

        return points[sourceIndex];
    }

    private static int entityRatioIndex(int kills, int killedBy) {
        int safeKills = Math.max(0, kills);
        int safeKilledBy = Math.max(0, killedBy);
        int total = safeKills + safeKilledBy;

        if (total <= 0) {
            return 100;
        }

        int ratioScore = Math.round((safeKills - safeKilledBy) * 100.0F / Math.max(1, total));
        int scaleScore = Math.min(40, total / 64);
        return 100 + ratioScore + scaleScore;
    }

    private static boolean isEntityKillAction(String action, String metric) {
        String safeAction = safe(action, "").toLowerCase(Locale.ROOT);
        String safeMetric = safe(metric, "").toLowerCase(Locale.ROOT);
        return safeAction.contains("kill") && !safeAction.contains("killed by") || safeMetric.startsWith("mob_killed/") || safeMetric.startsWith("entity_killed/") || safeMetric.equals("mobs_killed");
    }

    private static boolean isEntityDeathAction(String action, String metric) {
        String safeAction = safe(action, "").toLowerCase(Locale.ROOT);
        String safeMetric = safe(metric, "").toLowerCase(Locale.ROOT);
        return safeAction.contains("death") || safeAction.contains("killed by") || safeMetric.startsWith("killed_by/") || safeMetric.startsWith("mob_death/") || safeMetric.equals("entity_deaths") || safeMetric.equals("killed_by_entities");
    }

    private static String bestLeader(List<ParsedRow> rows) {
        String leader = "none";
        int best = 0;

        for (ParsedRow row : rows) {
            int value = Math.max(row.row.leaderValue(), row.row.value());

            if (value >= best) {
                best = value;
                leader = row.row.leader();
            }
        }

        return safe(leader, "none");
    }

    private static int bestLeaderValue(List<ParsedRow> rows) {
        int best = 0;

        for (ParsedRow row : rows) {
            best = Math.max(best, Math.max(row.row.leaderValue(), row.row.value()));
        }

        return best;
    }

    private static int confidence(List<ParsedRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }

        int total = 0;

        for (ParsedRow row : rows) {
            total += row.row.confidence();
        }

        return Math.max(0, Math.min(100, Math.round(total / (float) rows.size())));
    }

    private static boolean authoritative(List<ParsedRow> rows) {
        if (rows.isEmpty()) {
            return false;
        }

        for (ParsedRow row : rows) {
            if (!row.row.authoritative()) {
                return false;
            }
        }

        return true;
    }


    private static String stockReceiptCell(String label, String value) {
        String cleanLabel = label == null || label.isBlank() ? "field" : label.trim();
        String cleanValue = value == null || value.isBlank() ? "none" : value.trim();
        return cleanLabel + ": " + cleanValue;
    }

    private static String latestOhlcSummary(KoilGlobalActivityRow row) {
        if (row == null) {
            return "";
        }

        int[] open = row.candleOpenView();
        int[] high = row.candleHighView();
        int[] low = row.candleLowView();
        int[] close = row.candleCloseView();
        int index = Math.min(Math.min(open.length, high.length), Math.min(low.length, close.length)) - 1;

        if (index < 0) {
            return "";
        }

        return compact(open[index]) + " / " + compact(high[index]) + " / " + compact(low[index]) + " / " + compact(close[index]);
    }

    private static String trendLabel(int trend) {
        if (trend > 0) {
            return "rising " + signedCompact(trend);
        }

        if (trend < 0) {
            return "falling " + signedCompact(trend);
        }

        return "flat";
    }

    private static String signedCompact(int value) {
        if (value > 0) {
            return "+" + compact(value);
        }

        if (value < 0) {
            return "-" + compact(Math.abs(value));
        }

        return "0";
    }

    private static String sparkline(int[] values, int width) {
        if (values == null || values.length == 0 || width <= 0) {
            return "";
        }

        char[] chars = new char[]{'_', '.', ':', '-', '=', '+', '*', '#'};
        int samples = Math.max(1, Math.min(width, values.length));
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (int value : values) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        int range = Math.max(1, max - min);
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < samples; i++) {
            int sourceIndex = samples == 1 ? values.length - 1 : Math.round(i / (float) (samples - 1) * (values.length - 1));
            int value = values[Math.max(0, Math.min(values.length - 1, sourceIndex))];
            int level = Math.max(0, Math.min(chars.length - 1, Math.round((value - min) / (float) range * (chars.length - 1))));
            builder.append(chars[level]);
        }

        return builder.toString();
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String titleCase(String value) {
        String[] parts = safe(value, "unknown").replace('_', ' ').replace('-', ' ').split("\\s+");
        StringBuilder builder = new StringBuilder();

        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }

            if (builder.length() > 0) {
                builder.append(' ');
            }

            builder.append(Character.toUpperCase(part.charAt(0))).append(part.length() > 1 ? part.substring(1) : "");
        }

        return builder.length() == 0 ? value : builder.toString();
    }

    private static String compact(int value) {
        if (value >= 1000000) {
            return String.format(Locale.ROOT, "%.1fm", value / 1000000.0F);
        }

        if (value >= 1000) {
            return String.format(Locale.ROOT, "%.1fk", value / 1000.0F);
        }

        return String.valueOf(value);
    }

    private static final class ParsedRow {
        private final KoilGlobalActivityRow row;
        private final String metric;
        private final String domain;
        private final String subjectKey;
        private final String action;
        private final boolean developer;

        private ParsedRow(KoilGlobalActivityRow row, String metric, String domain, String subjectKey, String action, boolean developer) {
            this.row = row;
            this.metric = metric;
            this.domain = domain;
            this.subjectKey = subjectKey;
            this.action = action;
            this.developer = developer;
        }
    }

    private static final class Group {
        private final String tier;
        private final String id;
        private final String domain;
        private final String subject;
        private final String action;
        private final List<ParsedRow> rows = new ArrayList<>();

        private Group(String tier, String id, String domain, String subject, String action) {
            this.tier = tier;
            this.id = id;
            this.domain = domain;
            this.subject = subject;
            this.action = action;
        }

        private void add(ParsedRow row) {
            this.rows.add(row);
        }

        private KoilGlobalMarketViewRow toView() {
            Map<String, Integer> components = new LinkedHashMap<>();
            List<String> rawMetrics = new ArrayList<>();
            int value = 0;
            int volume = 0;

            for (ParsedRow row : this.rows) {
                value += Math.max(0, row.row.value());
                volume += Math.max(0, row.row.volume());
                String componentKey = componentKey(row.action, row.metric);
                components.put(componentKey, components.getOrDefault(componentKey, 0) + Math.max(0, row.row.value()));
                rawMetrics.add(row.metric);
            }

            int[] points = "entity".equals(this.domain) ? entityKillDeathRatioPoints(this.rows) : marketPressurePoints(this.rows, this.domain);
            int change = points.length < 2 ? 0 : points[points.length - 1] - points[points.length - 2];
            String title = "head".equals(this.tier) ? headTitle(this.domain) : subTitle(this.domain, this.subject);
            String description = "head".equals(this.tier)
                    ? headDescription(this.domain)
                    : subDescription(this.domain, this.subject);
            String leader = bestLeader(this.rows);
            int leaderValue = bestLeaderValue(this.rows);
            int confidence = confidence(this.rows);
            boolean authoritative = authoritative(this.rows);
            int base = points.length == 0 ? 0 : Math.max(1, points[0]);
            String chartMetric = rawMetrics.isEmpty() ? this.id : rawMetrics.get(0);
            KoilGlobalActivityRow chartRow = new KoilGlobalActivityRow("market", this.tier + "/" + this.id, chartMetric, title, value, leader, leaderValue, points, authoritative ? "server aggregate" : "mixed aggregate", base, change, volume, this.id, confidence, authoritative);
            return new KoilGlobalMarketViewRow(this.tier, this.tier + "/" + this.id, this.domain, this.subject, this.action, title, description, value, leader, leaderValue, confidence, authoritative, components, rawMetrics, chartRow);
        }
    }
}
