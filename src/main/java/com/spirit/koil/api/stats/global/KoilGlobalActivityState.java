package com.spirit.koil.api.stats.global;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.world.PersistentState;

import java.util.*;

public final class KoilGlobalActivityState extends PersistentState {
    private static final int MAX_POINTS = KoilMarketSeriesWindow.historySampleLimit();
    private static final int MAX_AUTO_MARKETS = 4096;
    private final Map<String, Map<UUID, Integer>> values = new LinkedHashMap<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();
    private final Map<String, Deque<Integer>> history = new LinkedHashMap<>();
    private final Map<String, String> marketMetrics = new LinkedHashMap<>();
    private final Map<String, String> marketNames = new LinkedHashMap<>();
    private final Map<String, Integer> marketBasePrices = new LinkedHashMap<>();
    private final Map<String, Deque<Integer>> marketHistory = new LinkedHashMap<>();
    private long snapshotId;
    private long lastSnapshotAt;

    public static KoilGlobalActivityState fromNbt(NbtCompound nbt) {
        KoilGlobalActivityState state = new KoilGlobalActivityState();
        state.snapshotId = Math.max(0L, nbt.getLong("snapshotId"));
        state.lastSnapshotAt = Math.max(0L, nbt.getLong("lastSnapshotAt"));
        NbtList players = nbt.getList("players", NbtElement.COMPOUND_TYPE);

        for (int i = 0; i < players.size(); i++) {
            NbtCompound player = players.getCompound(i);
            UUID uuid = player.getUuid("uuid");
            String name = player.getString("name");
            state.names.put(uuid, name);
            NbtCompound stats = player.getCompound("stats");

            for (String key : stats.getKeys()) {
                state.values.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(uuid, stats.getInt(key));
            }
        }

        state.readHistory(nbt.getList("history", NbtElement.COMPOUND_TYPE), state.history, false);
        NbtList markets = nbt.getList("markets", NbtElement.COMPOUND_TYPE);

        for (int i = 0; i < markets.size(); i++) {
            NbtCompound market = markets.getCompound(i);
            String id = market.getString("id");
            String metric = market.getString("metric");

            if (id.isBlank() || metric.isBlank()) {
                continue;
            }

            state.marketMetrics.put(id, metric);
            state.marketNames.put(id, market.getString("name"));
            state.marketBasePrices.put(id, Math.max(1, market.getInt("base")));
            Deque<Integer> points = new ArrayDeque<>();
            NbtList list = market.getList("points", NbtElement.INT_TYPE);

            for (int point = 0; point < list.size(); point++) {
                points.add(Math.max(1, list.getInt(point)));
            }

            state.marketHistory.put(id, points);
        }

        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        nbt.putLong("snapshotId", Math.max(0L, this.snapshotId));
        nbt.putLong("lastSnapshotAt", Math.max(0L, this.lastSnapshotAt));
        NbtList players = new NbtList();
        Set<UUID> uuids = new LinkedHashSet<>(this.names.keySet());

        for (Map<UUID, Integer> metricValues : this.values.values()) {
            uuids.addAll(metricValues.keySet());
        }

        for (UUID uuid : uuids) {
            NbtCompound player = new NbtCompound();
            NbtCompound stats = new NbtCompound();
            player.putUuid("uuid", uuid);
            player.putString("name", this.names.getOrDefault(uuid, uuid.toString()));

            for (Map.Entry<String, Map<UUID, Integer>> entry : this.values.entrySet()) {
                Integer value = entry.getValue().get(uuid);

                if (value != null) {
                    stats.putInt(entry.getKey(), Math.max(0, value));
                }
            }

            player.put("stats", stats);
            players.add(player);
        }

        nbt.put("players", players);
        nbt.put("history", writeHistory(this.history, false));
        NbtList markets = new NbtList();

        for (String id : this.marketMetrics.keySet()) {
            NbtCompound market = new NbtCompound();
            market.putString("id", id);
            market.putString("metric", this.marketMetrics.getOrDefault(id, ""));
            market.putString("name", this.marketNames.getOrDefault(id, displayName(id)));
            market.putInt("base", Math.max(1, this.marketBasePrices.getOrDefault(id, 100)));
            NbtList points = new NbtList();
            Deque<Integer> historyPoints = this.marketHistory.getOrDefault(id, new ArrayDeque<>());

            for (Integer point : historyPoints) {
                points.add(NbtInt.of(Math.max(1, point)));
            }

            market.put("points", points);
            markets.add(market);
        }

        nbt.put("markets", markets);
        return nbt;
    }

