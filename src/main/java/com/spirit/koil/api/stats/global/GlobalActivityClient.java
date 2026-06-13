package com.spirit.koil.api.stats.global;

import com.google.gson.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import com.spirit.koil.api.stats.global.client.MarketHudState;

import java.nio.file.*;
import java.util.*;

@Environment(EnvType.CLIENT)
public final class GlobalActivityClient {
    private static final List<KoilGlobalActivityRow> SERVER_ROWS = new ArrayList<>();
    private static final Map<String, Integer> OBSERVED_TOTALS = new LinkedHashMap<>();
    private static final Map<String, String> OBSERVED_LEADERS = new LinkedHashMap<>();
    private static final Map<String, Integer> OBSERVED_LEADER_VALUES = new LinkedHashMap<>();
    private static final Map<String, Deque<Integer>> OBSERVED_HISTORY = new LinkedHashMap<>();
    private static final Map<String, String> OBSERVED_HELD_CACHE = new LinkedHashMap<>();
    private static final int SYNC_MAGIC = 1263487308;
    private static final int MAX_SYNC_ROWS = 768;
    private static long updatedAt;
    private static long observedUpdatedAt;
    private static long lastServerSnapshotId = -1L;
    private static long lastServerSnapshotCreatedAt;
    private static boolean registered;
    private static boolean hasServerData;
    private static String contextKey = "menu";
    private static String loadedContextKey = "";
    private static int tickCounter;
    private static boolean observedDirty;
    private static long lastSnapshotRequestAt;

    private GlobalActivityClient() {
    }

    public static void registerClient() {
        if (registered) {
            return;
        }

        registered = true;
        ClientPlayNetworking.registerGlobalReceiver(GlobalActivityApi.CHANNEL, (client, handler, buf, responseSender) -> {
            Object[] payload = readSnapshot(buf);
            client.execute(() -> acceptServerSnapshot(client, payload));
        });
        ClientPlayNetworking.registerGlobalReceiver(GlobalActivityApi.MARKET_HUD_CHANNEL, (client, handler, buf, responseSender) -> {
            KoilMarketHudSnapshot snapshot = KoilMarketHudSnapshot.read(buf);
            client.execute(() -> {
                if ("hide".equalsIgnoreCase(snapshot.mode())) {
                    MarketHudState.hide();
                } else {
                    MarketHudState.show(snapshot);
                }
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(GlobalActivityClient::clientTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> switchContext(currentContextKey(client), true)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> resetToMenuContext()));
    }


    public static void requestServerSnapshot() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null || client.player == null || client.getNetworkHandler() == null) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now - lastSnapshotRequestAt < 1500L) {
            return;
        }

        try {
            if (!ClientPlayNetworking.canSend(GlobalActivityApi.REQUEST_CHANNEL)) {
                return;
            }

            lastSnapshotRequestAt = now;
            ClientPlayNetworking.send(GlobalActivityApi.REQUEST_CHANNEL, PacketByteBufs.create());
        } catch (Exception ignored) {
        }
    }

    public static List<KoilGlobalActivityRow> rows() {
        List<KoilGlobalActivityRow> rows = new ArrayList<>();

        synchronized (SERVER_ROWS) {
            rows.addAll(SERVER_ROWS);

            if (hasServerData && !SERVER_ROWS.isEmpty()) {
                return rows;
            }
        }

        rows.addAll(observedRows(true, true));
        return rows;
    }

    public static List<KoilGlobalActivityRow> marketRows() {
        List<KoilGlobalActivityRow> result = new ArrayList<>();

        synchronized (SERVER_ROWS) {
            for (KoilGlobalActivityRow row : SERVER_ROWS) {
                if (row.market()) {
                    result.add(row);
                }
            }

            if (hasServerData && !SERVER_ROWS.isEmpty()) {
                result.sort((a, b) -> Integer.compare(b.value(), a.value()));
                return result;
            }
        }

        result.addAll(observedRows(true, false));
        result.sort((a, b) -> Integer.compare(b.value(), a.value()));
        return result;
    }

