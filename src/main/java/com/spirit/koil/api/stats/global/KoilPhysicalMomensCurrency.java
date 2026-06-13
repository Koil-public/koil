package com.spirit.koil.api.stats.global;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.*;

public final class KoilPhysicalMomensCurrency {
    private static final int[] DENOMINATIONS = new int[]{500, 100, 50, 20, 10, 5, 1};
    private static final String ROOT = "koil_momens";
    private static final String SOURCES = "sources";

    private KoilPhysicalMomensCurrency() {
    }

    public static boolean available() {
        return !detectedNotes().isEmpty();
    }

    public static List<String> detectedNotes() {
        List<String> values = new ArrayList<>();

        for (int denomination : DENOMINATIONS) {
            Identifier note = new Identifier("shit", denomination + "_momen");
            Identifier stack = new Identifier("shit", denomination + "_momen_stack");

            if (contains(note)) {
                values.add(note + " = ƒ" + denomination);
            }

            if (contains(stack)) {
                values.add(stack + " = ƒ" + (denomination * 9));
            }
        }

        Collections.reverse(values);
        return values;
    }

    public static int giveMomens(ServerPlayerEntity player, int amount) {
        SourceInfo source = new SourceInfo("admin", "Admin Issued Momens", Math.max(0, amount), 1.0D, 0, player == null ? "server" : player.getGameProfile().getName(), false, "admin_issued");
        return giveMomens(player, amount, source);
    }

    public static int giveMomens(ServerPlayerEntity player, int amount, SourceInfo source) {
        if (player == null || amount <= 0) {
            return 0;
        }

        int remaining = amount;
        int given = 0;

        for (ItemValue itemValue : availableValues()) {
            int itemMomens = itemValue.value();
            int count = remaining / itemMomens;

            while (count > 0) {
                int stackCount = Math.min(count, Math.max(1, new ItemStack(itemValue.item()).getMaxCount()));
                ItemStack stack = new ItemStack(itemValue.item(), stackCount);
                int stackValue = stackCount * itemMomens;
                SourceInfo noteSource = source == null ? SourceInfo.admin(amount) : source;
                writeSource(stack, noteSource, itemMomens);
                insertOrDrop(player, stack);
                remaining -= stackValue;
                given += stackValue;
                count -= stackCount;
            }
        }

        return given;
    }


    public static int giveExactMomens(ServerPlayerEntity player, Item item, int count, int denominationValue, SourceInfo source) {
        if (player == null || item == null || count <= 0 || denominationValue <= 0) {
            return 0;
        }

        int maxCount = Math.max(1, new ItemStack(item).getMaxCount());
        int remaining = count;
        int given = 0;

        while (remaining > 0) {
            int stackCount = Math.min(maxCount, remaining);
            ItemStack stack = new ItemStack(item, stackCount);
            writeSource(stack, source == null ? SourceInfo.admin(denominationValue * stackCount) : source, denominationValue);
            insertOrDrop(player, stack);
            remaining -= stackCount;
            given += stackCount * denominationValue;
        }

        return given;
    }

    public static int compactInventory(ServerPlayerEntity player) {
        if (player == null) {
            return 0;
        }

        Map<String, CompactBucket> buckets = new LinkedHashMap<>();

        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);

            if (!isMomens(stack)) {
                continue;
            }

            Identifier id = Registries.ITEM.getId(stack.getItem());
            int value = momensValue(id);

            if (value <= 0) {
                continue;
            }