    public boolean updatePlayer(UUID uuid, String name, Map<String, Integer> stats) {
        boolean changed = false;
        this.names.put(uuid, name == null || name.isBlank() ? uuid.toString() : name);

        for (Map.Entry<String, Integer> entry : stats.entrySet()) {
            String metric = cleanMetric(entry.getKey());
            int value = Math.max(0, entry.getValue());
            Map<UUID, Integer> metricValues = this.values.computeIfAbsent(metric, ignored -> new LinkedHashMap<>());
            Integer oldValue = metricValues.put(uuid, value);

            if (oldValue == null || oldValue != value) {
                changed = true;
            }
        }

        if (ensureAutomaticMarkets()) {
            changed = true;
        }

        return changed;
    }

    public boolean addExternal(UUID uuid, String name, String metric, int amount) {
        String cleanMetric = cleanMetric(metric);

        if (uuid == null || cleanMetric.isBlank() || amount == 0) {
            return false;
        }

        this.names.put(uuid, name == null || name.isBlank() ? uuid.toString() : name);
        Map<UUID, Integer> metricValues = this.values.computeIfAbsent(cleanMetric, ignored -> new LinkedHashMap<>());
        int current = Math.max(0, metricValues.getOrDefault(uuid, 0));
        int next = Math.max(0, current + amount);
        metricValues.put(uuid, next);
        ensureAutomaticMarkets();
        return current != next;
    }

    public boolean setExternal(UUID uuid, String name, String metric, int value) {
        String cleanMetric = cleanMetric(metric);

        if (uuid == null || cleanMetric.isBlank()) {
            return false;
        }

        this.names.put(uuid, name == null || name.isBlank() ? uuid.toString() : name);
        Map<UUID, Integer> metricValues = this.values.computeIfAbsent(cleanMetric, ignored -> new LinkedHashMap<>());
        int safeValue = Math.max(0, value);
        Integer oldValue = metricValues.put(uuid, safeValue);
        ensureAutomaticMarkets();
        return oldValue == null || oldValue != safeValue;
    }

    public void appendSnapshot() {
        this.snapshotId++;
        this.lastSnapshotAt = System.currentTimeMillis();

        for (String metric : this.values.keySet()) {
            appendPoint(metric, total(metric));
        }

        ensureAutomaticMarkets();

        for (String id : this.marketMetrics.keySet()) {
            appendMarketPoint(id, marketIndex(id));
        }
    }

    public long snapshotId() {
        return Math.max(0L, this.snapshotId);
    }

    public long lastSnapshotAt() {
        return Math.max(0L, this.lastSnapshotAt);
    }

    public boolean defineMarket(String id, String metric, String name, int basePrice) {
        String cleanId = cleanMarketId(id);
        String cleanMetric = cleanMetric(metric);

        if (cleanId.isBlank() || cleanMetric.isBlank()) {
            return false;
        }

        boolean changed = !Objects.equals(this.marketMetrics.get(cleanId), cleanMetric)
                || !Objects.equals(this.marketNames.get(cleanId), name)
                || this.marketBasePrices.getOrDefault(cleanId, 100) != Math.max(1, basePrice);

        this.marketMetrics.put(cleanId, cleanMetric);
        this.marketNames.put(cleanId, name == null || name.isBlank() ? displayName(cleanId) : name);
        this.marketBasePrices.put(cleanId, Math.max(1, basePrice));
        this.marketHistory.computeIfAbsent(cleanId, ignored -> new ArrayDeque<>());

        if (changed) {
            appendMarketPoint(cleanId, marketIndex(cleanId));
        }

        return changed;
    }

