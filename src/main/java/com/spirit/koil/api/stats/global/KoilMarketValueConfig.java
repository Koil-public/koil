package com.spirit.koil.api.stats.global;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public final class KoilMarketValueConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static KoilMarketValueConfig current;
    private final boolean momensEnabled;
    private final String momensIcon;
    private final List<String> reserveItems;
    private final int marketTickSeconds;
    private final double priceSmoothing;
    private final double antiSpamStrength;
    private final int minimumConfidenceToShowPrice;
    private final boolean allowClientEstimatedMarkets;
    private final boolean valueGraphModeEnabled;
    private final double uniquePlayerWeight;
    private final double volatilityPenalty;
    private final double lowSamplePenalty;
    private final boolean dynamicReserveBase;
    private final boolean physicalMomensCurrency;

    public KoilMarketValueConfig(boolean momensEnabled, String momensIcon, List<String> reserveItems, int marketTickSeconds, double priceSmoothing, double antiSpamStrength, int minimumConfidenceToShowPrice, boolean allowClientEstimatedMarkets, boolean valueGraphModeEnabled, double uniquePlayerWeight, double volatilityPenalty, double lowSamplePenalty) {
        this(momensEnabled, momensIcon, reserveItems, marketTickSeconds, priceSmoothing, antiSpamStrength, minimumConfidenceToShowPrice, allowClientEstimatedMarkets, valueGraphModeEnabled, uniquePlayerWeight, volatilityPenalty, lowSamplePenalty, true, true);
    }

    public KoilMarketValueConfig(boolean momensEnabled, String momensIcon, List<String> reserveItems, int marketTickSeconds, double priceSmoothing, double antiSpamStrength, int minimumConfidenceToShowPrice, boolean allowClientEstimatedMarkets, boolean valueGraphModeEnabled, double uniquePlayerWeight, double volatilityPenalty, double lowSamplePenalty, boolean dynamicReserveBase, boolean physicalMomensCurrency) {
        this.momensEnabled = momensEnabled;
        this.momensIcon = momensIcon == null || momensIcon.isBlank() ? "ƒ" : momensIcon;
        this.reserveItems = new ArrayList<>(reserveItems == null || reserveItems.isEmpty() ? defaultReserveItems() : reserveItems);
        this.marketTickSeconds = Math.max(5, marketTickSeconds);
        this.priceSmoothing = clamp(priceSmoothing, 0.0D, 0.98D);
        this.antiSpamStrength = clamp(antiSpamStrength, 0.0D, 1.0D);
        this.minimumConfidenceToShowPrice = Math.max(0, Math.min(100, minimumConfidenceToShowPrice));
        this.allowClientEstimatedMarkets = allowClientEstimatedMarkets;
        this.valueGraphModeEnabled = valueGraphModeEnabled;
        this.uniquePlayerWeight = clamp(uniquePlayerWeight, 0.0D, 1.0D);
        this.volatilityPenalty = clamp(volatilityPenalty, 0.0D, 1.0D);
        this.lowSamplePenalty = clamp(lowSamplePenalty, 0.0D, 1.0D);
        this.dynamicReserveBase = dynamicReserveBase;
        this.physicalMomensCurrency = physicalMomensCurrency;
    }

    public static KoilMarketValueConfig current() {
        if (current == null) {
            current = load();
        }

        return current;
    }

    public static KoilMarketValueConfig reload() {
        current = load();
        return current;
    }

    public static KoilMarketValueConfig defaults() {
        return new KoilMarketValueConfig(true, "ƒ", defaultReserveItems(), 60, 0.65D, 0.8D, 35, true, true, 0.72D, 0.35D, 0.55D, true, true);
    }

    private static KoilMarketValueConfig load() {
        Path path = configPath();
        KoilMarketValueConfig fallback = defaults();

        try {
            Files.createDirectories(path.getParent());

            if (!Files.isRegularFile(path)) {
                write(path, fallback);
                return fallback;
            }

            JsonElement parsed = JsonParser.parseString(Files.readString(path));

            if (!parsed.isJsonObject()) {
                write(path, fallback);
                return fallback;
            }

            JsonObject object = parsed.getAsJsonObject();
            List<String> reserves = new ArrayList<>();
            JsonArray reserveArray = object.has("reserveItems") && object.get("reserveItems").isJsonArray() ? object.getAsJsonArray("reserveItems") : new JsonArray();

            for (JsonElement element : reserveArray) {
                if (element.isJsonPrimitive()) {
                    String value = element.getAsString();

                    if (value != null && !value.isBlank()) {
                        reserves.add(value.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }

            return new KoilMarketValueConfig(
                    bool(object, "momensEnabled", fallback.momensEnabled),
                    string(object, "momensIcon", fallback.momensIcon),
                    reserves.isEmpty() ? fallback.reserveItems : reserves,
                    integer(object, "marketTickSeconds", fallback.marketTickSeconds),
                    decimal(object, "priceSmoothing", fallback.priceSmoothing),
                    decimal(object, "antiSpamStrength", fallback.antiSpamStrength),
                    integer(object, "minimumConfidenceToShowPrice", fallback.minimumConfidenceToShowPrice),
                    bool(object, "allowClientEstimatedMarkets", fallback.allowClientEstimatedMarkets),
                    bool(object, "valueGraphModeEnabled", fallback.valueGraphModeEnabled),
                    decimal(object, "uniquePlayerWeight", fallback.uniquePlayerWeight),
                    decimal(object, "volatilityPenalty", fallback.volatilityPenalty),
                    decimal(object, "lowSamplePenalty", fallback.lowSamplePenalty),
                    bool(object, "dynamicReserveBase", fallback.dynamicReserveBase),
                    bool(object, "physicalMomensCurrency", fallback.physicalMomensCurrency)
            );
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void write(Path path, KoilMarketValueConfig config) {
        try {
            JsonObject object = new JsonObject();
            object.addProperty("momensEnabled", config.momensEnabled);
            object.addProperty("momensIcon", config.momensIcon);
            JsonArray reserves = new JsonArray();

            for (String item : config.reserveItems) {
                reserves.add(item);
            }

            object.add("reserveItems", reserves);
            object.addProperty("marketTickSeconds", config.marketTickSeconds);
            object.addProperty("priceSmoothing", config.priceSmoothing);
            object.addProperty("antiSpamStrength", config.antiSpamStrength);
            object.addProperty("minimumConfidenceToShowPrice", config.minimumConfidenceToShowPrice);
            object.addProperty("allowClientEstimatedMarkets", config.allowClientEstimatedMarkets);
            object.addProperty("valueGraphModeEnabled", config.valueGraphModeEnabled);
            object.addProperty("uniquePlayerWeight", config.uniquePlayerWeight);
            object.addProperty("volatilityPenalty", config.volatilityPenalty);
            object.addProperty("lowSamplePenalty", config.lowSamplePenalty);
            object.addProperty("dynamicReserveBase", config.dynamicReserveBase);
            object.addProperty("physicalMomensCurrency", config.physicalMomensCurrency);
            Files.writeString(path, GSON.toJson(object));
        } catch (Exception ignored) {
        }
    }

    private static Path configPath() {
        return Path.of("./koil/sys/config/momens_market.json");
    }

    private static List<String> defaultReserveItems() {
        List<String> values = new ArrayList<>();
        values.add("minecraft:diamond");
        values.add("minecraft:emerald");
        values.add("minecraft:gold_ingot");
        values.add("minecraft:iron_ingot");
        values.add("minecraft:coal");
        values.add("minecraft:redstone");
        values.add("minecraft:lapis_lazuli");
        return values;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            return object.has(key) ? object.get(key).getAsBoolean() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double decimal(JsonObject object, String key, double fallback) {
        try {
            return object.has(key) ? object.get(key).getAsDouble() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String string(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public boolean momensEnabled() {
        return this.momensEnabled;
    }

    public String momensIcon() {
        return this.momensIcon;
    }

    public List<String> reserveItems() {
        return new ArrayList<>(this.reserveItems);
    }

    public int marketTickSeconds() {
        return this.marketTickSeconds;
    }

    public double priceSmoothing() {
        return this.priceSmoothing;
    }

    public double antiSpamStrength() {
        return this.antiSpamStrength;
    }

    public int minimumConfidenceToShowPrice() {
        return this.minimumConfidenceToShowPrice;
    }

    public boolean allowClientEstimatedMarkets() {
        return this.allowClientEstimatedMarkets;
    }

    public boolean valueGraphModeEnabled() {
        return this.valueGraphModeEnabled;
    }

    public double uniquePlayerWeight() {
        return this.uniquePlayerWeight;
    }

    public double volatilityPenalty() {
        return this.volatilityPenalty;
    }

    public double lowSamplePenalty() {
        return this.lowSamplePenalty;
    }

    public boolean dynamicReserveBase() {
        return this.dynamicReserveBase;
    }

    public boolean physicalMomensCurrency() {
        return this.physicalMomensCurrency;
    }

}