            CompactBucket bucket = buckets.computeIfAbsent(id + "|" + sourceMode(sources(stack)), key -> new CompactBucket(stack.getItem(), value));
            bucket.count += stack.getCount();
            bucket.momens += value * stack.getCount();
            bucket.sources.addAll(sources(stack));
            player.getInventory().setStack(i, ItemStack.EMPTY);
        }

        int compacted = 0;

        for (CompactBucket bucket : buckets.values()) {
            int maxCount = Math.max(1, new ItemStack(bucket.item).getMaxCount());
            int remaining = bucket.count;

            while (remaining > 0) {
                int count = Math.min(maxCount, remaining);
                ItemStack stack = new ItemStack(bucket.item, count);
                writeMixedSources(stack, bucket.sources, bucket.momens, bucket.value);
                insertOrDrop(player, stack);
                remaining -= count;
                compacted += count;
            }
        }

        return compacted;
    }

    public static boolean mergeStacks(ItemStack target, ItemStack incoming) {
        if (!canMergeStacks(target, incoming)) {
            return false;
        }

        int max = target.getMaxCount();
        int move = Math.min(incoming.getCount(), max - target.getCount());

        if (move <= 0) {
            return false;
        }

        List<SourceEntry> mergedSources = new ArrayList<>();
        mergedSources.addAll(sources(target));

        ItemStack movedCopy = incoming.copy();
        movedCopy.setCount(move);
        mergedSources.addAll(sources(movedCopy));

        int targetValue = momensValue(Registries.ITEM.getId(target.getItem()));
        int mergedCount = target.getCount() + move;
        int mergedMomens = targetValue * mergedCount;

        target.setCount(mergedCount);
        incoming.decrement(move);
        writeMixedSources(target, mergedSources, Math.max(1, totalMomens(mergedSources)), targetValue);
        return true;
    }

    public static boolean canMergeStacks(ItemStack target, ItemStack incoming) {
        if (!isMomens(target) || !isMomens(incoming) || target.isEmpty() || incoming.isEmpty()) {
            return false;
        }

        if (!target.isOf(incoming.getItem())) {
            return false;
        }

        return target.getCount() < target.getMaxCount();
    }

    public static boolean isMomens(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        return momensValue(Registries.ITEM.getId(stack.getItem())) > 0;
    }

    public static int stackMomensValue(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }

        return momensValue(Registries.ITEM.getId(stack.getItem())) * stack.getCount();
    }

    public static int redeemableMomensValue(ItemStack stack) {
        int value = 0;

        for (SourceEntry entry : sources(stack)) {
            if (entry.redeemable()) {
                value += Math.max(0, entry.momens());
            }
        }

        return Math.min(stackMomensValue(stack), value);
    }

    public static List<SourceEntry> sources(ItemStack stack) {
        List<SourceEntry> entries = new ArrayList<>();

        if (stack == null || stack.isEmpty() || stack.getNbt() == null || !stack.getNbt().contains(ROOT, 10)) {
            return entries;
        }

        NbtCompound root = stack.getNbt().getCompound(ROOT);
        NbtList list = root.getList(SOURCES, 10);
        int count = Math.max(1, stack.getCount());

        for (int i = 0; i < list.size(); i++) {
            NbtCompound entry = list.getCompound(i);
            entries.add(new SourceEntry(
                    entry.getString("id"),
                    entry.getString("title"),
                    Math.max(0, entry.getInt("momens")) * count,
                    entry.getDouble("unit_momens"),
                    Math.max(0, entry.getInt("units")) * count,
                    entry.getString("issuer"),
                    entry.getBoolean("redeemable"),
                    entry.getString("reason")
            ));
        }

        return mergeEntries(entries);
    }

    public static List<Text> sourceTooltip(ItemStack stack) {
        List<Text> lines = new ArrayList<>();
        List<SourceEntry> entries = sources(stack);
        int stackValue = stackMomensValue(stack);

        if (entries.isEmpty()) {
            if (isMomens(stack)) {
                lines.add(Text.literal("Momens Portfolio").formatted(Formatting.GOLD, Formatting.BOLD));
                lines.add(Text.literal("┌──────────┬──────────────────────┐").formatted(Formatting.DARK_GRAY));
                lines.add(portfolioTooltipRow("Value", "ƒ" + stackValue, Formatting.GOLD));
                lines.add(portfolioTooltipRow("Backed", "none", Formatting.RED));
                lines.add(portfolioTooltipRow("Rate", "unknown", Formatting.DARK_GRAY));
                lines.add(portfolioTooltipRow("Status", "legacy note", Formatting.RED));
                lines.add(Text.literal("└──────────┴──────────────────────┘").formatted(Formatting.DARK_GRAY));
                lines.add(tooltipLine("Market link", "none", Formatting.DARK_GRAY, Formatting.DARK_GRAY));
                lines.add(tooltipLine("Redeemable", "no source backing", Formatting.GRAY, Formatting.RED));
            }

            return lines;
        }

        int total = 0;
        int redeemable = 0;

        for (SourceEntry entry : entries) {
            total += Math.max(0, entry.momens());

            if (entry.redeemable()) {
                redeemable += Math.max(0, entry.momens());
            }
        }

        int safeRedeemable = Math.min(stackValue, redeemable);
        Formatting coverageColor = coverageColor(safeRedeemable, stackValue);
        String state = entries.size() > 1 ? "mixed-source portfolio" : entries.get(0).redeemable() ? "source-backed note" : "tracked / locked note";
        Formatting stateColor = safeRedeemable > 0 ? entries.size() > 1 ? Formatting.AQUA : Formatting.GREEN : Formatting.RED;
        lines.add(Text.literal("Momens Portfolio").formatted(Formatting.GOLD, Formatting.BOLD));
        lines.add(Text.literal("┌──────────┬──────────────────────┐").formatted(Formatting.DARK_GRAY));
        lines.add(portfolioTooltipRow("Value", "ƒ" + stackValue, Formatting.GOLD));
        lines.add(portfolioTooltipRow("Backed", "ƒ" + safeRedeemable + " " + coveragePercent(safeRedeemable, stackValue), coverageColor));
        lines.add(portfolioTooltipRow("Rate", averageRateText(entries), Formatting.LIGHT_PURPLE));
        lines.add(portfolioTooltipRow("Status", state, stateColor));
        lines.add(Text.literal("└──────────┴──────────────────────┘").formatted(Formatting.DARK_GRAY));

        if (entries.size() == 1) {
            SourceEntry entry = entries.get(0);
            String sourceId = parseSourceId(entry.id());
            lines.add(tooltipLine("Market", shortTitle(entry.title()), Formatting.GRAY, entry.redeemable() ? Formatting.AQUA : Formatting.YELLOW));
            lines.add(tooltipLine("Backing", "ƒ" + entry.momens() + " / " + entry.units() + " unit(s)", Formatting.GRAY, entry.redeemable() ? Formatting.GREEN : Formatting.RED));
            lines.add(tooltipLine("Stored rate", sourceRateText(entry), Formatting.GRAY, Formatting.LIGHT_PURPLE));
            lines.add(tooltipLine("Position", allocationBar(entry.momens(), total) + " " + coveragePercent(entry.momens(), Math.max(1, total)), Formatting.GRAY, entry.redeemable() ? Formatting.GREEN : Formatting.RED));
            lines.add(tooltipLine("Issuer", shortTitle(entry.issuer()), Formatting.DARK_GRAY, Formatting.GRAY));

            if (entry.redeemable() && sourceId != null) {
                lines.add(tooltipLine("Cash out", "/market to " + sourceId, Formatting.GRAY, Formatting.GREEN));
            }
        } else {
            lines.add(tooltipLine("Sources", entries.size() + " market positions", Formatting.GRAY, Formatting.AQUA));
            int limit = Math.min(4, entries.size());

            for (int i = 0; i < limit; i++) {
                SourceEntry entry = entries.get(i);
                Formatting color = entry.redeemable() ? Formatting.GRAY : Formatting.DARK_GRAY;
                lines.add(Text.literal("  " + (i + 1) + ". ").formatted(Formatting.DARK_GRAY)
                        .append(Text.literal("ƒ" + entry.momens()).formatted(entry.redeemable() ? Formatting.GREEN : Formatting.RED))
                        .append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal(allocationBar(entry.momens(), total)).formatted(entry.redeemable() ? Formatting.GREEN : Formatting.RED))
                        .append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal(entry.units() + "u").formatted(Formatting.AQUA))
                        .append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal(sourceRateText(entry)).formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                        .append(Text.literal(shortTitle(entry.title())).formatted(color)));
            }

            if (entries.size() > limit) {
                lines.add(Text.literal("  +" + (entries.size() - limit) + " more market source(s)").formatted(Formatting.DARK_GRAY));
            }
        }

        if (safeRedeemable > 0) {
            lines.add(tooltipLine("Redeemable", "yes, source-backed cash out", Formatting.GRAY, Formatting.GREEN));
        } else {
            lines.add(tooltipLine("Redeemable", "no market backing", Formatting.GRAY, Formatting.RED));
        }

        return lines;
    }

    private static Text tooltipLine(String label, String value, Formatting labelColor, Formatting valueColor) {
        return Text.literal(label + ": ").formatted(labelColor).append(Text.literal(value).formatted(valueColor));
    }

    private static Text portfolioTooltipRow(String label, String value, Formatting valueColor) {
        return Text.literal("| ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(padTooltip(label, 8)).formatted(Formatting.GRAY))
                .append(Text.literal(" | ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal(padTooltip(clipTooltip(value, 20), 20)).formatted(valueColor))
                .append(Text.literal(" |").formatted(Formatting.DARK_GRAY));
    }

    private static String padTooltip(String value, int width) {
        String safe = value == null ? "" : value;

        if (safe.length() >= width) {
            return safe;
        }

        return safe + " ".repeat(width - safe.length());
    }

    private static String clipTooltip(String value, int width) {
        String safe = value == null ? "" : value;

        if (safe.length() <= width) {
            return safe;
        }

        return safe.substring(0, Math.max(0, width - 1)) + "~";
    }

    private static String allocationBar(int value, int total) {
        int slots = 8;
        int filled = total <= 0 ? 0 : Math.max(0, Math.min(slots, Math.round(value * slots / (float) total)));
        return "[" + "#".repeat(filled) + ".".repeat(slots - filled) + "]";
    }

    private static String averageRateText(List<SourceEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "unknown";
        }

        double weighted = 0.0D;
        int units = 0;

        for (SourceEntry entry : entries) {
            if (entry.units() <= 0) {
                continue;
            }

            double rate = entry.unitMomens() > 0.0D ? entry.unitMomens() : entry.momens() / (double) Math.max(1, entry.units());
            weighted += rate * entry.units();
            units += entry.units();
        }

        if (units <= 0) {
            return "unknown";
        }

        return "ƒ" + KoilMarketValueQuote.compactDecimal(weighted / units) + " / unit";
    }

    private static Formatting coverageColor(int redeemable, int value) {
        if (value <= 0 || redeemable <= 0) {
            return Formatting.RED;
        }

        double ratio = redeemable / (double) value;

        if (ratio >= 0.95D) {
            return Formatting.GREEN;
        }

        if (ratio >= 0.5D) {
            return Formatting.YELLOW;
        }

        return Formatting.RED;
    }

    private static String coveragePercent(int redeemable, int value) {
        if (value <= 0) {
            return "0%";
        }

        double percent = Math.max(0.0D, Math.min(100.0D, redeemable * 100.0D / value));
        return KoilMarketValueQuote.compactDecimal(percent) + "%";
    }

    private static String sourceRateText(SourceEntry entry) {
        double rate = entry.unitMomens() > 0.0D ? entry.unitMomens() : entry.units() > 0 ? entry.momens() / (double) entry.units() : 0.0D;

        if (rate <= 0.0D) {
            return "unknown";
        }

        return "ƒ" + KoilMarketValueQuote.compactDecimal(rate) + " / unit";
    }

    private static String parseSourceId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            Identifier id = new Identifier(value.trim().toLowerCase(Locale.ROOT));
            return Registries.ITEM.containsId(id) ? id.toString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }


    public static int denominationValue(String denomination) {
        if (denomination == null || denomination.isBlank()) {
            return 0;
        }

        String clean = denomination.trim().toLowerCase(Locale.ROOT).replace("_momen", "");
        boolean stack = clean.endsWith("_stack") || clean.endsWith("stack");
        clean = clean.replace("_stack", "").replace("stack", "");

        try {
            int value = Integer.parseInt(clean);

            for (int denominationValue : DENOMINATIONS) {
                if (denominationValue == value) {
                    return stack ? value * 9 : value;
                }
            }
        } catch (Exception ignored) {
        }

        return 0;
    }

    public static Item itemForDenomination(String denomination) {
        if (denomination == null || denomination.isBlank()) {
            return null;
        }

        String clean = denomination.trim().toLowerCase(Locale.ROOT).replace("_momen", "");
        boolean stack = clean.endsWith("_stack") || clean.endsWith("stack");
        clean = clean.replace("_stack", "").replace("stack", "");

        try {
            int value = Integer.parseInt(clean);

            for (int denominationValue : DENOMINATIONS) {
                if (denominationValue == value) {
                    return item(new Identifier("shit", value + (stack ? "_momen_stack" : "_momen")));
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    public static int momensValue(Identifier id) {
        if (id == null || !"shit".equals(id.getNamespace())) {
            return 0;
        }

        String path = id.getPath();

        for (int denomination : DENOMINATIONS) {
            if (path.equals(denomination + "_momen")) {
                return denomination;
            }

            if (path.equals(denomination + "_momen_stack")) {
                return denomination * 9;
            }
        }

        return 0;
    }

    public static String summary() {
        List<String> notes = detectedNotes();

        if (notes.isEmpty()) {
            return "Physical Momens: not detected";
        }

        return "Physical Momens: " + notes.size() + " denominations detected";
    }

    public static String detailedSummary() {
        List<String> notes = detectedNotes();

        if (notes.isEmpty()) {
            return "Physical Momens: not detected";
        }

        return String.join(", ", notes);
    }

    private static void writeSource(ItemStack stack, SourceInfo source, int denominationValue) {
        NbtCompound root = new NbtCompound();
        root.putInt("value", denominationValue);
        root.putInt("denomination", denominationValue);
        NbtList list = new NbtList();
        list.add(sourceEntry(normalizeSourceForUnit(source, denominationValue)));
        root.put(SOURCES, list);
        stack.getOrCreateNbt().put(ROOT, root);
    }

    private static void writeMixedSources(ItemStack stack, List<SourceEntry> sources, int sourceTotalMomens, int denominationValue) {
        NbtCompound root = new NbtCompound();
        root.putInt("value", denominationValue);
        root.putInt("denomination", denominationValue);
        NbtList list = new NbtList();
        Map<String, SourceInfo> merged = new LinkedHashMap<>();
        int perItemMomens = Math.max(1, denominationValue);

        for (SourceEntry entry : sources) {
            if (entry.momens() <= 0) {
                continue;
            }

            int scaledMomens = Math.max(0, (int) Math.floor(perItemMomens * (entry.momens() / (double) Math.max(1, sourceTotalMomens))));

            if (scaledMomens <= 0) {
                continue;
            }

            int scaledUnits = entry.units() <= 0 ? 0 : Math.max(1, (int) Math.round(entry.units() * (scaledMomens / (double) Math.max(1, entry.momens()))));
            SourceInfo next = new SourceInfo(entry.id(), entry.title(), scaledMomens, entry.unitMomens(), scaledUnits, entry.issuer(), entry.redeemable(), entry.reason());
            SourceInfo previous = merged.get(entry.key());

            if (previous == null) {
                merged.put(entry.key(), next);
            } else {
                merged.put(entry.key(), previous.merge(next));
            }
        }

        int written = 0;

        for (SourceInfo entry : merged.values()) {
            written += Math.max(0, entry.momens());
        }

        if (written < perItemMomens && !merged.isEmpty()) {
            String firstKey = merged.keySet().iterator().next();
            SourceInfo first = merged.get(firstKey);
            SourceInfo adjusted = first.withMomens(first.momens() + (perItemMomens - written));
            merged.put(firstKey, adjusted);
        }

        if (merged.isEmpty()) {
            merged.put("admin|false|admin_issued", SourceInfo.admin(perItemMomens));
        }

        for (SourceInfo entry : merged.values()) {
            list.add(sourceEntry(entry));
        }

        root.put(SOURCES, list);
        stack.getOrCreateNbt().put(ROOT, root);
    }

    private static SourceInfo normalizeSourceForUnit(SourceInfo source, int denominationValue) {
        SourceInfo safe = source == null ? SourceInfo.admin(denominationValue) : source;
        int originalMomens = Math.max(1, safe.momens());
        int units = safe.units() <= 0 ? 0 : Math.max(1, (int) Math.round(safe.units() * (denominationValue / (double) originalMomens)));
        return new SourceInfo(safe.id(), safe.title(), Math.max(1, denominationValue), safe.unitMomens(), units, safe.issuer(), safe.redeemable(), safe.reason());
    }

    private static NbtCompound sourceEntry(SourceInfo source) {
        NbtCompound entry = new NbtCompound();
        entry.putString("id", safe(source.id(), "admin"));
        entry.putString("title", safe(source.title(), "Admin Issued Momens"));
        entry.putInt("momens", Math.max(0, source.momens()));
        entry.putDouble("unit_momens", Math.max(0.0D, source.unitMomens()));
        entry.putInt("units", Math.max(0, source.units()));
        entry.putString("issuer", safe(source.issuer(), "server"));
        entry.putBoolean("redeemable", source.redeemable());
        entry.putString("reason", safe(source.reason(), source.redeemable() ? "resource_exchange" : "admin_issued"));
        return entry;
    }

    private static List<ItemValue> availableValues() {
        List<ItemValue> values = new ArrayList<>();

        for (int denomination : DENOMINATIONS) {
            Identifier stackId = new Identifier("shit", denomination + "_momen_stack");
            Item stackItem = item(stackId);

            if (stackItem != null) {
                values.add(new ItemValue(stackId, stackItem, denomination * 9));
            }

            Identifier noteId = new Identifier("shit", denomination + "_momen");
            Item noteItem = item(noteId);

            if (noteItem != null) {
                values.add(new ItemValue(noteId, noteItem, denomination));
            }
        }

        values.sort((a, b) -> Integer.compare(b.value(), a.value()));
        return values;
    }

    private static void insertOrDrop(ServerPlayerEntity player, ItemStack stack) {
        if (!player.getInventory().insertStack(stack)) {
            player.dropItem(stack, false);
        }
    }

    private static Item item(Identifier id) {
        try {
            return Registries.ITEM.containsId(id) ? Registries.ITEM.get(id) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean contains(Identifier id) {
        return item(id) != null;
    }

    private static String shortTitle(String value) {
        String safe = safe(value, "unknown");
        return safe.length() > 36 ? safe.substring(0, 33) + "..." : safe;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int totalMomens(List<SourceEntry> entries) {
        int total = 0;

        for (SourceEntry entry : entries) {
            total += Math.max(0, entry.momens());
        }

        return total;
    }

    private static List<SourceEntry> mergeEntries(List<SourceEntry> entries) {
        Map<String, SourceInfo> merged = new LinkedHashMap<>();

        for (SourceEntry entry : entries) {
            if (entry.momens() <= 0) {
                continue;
            }

            SourceInfo next = new SourceInfo(entry.id(), entry.title(), entry.momens(), entry.unitMomens(), entry.units(), entry.issuer(), entry.redeemable(), entry.reason());
            SourceInfo previous = merged.get(entry.key());

            if (previous == null) {
                merged.put(entry.key(), next);
            } else {
                merged.put(entry.key(), previous.merge(next));
            }
        }

        List<SourceEntry> result = new ArrayList<>();

        for (SourceInfo info : merged.values()) {
            result.add(new SourceEntry(info.id(), info.title(), info.momens(), info.unitMomens(), info.units(), info.issuer(), info.redeemable(), info.reason()));
        }

        result.sort((a, b) -> Integer.compare(b.momens(), a.momens()));
        return result;
    }


    private static String sourceMode(List<SourceEntry> entries) {
        boolean redeemable = false;
        boolean nonRedeemable = false;

        for (SourceEntry entry : entries) {
            if (entry.redeemable()) {
                redeemable = true;
            } else {
                nonRedeemable = true;
            }
        }

        if (redeemable && nonRedeemable) {
            return "mixed";
        }

        if (redeemable) {
            return "redeemable";
        }

        return "nonredeemable";
    }

    private static final class CompactBucket {
        private final Item item;
        private final int value;
        private int count;
        private int momens;
        private final List<SourceEntry> sources = new ArrayList<>();

        private CompactBucket(Item item, int value) {
            this.item = item;
            this.value = value;
        }
    }

    private record ItemValue(Identifier id, Item item, int value) {
    }

    public record SourceInfo(String id, String title, int momens, double unitMomens, int units, String issuer, boolean redeemable, String reason) {
        public static SourceInfo admin(int momens) {
            return new SourceInfo("admin", "Admin Issued Momens", momens, 1.0D, 0, "server", false, "admin_issued");
        }

        public SourceInfo withMomens(int value) {
            return new SourceInfo(id, title, value, unitMomens, units, issuer, redeemable, reason);
        }

        public SourceInfo merge(SourceInfo other) {
            if (other == null) {
                return this;
            }

            int nextUnits = units + other.units();
            double nextUnitMomens = unitMomens > 0.0D ? unitMomens : other.unitMomens();
            return new SourceInfo(id, title, momens + other.momens(), nextUnitMomens, nextUnits, issuer, redeemable && other.redeemable(), reason);
        }
    }

    public record SourceEntry(String id, String title, int momens, double unitMomens, int units, String issuer, boolean redeemable, String reason) {
        public String key() {
            return id + "|" + redeemable + "|" + reason + "|" + title + "|" + issuer;
        }
    }
}