    public boolean removeMarket(String id) {
        String cleanId = cleanMarketId(id);
        boolean changed = this.marketMetrics.remove(cleanId) != null;
        this.marketNames.remove(cleanId);
        this.marketBasePrices.remove(cleanId);
        this.marketHistory.remove(cleanId);
        return changed;
    }

    public List<String> marketDescriptions() {
        List<String> descriptions = new ArrayList<>();

        for (String id : this.marketMetrics.keySet()) {
            String metric = this.marketMetrics.getOrDefault(id, "");
            descriptions.add(id + " -> " + metric + " price " + marketIndex(id));
        }

        descriptions.sort(String::compareToIgnoreCase);
        return descriptions;
    }

    public List<KoilGlobalActivityRow> snapshotRows(int limit) {
        ensureAutomaticMarkets();
        List<KoilGlobalActivityRow> markets = new ArrayList<>();
        List<KoilGlobalActivityRow> activities = new ArrayList<>();

        for (String id : this.marketMetrics.keySet()) {
            String metric = this.marketMetrics.get(id);

            if (shouldSuppressAggregateMetric(metric)) {
                continue;
            }

            int index = marketIndex(id);
            Object[] leader = leader(metric);
            Deque<Integer> deque = this.marketHistory.computeIfAbsent(id, ignored -> new ArrayDeque<>());

            if (deque.isEmpty()) {
                appendMarketPoint(id, index);
            }

            int[] marketPoints = marketPoints(id);
            int change = marketPoints.length < 2 ? 0 : marketPoints[marketPoints.length - 1] - marketPoints[marketPoints.length - 2];
            markets.add(new KoilGlobalActivityRow("market", id, metric, this.marketNames.getOrDefault(id, displayName(id)), index, (String) leader[0], (Integer) leader[1], marketPoints, "server market", this.marketBasePrices.getOrDefault(id, 100), change, recentVolume(metric)));
        }

        for (String metric : this.values.keySet()) {
            if (shouldSuppressAggregateMetric(metric)) {
                continue;
            }

            int total = total(metric);
            Object[] leader = leader(metric);
            Deque<Integer> deque = this.history.computeIfAbsent(metric, ignored -> new ArrayDeque<>());

            if (deque.isEmpty()) {
                appendPoint(metric, total);
            }

            int[] points = points(metric);
            int change = points.length < 2 ? 0 : points[points.length - 1] - points[points.length - 2];
            activities.add(new KoilGlobalActivityRow("activity", metric, metric, displayName(metric), total, (String) leader[0], (Integer) leader[1], points, "server activity", 0, change, Math.max(0, change)));
        }

        markets.sort((a, b) -> {
            int priority = Integer.compare(rowPriority(b.metric()), rowPriority(a.metric()));

            if (priority != 0) {
                return priority;
            }

            return Integer.compare(b.value(), a.value());
        });
        activities.sort((a, b) -> {
            int priority = Integer.compare(rowPriority(b.metric()), rowPriority(a.metric()));

            if (priority != 0) {
                return priority;
            }

            return Integer.compare(b.value(), a.value());
        });
        int marketLimit = Math.min(markets.size(), Math.max(64, Math.round(limit * 0.55F)));
        int activityLimit = Math.max(0, limit - marketLimit);
        List<KoilGlobalActivityRow> rows = new ArrayList<>();
        rows.addAll(markets.subList(0, marketLimit));
        rows.addAll(activities.subList(0, Math.min(activities.size(), activityLimit)));

        if (rows.size() < limit && marketLimit < markets.size()) {
            int remaining = Math.min(markets.size() - marketLimit, limit - rows.size());
            rows.addAll(markets.subList(marketLimit, marketLimit + remaining));
        }

        if (rows.size() < limit && activityLimit < activities.size()) {
            int remaining = Math.min(activities.size() - activityLimit, limit - rows.size());
            rows.addAll(activities.subList(activityLimit, activityLimit + remaining));
        }

        return rows;
    }

