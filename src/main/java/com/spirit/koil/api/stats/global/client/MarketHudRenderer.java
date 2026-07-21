package com.spirit.koil.api.stats.global.client;

import com.spirit.client.gui.console.ConsoleScreen;
import com.spirit.koil.api.stats.global.KoilMarketHudSnapshot;
import com.spirit.koil.api.stats.global.KoilMarketSeriesWindow;
import com.spirit.koil.api.stats.global.KoilMarketValueQuote;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public final class MarketHudRenderer {
    private static MarketHudBlock cachedBlock;
    private static String cachedBlockKey = "";

    private MarketHudRenderer() {
    }

    public static int reservedHeight(MinecraftClient client) {
        return panelHeight(client);
    }

    public static void render(DrawContext context, MinecraftClient client) {
        MarketHudBlock block = buildBlock(client);

        if (block == null || client == null) {
            return;
        }

        int y = client.getWindow().getScaledHeight() - bottomOffset(client) - block.height;
        renderAt(context, client, y);
    }

    public static void renderAt(DrawContext context, MinecraftClient client, int y) {
        MarketHudBlock block = buildBlock(client);
        if (block == null || client == null) {
            return;
        }
        int x = 0;
        int background = panelBackgroundColor(client);
        context.fill(x, y, x + block.width, y + block.height, background);
        context.fill(x, y, x + 2, y + block.height, block.accentColor);

        int textY = y + block.paddingY;

        for (OrderedText line : block.lines) {
            context.drawTextWithShadow(client.textRenderer, line, x + block.paddingX, textY, 0xFFFFFF);
            textY += block.lineHeight;
        }

        if (!block.entries.isEmpty()) {
            int chartY = textY + 3;
            int chartHeight = block.chartHeight;
            int chartX = x + block.paddingX;
            int chartWidth = block.width - block.paddingX * 2;

            if (block.entries.size() >= 2) {
                int gap = 6;
                int cellWidth = Math.max(38, (chartWidth - gap) / 2);
                drawChartCell(context, client, chartX, chartY, cellWidth, chartHeight, block.entries.get(0), 0xFF8EE8FF, block.showDeltas);
                drawChartCell(context, client, chartX + cellWidth + gap, chartY, chartWidth - cellWidth - gap, chartHeight, block.entries.get(1), 0xFFE9B85B, block.showDeltas);
            } else {
                drawChartCell(context, client, chartX, chartY, chartWidth, chartHeight, block.entries.get(0), primaryChartColor(block.entries.get(0)), block.showDeltas);
            }
        }
    }

    public static int panelHeight(MinecraftClient client) {
        MarketHudBlock block = buildBlock(client);
        return block == null ? 0 : block.height;
    }

    private static MarketHudBlock buildBlock(MinecraftClient client) {
        if (client == null || client.player == null || client.textRenderer == null || client.currentScreen instanceof ConsoleScreen) {
            return null;
        }

        if (!(client.currentScreen == null || client.currentScreen instanceof ChatScreen)) {
            return null;
        }

        KoilMarketHudSnapshot snapshot = MarketHudState.snapshot();

        if (snapshot == null || snapshot.entries().isEmpty() && snapshot.notes().isEmpty()) {
            cachedBlock = null;
            cachedBlockKey = "";
            return null;
        }

        int chatWidth = client.inGameHud == null ? 0 : client.inGameHud.getChatHud().getWidth();
        String key = blockCacheKey(client, snapshot, chatWidth);

        if (cachedBlock != null && key.equals(cachedBlockKey)) {
            return cachedBlock;
        }
        int width = Math.min(client.getWindow().getScaledWidth(), Math.max(snapshot.entries().size() >= 2 ? 330 : 246, chatWidth + 12));
        int innerWidth = width - 14;
        int lineHeight = client.textRenderer.fontHeight + 1;
        List<OrderedText> lines = new ArrayList<>();
        lines.addAll(wrappedLines(client, header(snapshot), innerWidth, 1));
        lines.addAll(wrappedLines(client, prompt(snapshot), innerWidth, 1));

        boolean showDeltas = showDeltaText(snapshot);

        if (snapshot.entries().size() >= 2) {
            lines.addAll(wrappedLines(client, compareReceipt(snapshot, showDeltas), innerWidth, 8));
        } else if (snapshot.entries().size() == 1) {
            lines.addAll(wrappedLines(client, singleReceipt(snapshot, showDeltas), innerWidth, 7));
        }

        for (String note : snapshot.notes()) {
            lines.addAll(wrappedLines(client, Text.literal(note == null ? "" : note).styled(style -> style.withColor(0xD6D6D6)), innerWidth, 1));
        }

        lines.addAll(wrappedLines(client, footer(snapshot), innerWidth, 1));
        int chartHeight = snapshot.entries().size() >= 2 ? 38 : 34;
        boolean hasChart = false;

        for (KoilMarketHudSnapshot.Entry entry : snapshot.entries()) {
            if (displaySeries(client, entry).length > 1) {
                hasChart = true;
                break;
            }
        }

        int height = Math.max(lineHeight, lines.size() * lineHeight) + 7 + (hasChart ? chartHeight + 12 : 0);
        cachedBlock = new MarketHudBlock(lines, snapshot.entries(), width, height, 7, 4, lineHeight, chartHeight, accentColor(snapshot), snapshot.watch(), showDeltas);
        cachedBlockKey = key;
        return cachedBlock;
    }

    private static String blockCacheKey(MinecraftClient client, KoilMarketHudSnapshot snapshot, int chatWidth) {
        StringBuilder builder = new StringBuilder();
        builder.append(client.getWindow().getScaledWidth()).append('|');
        builder.append(chatWidth).append('|');
        builder.append(KoilMarketSeriesWindow.activeKey()).append('|');
        builder.append(snapshot.createdAt()).append('|');
        builder.append(snapshot.mode()).append('|').append(snapshot.title()).append('|').append(snapshot.subtitle()).append('|').append(snapshot.reserveLine()).append('|').append(snapshot.sourceLine()).append('|');

        for (String note : snapshot.notes()) {
            builder.append(note).append('|');
        }

        for (KoilMarketHudSnapshot.Entry entry : snapshot.entries()) {
            builder.append(entry.id()).append('|').append(entry.value()).append('|').append(entry.exchange()).append('|').append(entry.confidence()).append('|').append(entry.demand()).append('|').append(entry.supply()).append('|').append(entry.scarcity()).append('|').append(entry.trend()).append('|');
            int[] series = entry.series();
            builder.append(series.length).append(':');

            if (series.length > 0) {
                builder.append(series[0]).append(',').append(series[series.length - 1]);
            }
        }

        return builder.toString();
    }

    private static Text header(KoilMarketHudSnapshot snapshot) {
        int accent = accentColor(snapshot);
        String mode = snapshot.mode();
        String entryCount = snapshot.entries().size() >= 2 ? "2 charts" : snapshot.entries().size() == 1 ? "1 chart" : "receipt";
        return Text.literal("Market").styled(style -> style.withColor(0xA7A7A7))
                .append(Text.literal(" | ").styled(style -> style.withColor(0x444444)))
                .append(Text.literal(mode).styled(style -> style.withColor(accent)))
                .append(Text.literal(" | ").styled(style -> style.withColor(0x444444)))
                .append(Text.literal(entryCount).styled(style -> style.withColor(0xFFFFFF)))
                .append(Text.literal(" | ").styled(style -> style.withColor(0x444444)))
                .append(Text.literal(KoilMarketSeriesWindow.label(KoilMarketSeriesWindow.activeKey())).styled(style -> style.withColor(0x55DDE8)));
    }

    private static Text prompt(KoilMarketHudSnapshot snapshot) {
        String title = snapshot.subtitle().isBlank() ? snapshot.title() : snapshot.subtitle();
        return Text.literal("ƒ_ ").styled(style -> style.withColor(0xE9B85B))
                .append(Text.literal(clip(title, 60)).styled(style -> style.withColor(0xFFFFFF)));
    }

    private static boolean showDeltaText(KoilMarketHudSnapshot snapshot) {
        String mode = snapshot == null || snapshot.mode() == null ? "" : snapshot.mode().toLowerCase();
        return mode.equals("catch update") || mode.contains("catch update");
    }

    private static Text singleReceipt(KoilMarketHudSnapshot snapshot, boolean showDeltas) {
        KoilMarketHudSnapshot.Entry entry = snapshot.entries().get(0);
        return Text.literal("Price ").styled(style -> style.withColor(0x777777))
                .append(valueText(entry.value(), entry.valueDelta(), 0x76E28E, showDeltas))
                .append(Text.literal("   Demand ").styled(style -> style.withColor(0x777777)))
                .append(valueText(compact(entry.demand()), entry.demandDelta(), scoreColor(entry.demand(), entry.supply(), true), showDeltas))
                .append(Text.literal("\nSupply ").styled(style -> style.withColor(0x777777)))
                .append(valueText(compact(entry.supply()), entry.supplyDelta(), scoreColor(entry.supply(), entry.demand(), false), showDeltas))
                .append(Text.literal("   Scarcity ").styled(style -> style.withColor(0x777777)))
                .append(valueText(compact(entry.scarcity()), entry.scarcityDelta(), scoreColor(entry.scarcity(), entry.supply(), true), showDeltas))
                .append(Text.literal("\nTrend ").styled(style -> style.withColor(0x777777)))
                .append(valueText(compact(entry.trend()), entry.trendDelta(), trendColor(entry.trend()), showDeltas))
                .append(Text.literal("   Confidence ").styled(style -> style.withColor(0x777777)))
                .append(Text.literal(entry.confidence() + "%").styled(style -> style.withColor(confidenceColor(entry.confidence()))))
                .append(Text.literal("\nExchange ").styled(style -> style.withColor(0x777777)))
                .append(Text.literal(clip(entry.exchange(), 48)).styled(style -> style.withColor(0xE0D172)));
    }

    private static Text compareReceipt(KoilMarketHudSnapshot snapshot, boolean showDeltas) {
        KoilMarketHudSnapshot.Entry first = snapshot.entries().get(0);
        KoilMarketHudSnapshot.Entry second = snapshot.entries().get(1);
        return Text.literal("Asset ").styled(style -> style.withColor(0x777777))
                .append(Text.literal(clip(first.title(), 20)).styled(style -> style.withColor(0x8EE8FF)))
                .append(Text.literal(" / ").styled(style -> style.withColor(0x444444)))
                .append(Text.literal(clip(second.title(), 20)).styled(style -> style.withColor(0xE9B85B)))
                .append(Text.literal("\nPrice ").styled(style -> style.withColor(0x777777)))
                .append(valueText(first.value(), first.valueDelta(), strongerColor(first, second), showDeltas))
                .append(Text.literal(" / ").styled(style -> style.withColor(0x444444)))
                .append(valueText(second.value(), second.valueDelta(), strongerColor(second, first), showDeltas))
                .append(Text.literal("\nDemand ").styled(style -> style.withColor(0x777777)))
                .append(valueText(compact(first.demand()), first.demandDelta(), scoreColor(first.demand(), second.demand(), true), showDeltas))
                .append(Text.literal(" / ").styled(style -> style.withColor(0x444444)))
                .append(valueText(compact(second.demand()), second.demandDelta(), scoreColor(second.demand(), first.demand(), true), showDeltas))
                .append(Text.literal("   Supply ").styled(style -> style.withColor(0x777777)))
                .append(valueText(compact(first.supply()), first.supplyDelta(), scoreColor(first.supply(), second.supply(), false), showDeltas))
                .append(Text.literal(" / ").styled(style -> style.withColor(0x444444)))
                .append(valueText(compact(second.supply()), second.supplyDelta(), scoreColor(second.supply(), first.supply(), false), showDeltas))
                .append(Text.literal("\nScarcity ").styled(style -> style.withColor(0x777777)))
                .append(valueText(compact(first.scarcity()), first.scarcityDelta(), scoreColor(first.scarcity(), second.scarcity(), true), showDeltas))
                .append(Text.literal(" / ").styled(style -> style.withColor(0x444444)))
                .append(valueText(compact(second.scarcity()), second.scarcityDelta(), scoreColor(second.scarcity(), first.scarcity(), true), showDeltas))
                .append(Text.literal("   Confidence ").styled(style -> style.withColor(0x777777)))
                .append(Text.literal(Math.min(first.confidence(), second.confidence()) + "%").styled(style -> style.withColor(confidenceColor(Math.min(first.confidence(), second.confidence())))));
    }

    private static Text valueText(String value, String delta, int valueColor, boolean showDeltas) {
        return Text.literal(value == null ? "" : value).styled(style -> style.withColor(valueColor))
                .append(showDeltas ? deltaText(delta) : Text.empty());
    }

    private static Text deltaText(String delta) {
        if (delta == null || delta.isBlank()) {
            return Text.empty();
        }

        return Text.literal(" " + delta).styled(style -> style.withColor(deltaColor(delta)));
    }

    private static int deltaColor(String delta) {
        String safe = delta == null ? "" : delta;

        if (safe.contains("↑") || safe.contains("+")) {
            return 0x76E28E;
        }

        if (safe.contains("↓") || safe.contains("-")) {
            return 0xE06666;
        }

        return 0xE0D172;
    }

    private static Text footer(KoilMarketHudSnapshot snapshot) {
        return Text.literal(clip(snapshot.reserveLine(), 58)).styled(style -> style.withColor(0xE0D172))
                .append(Text.literal("  ").styled(style -> style.withColor(0x444444)))
                .append(Text.literal(clip(snapshot.sourceLine(), 34)).styled(style -> style.withColor(sourceColor(snapshot.sourceLine()))))
                .append(Text.literal("  ").styled(style -> style.withColor(0x444444)))
                .append(Text.literal("baseline: previous close").styled(style -> style.withColor(0x718091)));
    }

    private static List<OrderedText> wrappedLines(MinecraftClient client, Text text, int width, int maxLines) {
        if (text == null || text.getString().isBlank()) {
            return List.of();
        }

        List<OrderedText> result = new ArrayList<>();
        List<OrderedText> lines = client.textRenderer.wrapLines(text, width);

        for (int i = 0; i < lines.size() && i < maxLines; i++) {
            result.add(lines.get(i));
        }

        return result;
    }

    private static int bottomOffset(MinecraftClient client) {
        if (client.currentScreen instanceof ChatScreen) {
            return 22;
        }

        return 8;
    }

    private static int panelBackgroundColor(MinecraftClient client) {
        double opacity = Math.max(client.options.getTextBackgroundOpacity().getValue(), 0.48D);
        int value = Math.max(0, Math.min(210, (int) (255.0D * opacity)));
        return (value << 24) | 0x050607;
    }

    private static void drawChartCell(DrawContext context, MinecraftClient client, int x, int y, int width, int height, KoilMarketHudSnapshot.Entry entry, int fallbackColor, boolean showDeltas) {
        context.fill(x, y, x + width, y + height, 0x2A000000);
        context.drawBorder(x, y, width, height, 0x55333333);
        String title = clip(entry.subject(), Math.max(5, width / Math.max(1, client.textRenderer.getWidth("W")) - 8));
        context.drawTextWithShadow(client.textRenderer, title, x + 3, y + 3, 0xCFCFCF);
        if (showDeltas && !entry.chartDelta().isBlank()) {
            int deltaX = x + 5 + client.textRenderer.getWidth(title);
            context.drawTextWithShadow(client.textRenderer, entry.chartDelta(), deltaX, y + 3, deltaColor(entry.chartDelta()));
        }
        int[] display = displaySeries(client, entry);
        drawMiniChart(context, x + 3, y + 12, width - 6, height - 15, display, display.length > 1 ? primaryChartColor(display) : fallbackColor);
    }

    private static void drawMiniChart(DrawContext context, int x, int y, int width, int height, int[] points, int color) {
        if (points == null || points.length < 2 || width <= 2 || height <= 2) {
            return;
        }

        int[] display = renderSeriesForWidth(points, width);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (int point : display) {
            min = Math.min(min, point);
            max = Math.max(max, point);
        }

        if (min > 0) {
            min = 0;
        }

        if (max < 0) {
            max = 0;
        }

        int range = Math.max(1, max - min);
        int baselineY = chartY(y, height, 0, min, range);

        if (baselineY > y && baselineY < y + height) {
            drawDashedHorizontal(context, x, x + width, baselineY, 0x44909090);
        }

        drawMiniCandles(context, x, y, width, height, display, min, range);
        int lastX = x;
        int lastY = chartY(y, height, display[0], min, range);

        for (int i = 1; i < display.length; i++) {
            int nextX = x + Math.round(i * (width - 1) / (float) (display.length - 1));
            int nextY = chartY(y, height, display[i], min, range);
            drawLine(context, lastX, lastY, nextX, nextY, color);
            lastX = nextX;
            lastY = nextY;
        }
    }

    private static int[] renderSeriesForWidth(int[] source, int width) {
        if (source == null || source.length == 0) {
            return new int[0];
        }

        int samples = Math.max(2, Math.min(source.length, Math.max(2, width / 2)));

        if (source.length <= samples) {
            int[] copy = new int[source.length];
            System.arraycopy(source, 0, copy, 0, source.length);
            return copy;
        }

        int[] out = new int[samples];

        for (int i = 0; i < samples; i++) {
            int index = Math.round(i * (source.length - 1) / (float) (samples - 1));
            out[i] = source[Math.max(0, Math.min(source.length - 1, index))];
        }

        return out;
    }

    private static void drawMiniCandles(DrawContext context, int x, int y, int width, int height, int[] points, int min, int range) {
        if (points == null || points.length < 4 || width < 24) {
            return;
        }

        int candleCount = Math.max(2, Math.min(12, width / 12));
        int bucket = Math.max(1, (int) Math.ceil(points.length / (float) candleCount));

        for (int i = 0; i < candleCount; i++) {
            int start = i * bucket;
            int end = Math.min(points.length, start + bucket);

            if (start >= end) {
                break;
            }

            int open = points[start];
            int close = points[end - 1];
            int high = open;
            int low = open;

            for (int valueIndex = start; valueIndex < end; valueIndex++) {
                high = Math.max(high, points[valueIndex]);
                low = Math.min(low, points[valueIndex]);
            }

            int centerX = x + Math.round((i + 0.5F) * width / candleCount);
            int highY = chartY(y, height, high, min, range);
            int lowY = chartY(y, height, low, min, range);
            int openY = chartY(y, height, open, min, range);
            int closeY = chartY(y, height, close, min, range);
            int candleColor = close >= open ? 0x6676E28E : 0x66E06666;
            context.fill(centerX, Math.min(highY, lowY), centerX + 1, Math.max(highY, lowY) + 1, candleColor);
            context.fill(centerX - 1, Math.min(openY, closeY), centerX + 2, Math.max(openY, closeY) + 1, candleColor);
        }
    }

    private static int chartY(int y, int height, int value, int min, int range) {
        return y + height - 2 - Math.round((value - min) * (height - 4) / (float) Math.max(1, range));
    }

    private static void drawDashedHorizontal(DrawContext context, int x1, int x2, int y, int color) {
        for (int x = x1; x < x2; x += 6) {
            context.fill(x, y, Math.min(x + 3, x2), y + 1, color);
        }
    }

    private static void drawLine(DrawContext context, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;
        int x = x1;
        int y = y1;

        while (true) {
            context.fill(x, y, x + 1, y + 1, color);

            if (x == x2 && y == y2) {
                break;
            }

            int e2 = err * 2;

            if (e2 > -dy) {
                err -= dy;
                x += sx;
            }

            if (e2 < dx) {
                err += dx;
                y += sy;
            }
        }
    }

    private static int primaryChartColor(KoilMarketHudSnapshot.Entry entry) {
        return primaryChartColor(entry.series());
    }

    private static int primaryChartColor(int[] series) {
        if (series == null || series.length < 2) {
            return 0xFFE0D172;
        }

        return series[series.length - 1] >= series[0] ? 0xFF76E28E : 0xFFE06666;
    }

    private static int[] displaySeries(MinecraftClient client, KoilMarketHudSnapshot.Entry entry) {
        long worldTime = client == null || client.world == null ? 0L : client.world.getTimeOfDay();
        return KoilMarketSeriesWindow.points(entry == null ? null : entry.series(), KoilMarketSeriesWindow.activeKey(), worldTime);
    }

    private static int strongerColor(KoilMarketHudSnapshot.Entry value, KoilMarketHudSnapshot.Entry compare) {
        double first = parseMomens(value.value());
        double second = parseMomens(compare.value());
        return first >= second ? 0x76E28E : 0xE06666;
    }

    private static int scoreColor(double value, double compare, boolean higherGood) {
        if (higherGood) {
            return value > compare * 1.08D ? 0x76E28E : value < compare * 0.92D ? 0xE06666 : 0xE0D172;
        }

        return value < compare * 0.92D ? 0x76E28E : value > compare * 1.08D ? 0xE06666 : 0xE0D172;
    }

    private static int trendColor(double trend) {
        return trend > 1.05D ? 0x76E28E : trend < 0.95D ? 0xE06666 : 0xE0D172;
    }

    private static int confidenceColor(int confidence) {
        return confidence >= 70 ? 0x76E28E : confidence >= 40 ? 0xE0D172 : 0xE06666;
    }

    private static int sourceColor(String value) {
        String lower = value == null ? "" : value.toLowerCase();
        return lower.contains("estimated") ? 0xE0D172 : 0x76E28E;
    }

    private static int accentColor(KoilMarketHudSnapshot snapshot) {
        if (snapshot.entries().size() >= 2) {
            return 0xFFE9B85B;
        }

        String mode = snapshot.mode() == null ? "" : snapshot.mode().toLowerCase();
        if (mode.contains("catch")) {
            return 0xFF55DDE8;
        }

        if (mode.contains("error")) {
            return 0xFFE06666;
        }

        if (mode.contains("base") || mode.contains("currency")) {
            return 0xFFE0D172;
        }

        return 0xFF76E28E;
    }

    private static String compact(double value) {
        return KoilMarketValueQuote.compactDecimal(value);
    }

    private static double parseMomens(String value) {
        if (value == null) {
            return 0.0D;
        }

        try {
            return Double.parseDouble(value.replace("ƒ", "").replace("k", "000").replaceAll("[^0-9.]", ""));
        } catch (Exception ignored) {
            return 0.0D;
        }
    }

    private static String clip(String value, int max) {
        String safe = value == null ? "" : value;

        if (safe.length() <= max) {
            return safe;
        }

        return safe.substring(0, Math.max(0, max - 1)) + "~";
    }

    private record MarketHudBlock(List<OrderedText> lines, List<KoilMarketHudSnapshot.Entry> entries, int width, int height, int paddingX, int paddingY, int lineHeight, int chartHeight, int accentColor, boolean watch, boolean showDeltas) {
    }
}