    public static List<KoilGlobalActivityRow> activityRows() {
        List<KoilGlobalActivityRow> result = new ArrayList<>();

        synchronized (SERVER_ROWS) {
            for (KoilGlobalActivityRow row : SERVER_ROWS) {
                if (row.activity()) {
                    result.add(row);
                }
            }

            if (hasServerData && !SERVER_ROWS.isEmpty()) {
                result.sort((a, b) -> Integer.compare(b.value(), a.value()));
                return result;
            }
        }

        result.addAll(observedRows(false, true));
        result.sort((a, b) -> Integer.compare(b.value(), a.value()));
        return result;
    }

    public static boolean hasData() {
        return hasServerData() || hasObservedData();
    }

    public static boolean hasServerData() {
        synchronized (SERVER_ROWS) {
            return hasServerData && !SERVER_ROWS.isEmpty();
        }
    }

    public static boolean hasObservedData() {
        synchronized (OBSERVED_TOTALS) {
            return !OBSERVED_TOTALS.isEmpty();
        }
    }

    public static long updatedAt() {
        synchronized (SERVER_ROWS) {
            return Math.max(updatedAt, observedUpdatedAt);
        }
    }

    public static String contextKey() {
        return contextKey;
    }

    private static Object[] readSnapshot(PacketByteBuf buf) {
        long snapshotId = -1L;
        long snapshotCreatedAt = 0L;
        int first = Math.max(0, buf.readVarInt());
        int size;

        if (first == SYNC_MAGIC) {
            buf.readVarInt();
            snapshotId = buf.readLong();
            snapshotCreatedAt = buf.readLong();
            size = Math.min(MAX_SYNC_ROWS, Math.max(0, buf.readVarInt()));
        } else {
            snapshotId = System.currentTimeMillis();
            snapshotCreatedAt = snapshotId;
            size = Math.min(MAX_SYNC_ROWS, first);
        }

        List<KoilGlobalActivityRow> rows = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            String type = buf.readString(32);
            String id = buf.readString(192);
            String metric = buf.readString(192);
            String title = buf.readString(96);
            int value = buf.readVarInt();
            String leader = buf.readString(64);
            int leaderValue = buf.readVarInt();
            int length = Math.min(KoilMarketSeriesWindow.historySampleLimit(), Math.max(0, buf.readVarInt()));
            int[] points = new int[length];

            for (int point = 0; point < length; point++) {
                points[point] = buf.readVarInt();
            }

            String source = buf.readString(96);
            int base = buf.readVarInt();
            int change = buf.readInt();
            int volume = buf.readVarInt();
            rows.add(new KoilGlobalActivityRow(type, id, metric, title, value, leader, leaderValue, points, source, base, change, volume, currentContextKey(MinecraftClient.getInstance()), 100, true));
        }