    private boolean ensureAutomaticMarkets() {
        List<String> candidates = new ArrayList<>();

        for (String metric : this.values.keySet()) {
            if (shouldSuppressAggregateMetric(metric)) {
                continue;
            }

            if (isMarketMetric(metric) && total(metric) > 0) {
                candidates.add(metric);
            }
        }

        candidates.sort((a, b) -> Integer.compare(total(b), total(a)));
        boolean changed = false;
        int created = 0;

        for (String metric : candidates) {
            if (created >= MAX_AUTO_MARKETS) {
                break;
            }

            String id = "auto/" + metric;

            if (!this.marketMetrics.containsKey(id)) {
                this.marketMetrics.put(id, metric);
                this.marketNames.put(id, displayName(metric));
                this.marketBasePrices.put(id, baseForMetric(metric));
                this.marketHistory.put(id, new ArrayDeque<>());
                appendMarketPoint(id, marketIndex(id));
                changed = true;
            }

            created++;
        }

        return changed;
    }

    private boolean shouldSuppressAggregateMetric(String metric) {
        String key = aggregateDetailPrefix(metric);

        if (key.isBlank()) {
            return false;
        }

        int aggregateTotal = total(metric);

        if (aggregateTotal <= 0) {
            return false;
        }

        int detailTotal = 0;

        for (String candidate : this.values.keySet()) {
            if (candidate.equals(metric)) {
                continue;
            }

            if (candidate.startsWith(key)) {
                detailTotal += total(candidate);
            }
        }

        return detailTotal >= aggregateTotal;
    }

    private String aggregateDetailPrefix(String metric) {
        String clean = cleanMetric(metric);

        if (clean.equals("blocks_broken") || clean.equals("packet/blocks_broken")) {
            return clean.startsWith("packet/") ? "packet/block_broken/" : "block_broken/";
        }

        if (clean.equals("blocks_placed") || clean.equals("packet/blocks_placed")) {
            return clean.startsWith("packet/") ? "packet/block_place_intent/" : "block_placed/";
        }

        if (clean.equals("items_used")) {
            return "item_used/";
        }

        if (clean.equals("items_crafted")) {
            return "item_crafted/";
        }

        if (clean.equals("items_picked_up")) {
            return "item_picked_up/";
        }

        if (clean.equals("items_dropped")) {
            return "item_dropped/";
        }

        if (clean.equals("food_eaten")) {
            return "food_eaten/";
        }

        if (clean.equals("mobs_killed")) {
            return "mob_killed/";
        }

        if (clean.equals("entity_deaths") || clean.equals("killed_by_entities")) {
            return "killed_by/";
        }

        return "";
    }

    private int rowPriority(String metric) {
        String safe = metric == null ? "" : metric.toLowerCase(Locale.ROOT);

        if (safe.startsWith("food_eaten/") || safe.startsWith("food_use_intent/") || safe.equals("food_eaten")) {
            return 170;
        }

        if (safe.startsWith("processing/") || safe.startsWith("station/")) {
            return 165;
        }

        if (safe.startsWith("container_in/") || safe.startsWith("container_out/")) {
            return 162;
        }

        if (safe.startsWith("resource/") || isResourceMetric(safe)) {
            return 160;
        }

        if (safe.startsWith("block_broken/") || safe.startsWith("packet/block_broken/") || safe.equals("blocks_broken")) {
            return 150;
        }

        if (safe.startsWith("block_placed/") || safe.startsWith("block_place_intent/") || safe.startsWith("packet/block_place_intent/") || safe.equals("blocks_placed")) {
            return 145;
        }

        if (safe.startsWith("item_used/") || safe.startsWith("item_use_intent/") || safe.startsWith("item_crafted/") || safe.startsWith("item_picked_up/") || safe.startsWith("item_dropped/") || safe.equals("items_used") || safe.equals("items_crafted") || safe.equals("items_picked_up") || safe.equals("items_dropped")) {
            return 140;
        }

        if (safe.startsWith("mob_killed/") || safe.startsWith("entity_killed/") || safe.startsWith("killed_by/") || safe.equals("mobs_killed") || safe.equals("entity_deaths") || safe.equals("killed_by_entities")) {
            return 135;
        }

        if (safe.startsWith("market/")) {
            return 130;
        }

        if (safe.startsWith("transfer/")) {
            return 35;
        }

        if (safe.startsWith("loss/")) {
            return 60;
        }

        if (isMarketMetric(safe)) {
            return 100;
        }

        return 10;
    }

    private boolean isMarketMetric(String metric) {
        return metric.equals("blocks_broken")
                || metric.equals("blocks_placed")
                || metric.equals("items_used")
                || metric.equals("items_crafted")
                || metric.equals("items_picked_up")
                || metric.equals("items_dropped")
                || metric.equals("food_eaten")
                || metric.equals("mobs_killed")
                || metric.equals("entity_deaths")
                || metric.startsWith("processing/")
                || metric.startsWith("container_in/")
                || metric.startsWith("container_out/")
                || metric.startsWith("resource/")
                || metric.startsWith("block_broken/")
                || metric.startsWith("block_placed/")
                || metric.startsWith("item_used/")
                || metric.startsWith("item_crafted/")
                || metric.startsWith("item_picked_up/")
                || metric.startsWith("item_dropped/")
                || metric.startsWith("food_eaten/")
                || metric.startsWith("mob_killed/")
                || metric.startsWith("killed_by/")
                || metric.startsWith("market/")
                || metric.startsWith("packet/block_broken/")
                || metric.startsWith("packet/blocks_broken")
                || metric.startsWith("packet/block_place_intent/")
                || metric.startsWith("packet/item_use_intent/")
                || isResourceMetric(metric);
    }

    private boolean isResourceMetric(String metric) {
        String safe = metric == null ? "" : metric.toLowerCase(Locale.ROOT);
        String subject = resourceSubjectFromMetric(safe);

        if (!isResourceSubjectName(subject)) {
            return false;
        }

        if (isFinishedResourceProductName(subject)) {
            return false;
        }

        return true;
    }

    private String resourceSubjectFromMetric(String metric) {
        String safe = metric == null ? "" : metric.toLowerCase(Locale.ROOT);

        if (safe.startsWith("processing/fuel_supported_output/")) {
            String remainder = safe.substring("processing/fuel_supported_output/".length());
            String[] parts = remainder.split("/");
            return parts.length >= 2 ? parts[0] + "/" + parts[1] : remainder;
        }

        if (safe.contains("/")) {
            String[] parts = safe.split("/");

            for (int i = parts.length - 2; i >= 0; i--) {
                String namespace = parts[i];
                String path = parts[i + 1];

                if (!namespace.isBlank() && !path.isBlank() && !isMetricActionToken(namespace)) {
                    return namespace + "/" + path;
                }
            }
        }

        return safe;
    }

    private boolean isMetricActionToken(String value) {
        return value.equals("resource")
                || value.equals("processing")
                || value.equals("container_in")
                || value.equals("container_out")
                || value.equals("item")
                || value.equals("block")
                || value.equals("fuel_burned")
                || value.equals("smelt_input")
                || value.equals("smelt_output")
                || value.equals("fuel_supported_output")
                || value.equals("station_interact")
                || value.equals("block_broken")
                || value.equals("block_placed")
                || value.equals("block_place_intent")
                || value.equals("item_used")
                || value.equals("item_crafted")
                || value.equals("item_picked_up")
                || value.equals("item_dropped")
                || value.equals("packet");
    }