        return new Object[]{rows, snapshotId, snapshotCreatedAt};
    }

    private static void acceptServerSnapshot(MinecraftClient client, Object[] payload) {
        String key = currentContextKey(client);

        if (!Objects.equals(key, contextKey)) {
            switchContext(key, false);
        }

        List<KoilGlobalActivityRow> rows = (List<KoilGlobalActivityRow>) payload[0];
        long snapshotId = (Long) payload[1];
        long snapshotCreatedAt = (Long) payload[2];

        synchronized (SERVER_ROWS) {
            if (!SERVER_ROWS.isEmpty() && snapshotId >= 0L && snapshotId < lastServerSnapshotId) {
                return;
            }

            if (!SERVER_ROWS.isEmpty() && snapshotId == lastServerSnapshotId && snapshotCreatedAt <= lastServerSnapshotCreatedAt) {
                return;
            }

            SERVER_ROWS.clear();
            SERVER_ROWS.addAll(rows);
            updatedAt = System.currentTimeMillis();
            lastServerSnapshotId = snapshotId;
            lastServerSnapshotCreatedAt = snapshotCreatedAt;
            hasServerData = !rows.isEmpty();
        }

        if (!rows.isEmpty()) {
            saveServerCache(rows);
        }
    }

    private static void clearServerRows() {
        synchronized (SERVER_ROWS) {
            SERVER_ROWS.clear();
            hasServerData = false;
            updatedAt = 0L;
            lastServerSnapshotId = -1L;
            lastServerSnapshotCreatedAt = 0L;
        }
    }

    private static void switchContext(String key, boolean loadCache) {
        String safeKey = key == null || key.isBlank() ? "menu" : key;

        if (Objects.equals(safeKey, contextKey) && Objects.equals(loadedContextKey, contextKey)) {
            return;
        }

        saveObserved();
        clearServerRows();
        contextKey = safeKey;
        loadObserved();

        if (loadCache) {
            loadServerCache();
        }
    }

    private static void resetToMenuContext() {
        saveObserved();
        clearServerRows();
        synchronized (OBSERVED_TOTALS) {
            OBSERVED_TOTALS.clear();
            OBSERVED_LEADERS.clear();
            OBSERVED_LEADER_VALUES.clear();
            OBSERVED_HISTORY.clear();
            OBSERVED_HELD_CACHE.clear();
        }
        contextKey = "menu";
        loadedContextKey = "";
        observedDirty = false;
    }

    private static void clientTick(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) {
            if (!"menu".equals(contextKey)) {
                resetToMenuContext();
            }

            return;
        }

        String key = currentContextKey(client);

        if (!Objects.equals(key, contextKey)) {
            switchContext(key, true);
        } else if (!Objects.equals(loadedContextKey, contextKey)) {
            loadObserved();
            loadServerCache();
        }

        tickCounter++;

        if (tickCounter % 100 != 0) {
            return;
        }

        int visiblePlayers = 0;
        boolean changed = false;

        String selfName = client.player.getGameProfile() == null ? "local player" : client.player.getGameProfile().getName();
        changed |= recordHeldItem(client.player.getMainHandStack().getItem(), selfName, "client_observed/self_mainhand", client.player.getUuid().toString() + ":self_mainhand", 1);
        changed |= recordHeldItem(client.player.getOffHandStack().getItem(), selfName, "client_observed/self_offhand", client.player.getUuid().toString() + ":self_offhand", 1);

        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (player == null || client.player.getUuid().equals(player.getUuid())) {
                continue;
            }

            visiblePlayers++;
            String name = player.getGameProfile() == null ? "visible player" : player.getGameProfile().getName();
            recordObserved("client_observed/player_visible", name, 1);
            changed |= recordHeldItem(player.getMainHandStack().getItem(), name, "client_observed/mainhand", player.getUuid().toString() + ":mainhand", 1);
            changed |= recordHeldItem(player.getOffHandStack().getItem(), name, "client_observed/offhand", player.getUuid().toString() + ":offhand", 1);
        }

        if (visiblePlayers > 0) {
            recordObserved("client_observed/player_presence", "visible players", visiblePlayers);
            changed = true;
        }

        if (changed) {
            appendObservedSnapshot();
            observedDirty = true;
            observedUpdatedAt = System.currentTimeMillis();
        }

        if (tickCounter % 1200 == 0 && observedDirty) {
            saveObserved();
            observedDirty = false;
        }
    }

    private static boolean recordHeldItem(Item item, String playerName, String prefix, String holderKey, int amount) {
        if (item == null || item == Items.AIR) {
            return false;
        }

        Identifier id = Registries.ITEM.getId(item);
        String metric = prefix + "/" + id.getNamespace() + "/" + id.getPath();
        String cacheKey = holderKey == null ? metric : holderKey;
        String previous = OBSERVED_HELD_CACHE.get(cacheKey);

        if (metric.equals(previous)) {
            recordObserved(metric + "/active", playerName, amount);
            return true;
        }

        OBSERVED_HELD_CACHE.put(cacheKey, metric);
        recordObserved(metric, playerName, amount);
        recordObserved(metric + "/active", playerName, amount);
        return true;
    }

    private static void recordObserved(String metric, String leader, int amount) {
        if (metric == null || metric.isBlank() || amount <= 0) {
            return;
        }

        String cleanMetric = clean(metric);

        synchronized (OBSERVED_TOTALS) {
            int current = OBSERVED_TOTALS.getOrDefault(cleanMetric, 0);
            int next = current + amount;
            OBSERVED_TOTALS.put(cleanMetric, next);
            int leaderValue = OBSERVED_LEADER_VALUES.getOrDefault(cleanMetric + "|" + leader, 0) + amount;
            OBSERVED_LEADER_VALUES.put(cleanMetric + "|" + leader, leaderValue);

            if (leaderValue >= OBSERVED_LEADER_VALUES.getOrDefault(cleanMetric + "|" + OBSERVED_LEADERS.getOrDefault(cleanMetric, ""), 0)) {
                OBSERVED_LEADERS.put(cleanMetric, leader == null || leader.isBlank() ? "observed" : leader);
            }
        }
    }

    private static void appendObservedSnapshot() {
        synchronized (OBSERVED_TOTALS) {
            for (Map.Entry<String, Integer> entry : OBSERVED_TOTALS.entrySet()) {
                Deque<Integer> history = OBSERVED_HISTORY.computeIfAbsent(entry.getKey(), ignored -> new ArrayDeque<>());
                int value = Math.max(0, entry.getValue());

                if (history.isEmpty() || !Objects.equals(history.peekLast(), value)) {
                    history.add(value);
                }

                while (history.size() > KoilMarketSeriesWindow.historySampleLimit()) {
                    history.removeFirst();
                }
            }
        }
    }

    private static List<KoilGlobalActivityRow> observedRows(boolean markets, boolean activities) {
        List<KoilGlobalActivityRow> rows = new ArrayList<>();

        synchronized (OBSERVED_TOTALS) {
            for (Map.Entry<String, Integer> entry : OBSERVED_TOTALS.entrySet()) {
                String metric = entry.getKey();
                int total = Math.max(0, entry.getValue());

                if (total <= 0) {
                    continue;
                }

                int[] points = observedPoints(metric, total);
                int change = points.length < 2 ? 0 : points[points.length - 1] - points[points.length - 2];
                String leader = OBSERVED_LEADERS.getOrDefault(metric, "observed");
                int leaderValue = OBSERVED_LEADER_VALUES.getOrDefault(metric + "|" + leader, 0);

                if (activities) {
                    rows.add(new KoilGlobalActivityRow("activity", "client/" + metric, metric, displayName(metric), total, leader, leaderValue, points, observedSourceFor(metric), 0, change, Math.max(0, change), contextKey, observedConfidenceFor(metric), false));
                }

                if (markets && isMarketMetric(metric)) {
                    int base = baseFor(metric);
                    int price = marketIndex(base, total, points);
                    int marketChange = points.length < 2 ? 0 : marketIndex(base, points[points.length - 1], points) - marketIndex(base, points[points.length - 2], points);
                    int[] marketPoints = marketPoints(base, points);
                    rows.add(new KoilGlobalActivityRow("market", "client_market/" + metric, metric, displayName(metric), price, leader, leaderValue, marketPoints, observedSourceFor(metric) + " market", base, marketChange, Math.max(0, change), contextKey, observedConfidenceFor(metric), false));
                }
            }
        }

        rows.sort((a, b) -> {
            int priority = Integer.compare(metricPriority(b.metric()), metricPriority(a.metric()));

            if (priority != 0) {
                return priority;
            }

            return Integer.compare(b.value(), a.value());
        });
        return rows;
    }

    private static int[] observedPoints(String metric, int fallback) {
        Deque<Integer> deque = OBSERVED_HISTORY.get(metric);

        if (deque == null || deque.isEmpty()) {
            return new int[]{fallback};
        }

        int[] points = new int[deque.size()];
        int index = 0;

        for (Integer value : deque) {
            points[index++] = Math.max(0, value);
        }

        return points;
    }

    private static int[] marketPoints(int base, int[] source) {
        if (source == null || source.length == 0) {
            return new int[]{base};
        }

        int[] points = new int[source.length];

        for (int i = 0; i < source.length; i++) {
            int[] window = Arrays.copyOf(source, i + 1);
            points[i] = marketIndex(base, source[i], window);
        }

        return points;
    }

    private static int marketIndex(int base, int total, int[] points) {
        if (points == null || points.length < 2) {
            return Math.max(1, base + Math.min(50, total / 24));
        }

        int latest = points[points.length - 1];
        int previous = points[points.length - 2];
        int delta = Math.max(0, latest - previous);
        int average = 0;

        for (int i = 1; i < points.length; i++) {
            average += Math.max(0, points[i] - points[i - 1]);
        }

        average = average / Math.max(1, points.length - 1);
        int pressure = average <= 0 ? delta * 4 : delta * 100 / Math.max(1, average);
        int pressureScore = Math.max(-30, Math.min(90, (pressure - 100) / 2));
        int scaleScore = Math.min(40, total / 64);
        return Math.max(1, base + pressureScore + scaleScore);
    }

    private static boolean isMarketMetric(String metric) {
        String value = metric == null ? "" : metric;
        return value.contains("block_broken") || value.contains("block_place") || value.contains("item_use") || value.contains("item_used") || value.contains("food") || value.contains("mob") || value.contains("mainhand") || value.contains("offhand");
    }

    private static int baseFor(String metric) {
        String value = metric == null ? "" : metric;

        if (value.contains("food")) {
            return 80;
        }

        if (value.contains("mob")) {
            return 120;
        }

        if (value.contains("mainhand") || value.contains("offhand")) {
            return 90;
        }

        if (value.contains("item")) {
            return 95;
        }

        return 100;
    }

    private static int metricPriority(String metric) {
        String value = metric == null ? "" : metric;

        if (value.contains("block_broken") || value.contains("blocks_broken")) {
            return 100;
        }

        if (value.contains("block_place") || value.contains("blocks_placed")) {
            return 95;
        }

        if (value.contains("item_use") || value.contains("item_used")) {
            return 90;
        }

        if (value.contains("food") || value.contains("mob")) {
            return 85;
        }

        if (value.contains("mainhand") || value.contains("offhand")) {
            return 75;
        }

        if (value.contains("player_presence") || value.contains("player_visible")) {
            return 20;
        }

        return 10;
    }

    private static String observedSourceFor(String metric) {
        return metricPriority(metric) >= 75 ? "client observed activity" : "client observed developer feed";
    }

    private static int observedConfidenceFor(String metric) {
        int priority = metricPriority(metric);

        if (priority >= 90) {
            return 45;
        }

        if (priority >= 75) {
            return 40;
        }

        return 25;
    }

    private static void loadObserved() {
        synchronized (OBSERVED_TOTALS) {
            OBSERVED_TOTALS.clear();
            OBSERVED_LEADERS.clear();
            OBSERVED_LEADER_VALUES.clear();
            OBSERVED_HISTORY.clear();
            OBSERVED_HELD_CACHE.clear();
            loadedContextKey = contextKey;
        }

        Path path = observedPath();

        if (!Files.isRegularFile(path)) {
            return;
        }

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path));

            if (!parsed.isJsonObject()) {
                return;
            }

            JsonObject root = parsed.getAsJsonObject();
            JsonObject totals = root.getAsJsonObject("totals");
            JsonObject leaders = root.getAsJsonObject("leaders");
            JsonObject history = root.getAsJsonObject("history");

            synchronized (OBSERVED_TOTALS) {
                if (totals != null) {
                    for (Map.Entry<String, JsonElement> entry : totals.entrySet()) {
                        OBSERVED_TOTALS.put(entry.getKey(), Math.max(0, entry.getValue().getAsInt()));
                    }
                }

                if (leaders != null) {
                    for (Map.Entry<String, JsonElement> entry : leaders.entrySet()) {
                        OBSERVED_LEADERS.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }

                if (history != null) {
                    for (Map.Entry<String, JsonElement> entry : history.entrySet()) {
                        if (!entry.getValue().isJsonArray()) {
                            continue;
                        }

                        Deque<Integer> deque = new ArrayDeque<>();

                        for (JsonElement element : entry.getValue().getAsJsonArray()) {
                            deque.add(Math.max(0, element.getAsInt()));
                        }

                        OBSERVED_HISTORY.put(entry.getKey(), deque);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveObserved() {
        try {
            Path path = observedPath();
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            JsonObject totals = new JsonObject();
            JsonObject leaders = new JsonObject();
            JsonObject history = new JsonObject();

            synchronized (OBSERVED_TOTALS) {
                for (Map.Entry<String, Integer> entry : OBSERVED_TOTALS.entrySet()) {
                    totals.addProperty(entry.getKey(), Math.max(0, entry.getValue()));
                }

                for (Map.Entry<String, String> entry : OBSERVED_LEADERS.entrySet()) {
                    leaders.addProperty(entry.getKey(), entry.getValue());
                }

                for (Map.Entry<String, Deque<Integer>> entry : OBSERVED_HISTORY.entrySet()) {
                    JsonArray array = new JsonArray();

                    for (Integer point : entry.getValue()) {
                        array.add(Math.max(0, point));
                    }

                    history.add(entry.getKey(), array);
                }
            }

            root.addProperty("context", contextKey);
            root.add("totals", totals);
            root.add("leaders", leaders);
            root.add("history", history);
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception ignored) {
        }
    }

    private static void loadServerCache() {
        Path path = serverCachePath();

        if (!Files.isRegularFile(path)) {
            return;
        }

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path));

            if (!parsed.isJsonObject()) {
                return;
            }

            JsonObject root = parsed.getAsJsonObject();
            String context = root.has("context") ? root.get("context").getAsString() : "";

            if (!Objects.equals(context, contextKey)) {
                return;
            }

            JsonArray rows = root.getAsJsonArray("rows");

            if (rows == null) {
                return;
            }

            List<KoilGlobalActivityRow> loaded = new ArrayList<>();

            for (JsonElement element : rows) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject row = element.getAsJsonObject();
                loaded.add(new KoilGlobalActivityRow(
                        stringValue(row, "type", "activity"),
                        stringValue(row, "id", "cached"),
                        stringValue(row, "metric", "cached"),
                        stringValue(row, "title", "Cached Market"),
                        intValue(row, "value", 0),
                        stringValue(row, "leader", "server"),
                        intValue(row, "leaderValue", 0),
                        intArray(row, "points"),
                        stringValue(row, "source", "server exact cached"),
                        intValue(row, "base", 0),
                        intValue(row, "change", 0),
                        intValue(row, "volume", 0),
                        contextKey,
                        intValue(row, "confidence", 100),
                        boolValue(row, "authoritative", true)
                ));
            }

            if (!loaded.isEmpty()) {
                synchronized (SERVER_ROWS) {
                    if (SERVER_ROWS.isEmpty()) {
                        SERVER_ROWS.addAll(loaded);
                        hasServerData = true;
                        updatedAt = root.has("updatedAt") ? root.get("updatedAt").getAsLong() : System.currentTimeMillis();
                        lastServerSnapshotId = root.has("snapshotId") ? root.get("snapshotId").getAsLong() : -1L;
                        lastServerSnapshotCreatedAt = root.has("snapshotCreatedAt") ? root.get("snapshotCreatedAt").getAsLong() : updatedAt;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveServerCache(List<KoilGlobalActivityRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        try {
            Path path = serverCachePath();
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            JsonArray array = new JsonArray();

            for (KoilGlobalActivityRow row : rows) {
                JsonObject object = new JsonObject();
                object.addProperty("type", row.type());
                object.addProperty("id", row.id());
                object.addProperty("metric", row.metric());
                object.addProperty("title", row.title());
                object.addProperty("value", row.value());
                object.addProperty("leader", row.leader());
                object.addProperty("leaderValue", row.leaderValue());
                object.add("points", intArray(row.pointsView()));
                object.addProperty("source", row.source());
                object.addProperty("base", row.base());
                object.addProperty("change", row.change());
                object.addProperty("volume", row.volume());
                object.addProperty("confidence", row.confidence());
                object.addProperty("authoritative", row.authoritative());
                array.add(object);
            }

            root.addProperty("context", contextKey);
            root.addProperty("source", "server_exact_cache");
            root.addProperty("snapshotId", Math.max(-1L, lastServerSnapshotId));
            root.addProperty("snapshotCreatedAt", Math.max(0L, lastServerSnapshotCreatedAt));
            root.addProperty("updatedAt", System.currentTimeMillis());
            root.add("rows", array);
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception ignored) {
        }
    }

    private static JsonArray intArray(int[] values) {
        JsonArray array = new JsonArray();

        if (values != null) {
            for (int value : values) {
                array.add(value);
            }
        }

        return array;
    }

    private static int[] intArray(JsonObject object, String key) {
        JsonArray array = object.getAsJsonArray(key);

        if (array == null) {
            return new int[0];
        }

        int[] out = new int[array.size()];

        for (int i = 0; i < array.size(); i++) {
            out[i] = array.get(i).getAsInt();
        }

        return out;
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element == null || !element.isJsonPrimitive() ? fallback : element.getAsString();
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        try {
            JsonElement element = object.get(key);
            return element == null || !element.isJsonPrimitive() ? fallback : element.getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean boolValue(JsonObject object, String key, boolean fallback) {
        try {
            JsonElement element = object.get(key);
            return element == null || !element.isJsonPrimitive() ? fallback : element.getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Path serverCachePath() {
        return Path.of("./koil/sys/stats/server_cache", clean(contextKey) + ".json");
    }

    private static Path observedPath() {
        return Path.of("./koil/sys/stats/client_observed", clean(contextKey) + ".json");
    }

    private static String currentContextKey(MinecraftClient client) {
        if (client == null) {
            return "menu";
        }

        try {
            if (client.getCurrentServerEntry() != null && client.getCurrentServerEntry().address != null) {
                return "server_" + clean(client.getCurrentServerEntry().address);
            }
        } catch (Exception ignored) {
        }

        try {
            if (client.getServer() != null) {
                return "singleplayer_" + clean(client.getServer().getSaveProperties().getLevelName());
            }
        } catch (Exception ignored) {
        }

        return "local_" + clean(client.getSession() == null ? "unknown" : client.getSession().getUsername());
    }

    private static String clean(String value) {
        String safe = value == null || value.isBlank() ? "unknown" : value.toLowerCase(Locale.ROOT).trim();
        return safe.replace(':', '_').replace('/', '_').replace('\\', '_').replace(' ', '_').replace('.', '_');
    }

    private static String displayName(String value) {
        String clean = value == null ? "activity" : value.replace("client_observed/", "").replace("observed/", "").replace("packet/", "").replace("self_mainhand", "local main hand").replace("self_offhand", "local off hand").replace("mainhand", "main hand").replace("offhand", "off hand").replace("active", "active demand").replace('_', ' ').replace('/', ' ');
        StringBuilder builder = new StringBuilder();

        for (String part : clean.split(" ")) {
            if (part.isBlank()) {
                continue;
            }

            if (builder.length() > 0) {
                builder.append(' ');
            }

            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }

        return builder.length() == 0 ? "Activity" : builder.toString();
    }
}