    private boolean isResourceSubjectName(String value) {
        String safe = value == null ? "" : value.toLowerCase(Locale.ROOT);
        String path = safe.contains("/") ? safe.substring(safe.lastIndexOf('/') + 1) : safe;

        if (path.isBlank()) {
            return false;
        }

        if (isFinishedResourceProductName(path)) {
            return false;
        }

        return path.equals("coal")
                || path.equals("charcoal")
                || path.equals("flint")
                || path.equals("clay_ball")
                || path.equals("amethyst_shard")
                || path.equals("quartz")
                || path.equals("nether_quartz")
                || path.equals("redstone")
                || path.equals("glowstone_dust")
                || path.equals("gunpowder")
                || path.equals("blaze_powder")
                || path.equals("prismarine_shard")
                || path.equals("prismarine_crystals")
                || path.equals("echo_shard")
                || path.equals("ancient_debris")
                || path.equals("netherite_scrap")
                || path.equals("iron_ingot")
                || path.equals("gold_ingot")
                || path.equals("copper_ingot")
                || path.equals("netherite_ingot")
                || path.equals("iron_nugget")
                || path.equals("gold_nugget")
                || path.equals("diamond")
                || path.equals("emerald")
                || path.equals("lapis_lazuli")
                || path.endsWith("_ore")
                || path.endsWith("_ingot")
                || path.endsWith("_nugget")
                || path.endsWith("_gem")
                || path.endsWith("_dust")
                || path.endsWith("_crystal")
                || path.endsWith("_crystals")
                || path.endsWith("_shard")
                || path.endsWith("_scrap")
                || path.endsWith("_alloy")
                || path.startsWith("raw_")
                || path.endsWith("_raw")
                || path.endsWith("_raw_material")
                || path.endsWith("_material")
                || path.equals("steel")
                || path.equals("silver")
                || path.equals("lead")
                || path.equals("tin")
                || path.equals("nickel")
                || path.equals("zinc")
                || path.equals("aluminum")
                || path.equals("platinum")
                || path.equals("uranium")
                || path.equals("osmium")
                || path.equals("bronze")
                || path.equals("brass")
                || path.equals("ruby")
                || path.equals("sapphire")
                || path.equals("steel_ingot")
                || path.equals("silver_ingot")
                || path.equals("lead_ingot")
                || path.equals("tin_ingot")
                || path.equals("nickel_ingot")
                || path.equals("zinc_ingot")
                || path.equals("aluminum_ingot")
                || path.equals("platinum_ingot")
                || path.equals("uranium_ingot")
                || path.equals("osmium_ingot")
                || path.equals("bronze_ingot")
                || path.equals("brass_ingot")
                || isResourceStorageBlockName(path);
    }

    private boolean isResourceStorageBlockName(String path) {
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

    private boolean isFinishedResourceProductName(String value) {
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

    private int baseForMetric(String metric) {
        if (metric.startsWith("packet/")) {
            return 70;
        }

        if (metric.startsWith("processing/")) {
            return 130;
        }

        if (metric.startsWith("container_in/") || metric.startsWith("container_out/")) {
            return 115;
        }

        if (isResourceMetric(metric)) {
            return 140;
        }

        if (metric.startsWith("food_eaten/")) {
            return 80;
        }

        if (metric.startsWith("mob_killed/") || metric.equals("mobs_killed")) {
            return 120;
        }

        if (metric.startsWith("killed_by/") || metric.equals("entity_deaths")) {
            return 120;
        }

        if (metric.startsWith("block_broken/") || metric.startsWith("block_placed/")) {
            return 100;
        }

        if (metric.startsWith("item_crafted/") || metric.equals("items_crafted")) {
            return 110;
        }

        return 100;
    }

    private int marketIndex(String id) {
        String metric = this.marketMetrics.get(id);
        int base = Math.max(1, this.marketBasePrices.getOrDefault(id, 100));
        int[] points = points(metric);
        int total = total(metric);

        if (points.length < 2) {
            return Math.max(1, base + Math.min(50, total / 128));
        }

        int latestDelta = Math.max(0, points[points.length - 1] - points[points.length - 2]);
        int previousDelta = points.length < 3 ? latestDelta : Math.max(0, points[points.length - 2] - points[points.length - 3]);
        int deltaTotal = 0;
        int deltaCount = 0;

        for (int i = 1; i < points.length; i++) {
            deltaTotal += Math.max(0, points[i] - points[i - 1]);
            deltaCount++;
        }

        int average = deltaCount == 0 ? 0 : deltaTotal / deltaCount;
        int pressure = average <= 0 ? latestDelta : latestDelta * 100 / Math.max(1, average);
        int momentum = latestDelta - previousDelta;
        int pressureScore = Math.max(-50, Math.min(120, (pressure - 100) / 2));
        int momentumScore = average <= 0 ? 0 : Math.max(-35, Math.min(70, momentum * 20 / Math.max(1, average)));
        int scaleScore = Math.min(40, total / 1024);
        return Math.max(1, base + pressureScore + momentumScore + scaleScore);
    }

    private int recentVolume(String metric) {
        int[] points = points(metric);

        if (points.length < 2) {
            return total(metric);
        }

        return Math.max(0, points[points.length - 1] - points[points.length - 2]);
    }

    private int total(String metric) {
        int total = 0;
        Map<UUID, Integer> metricValues = this.values.get(metric);

        if (metricValues == null) {
            return 0;
        }

        for (Integer value : metricValues.values()) {
            total += Math.max(0, value);
        }

        return total;
    }

    private Object[] leader(String metric) {
        String name = "none";
        int value = 0;
        Map<UUID, Integer> metricValues = this.values.get(metric);

        if (metricValues == null) {
            return new Object[]{name, value};
        }

        for (Map.Entry<UUID, Integer> entry : metricValues.entrySet()) {
            int candidate = Math.max(0, entry.getValue());

            if (candidate > value) {
                value = candidate;
                name = this.names.getOrDefault(entry.getKey(), entry.getKey().toString());
            }
        }

        return new Object[]{name, value};
    }

    private void appendPoint(String metric, int value) {
        appendTo(this.history.computeIfAbsent(metric, ignored -> new ArrayDeque<>()), value, 0);
    }

    private void appendMarketPoint(String id, int value) {
        appendTo(this.marketHistory.computeIfAbsent(id, ignored -> new ArrayDeque<>()), value, 1);
    }

    private void appendTo(Deque<Integer> deque, int value, int minimum) {
        int safeValue = Math.max(minimum, value);

        if (!deque.isEmpty() && Objects.equals(deque.peekLast(), safeValue)) {
            return;
        }

        deque.add(safeValue);

        while (deque.size() > MAX_POINTS) {
            deque.removeFirst();
        }
    }

    private int[] points(String metric) {
        Deque<Integer> deque = this.history.get(metric);

        if (deque == null || deque.isEmpty()) {
            return new int[]{total(metric)};
        }

        int[] points = new int[deque.size()];
        int index = 0;

        for (Integer point : deque) {
            points[index++] = Math.max(0, point);
        }

        return points;
    }

    private int[] marketPoints(String id) {
        Deque<Integer> deque = this.marketHistory.get(id);

        if (deque == null || deque.isEmpty()) {
            return new int[]{marketIndex(id)};
        }

        int[] points = new int[deque.size()];
        int index = 0;

        for (Integer point : deque) {
            points[index++] = Math.max(1, point);
        }

        return points;
    }

    private NbtList writeHistory(Map<String, Deque<Integer>> source, boolean minimumOne) {
        NbtList list = new NbtList();

        for (Map.Entry<String, Deque<Integer>> entry : source.entrySet()) {
            NbtCompound item = new NbtCompound();
            NbtList points = new NbtList();
            item.putString("metric", entry.getKey());

            for (Integer point : entry.getValue()) {
                points.add(NbtInt.of(Math.max(minimumOne ? 1 : 0, point)));
            }

            item.put("points", points);
            list.add(item);
        }

        return list;
    }

    private void readHistory(NbtList list, Map<String, Deque<Integer>> target, boolean minimumOne) {
        for (int i = 0; i < list.size(); i++) {
            NbtCompound item = list.getCompound(i);
            String metric = item.getString("metric");
            NbtList points = item.getList("points", NbtElement.INT_TYPE);
            Deque<Integer> deque = new ArrayDeque<>();

            for (int point = 0; point < points.size(); point++) {
                deque.add(Math.max(minimumOne ? 1 : 0, points.getInt(point)));
            }

            target.put(metric, deque);
        }
    }

    private String cleanMetric(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace(':', '/');
    }

    private String cleanMarketId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace(':', '/');
    }

    private String displayName(String value) {
        String cleaned = value == null ? "market" : value;
        int slash = cleaned.lastIndexOf('/');

        if (slash >= 0 && slash < cleaned.length() - 1) {
            cleaned = cleaned.substring(slash + 1);
        }

        cleaned = cleaned.replace("packet/", "packet ").replace('_', ' ').replace('-', ' ');
        StringBuilder builder = new StringBuilder();

        for (String part : cleaned.split(" ")) {
            if (part.isBlank()) {
                continue;
            }

            if (builder.length() > 0) {
                builder.append(' ');
            }

            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }

        return builder.length() == 0 ? "Market" : builder.toString();
    }
}
