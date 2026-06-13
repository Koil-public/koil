package com.spirit.koil.api.stats.global;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

import java.util.*;

public final class GlobalActivityApi {
    public static final Identifier CHANNEL = new Identifier("koil", "global_activity_snapshot");
    public static final Identifier MARKET_HUD_CHANNEL = new Identifier("koil", "market_hud_snapshot");
    public static final Identifier REQUEST_CHANNEL = new Identifier("koil", "global_activity_request");

    private GlobalActivityApi() {
    }

    public static void registerServer() {
        KoilGlobalActivityServer.register();
    }

    public static void registerClient() {
        GlobalActivityClient.registerClient();
    }

    public static void recordActivity(MinecraftServer server, UUID uuid, String playerName, String metric, int amount) {
        if (server == null || uuid == null || metric == null || metric.isBlank() || amount == 0) {
            return;
        }

        KoilGlobalActivityServer.recordExternalActivity(server, uuid, playerName, metric, amount);
    }


    public static void recordLedgerActivity(MinecraftServer server, UUID uuid, String playerName, String category, String metric, int amount) {
        if (server == null || uuid == null || metric == null || metric.isBlank() || amount == 0) {
            return;
        }

        KoilGlobalActivityServer.recordLedgerActivity(server, uuid, playerName, category, metric, amount);
    }

    public static void recordLedgerActivity(ServerPlayerEntity player, String category, String metric, int amount) {
        if (player == null || player.getServer() == null) {
            return;
        }

        recordLedgerActivity(player.getServer(), player.getUuid(), player.getGameProfile().getName(), category, metric, amount);
    }



    public static void recordTransferActivity(MinecraftServer server, UUID uuid, String playerName, String metric, int amount) {
        if (server == null || uuid == null || metric == null || metric.isBlank() || amount == 0) {
            return;
        }

        KoilGlobalActivityServer.recordTransferActivity(server, uuid, playerName, metric, amount);
    }

    public static void recordTransferActivity(ServerPlayerEntity player, String metric, int amount) {
        if (player == null || player.getServer() == null) {
            return;
        }

        recordTransferActivity(player.getServer(), player.getUuid(), player.getGameProfile().getName(), metric, amount);
    }

    public static void recordPacketActivity(MinecraftServer server, UUID uuid, String playerName, String metric, int amount) {
        if (server == null || uuid == null || metric == null || metric.isBlank() || amount == 0) {
            return;
        }

        KoilGlobalActivityServer.recordPacketActivity(server, uuid, playerName, metric, amount);
    }

    public static void recordPacketActivity(ServerPlayerEntity player, String metric, int amount) {
        if (player == null || player.getServer() == null) {
            return;
        }

        recordPacketActivity(player.getServer(), player.getUuid(), player.getGameProfile().getName(), metric, amount);
    }

    public static void recordProcessingActivity(MinecraftServer server, String processorName, String metric, int amount) {
        if (server == null || metric == null || metric.isBlank() || amount == 0) {
            return;
        }

        KoilGlobalActivityServer.recordProcessingActivity(server, processorName, metric, amount);
    }

    public static void recordProcessingItem(MinecraftServer server, String processorName, String action, ItemStack stack, int amount) {
        if (server == null || stack == null || stack.isEmpty() || action == null || action.isBlank() || amount <= 0) {
            return;
        }

        KoilGlobalActivityServer.recordProcessingItem(server, processorName, action, stack, amount);
    }

    public static void recordProcessingPair(MinecraftServer server, String processorName, String action, ItemStack first, ItemStack second, int amount) {
        if (server == null || first == null || first.isEmpty() || second == null || second.isEmpty() || action == null || action.isBlank() || amount <= 0) {
            return;
        }

        KoilGlobalActivityServer.recordProcessingPair(server, processorName, action, first, second, amount);
    }

    public static void recordStationActivity(MinecraftServer server, UUID uuid, String playerName, String stationName, String action, ItemStack stack, int amount) {
        if (server == null || uuid == null || stack == null || stack.isEmpty() || action == null || action.isBlank() || amount <= 0) {
            return;
        }

        KoilGlobalActivityServer.recordStationItem(server, uuid, playerName, stationName, action, stack, amount);
    }

    public static void recordStationActivity(ServerPlayerEntity player, String stationName, String action, ItemStack stack, int amount) {
        if (player == null || player.getServer() == null) {
            return;
        }

        recordStationActivity(player.getServer(), player.getUuid(), player.getGameProfile().getName(), stationName, action, stack, amount);
    }

    public static void recordStationMetric(ServerPlayerEntity player, String stationName, String action, Identifier id, int amount) {
        if (player == null || player.getServer() == null || id == null || action == null || action.isBlank() || amount <= 0) {
            return;
        }

        KoilGlobalActivityServer.recordStationMetric(player.getServer(), player.getUuid(), player.getGameProfile().getName(), stationName, action, id, amount);
    }

    public static void setActivityValue(MinecraftServer server, UUID uuid, String playerName, String metric, int value) {
        if (server == null || uuid == null || metric == null || metric.isBlank()) {
            return;
        }

        KoilGlobalActivityServer.setExternalActivity(server, uuid, playerName, metric, Math.max(0, value));
    }

    public static void recordActivity(ServerPlayerEntity player, String metric, int amount) {
        if (player == null || player.getServer() == null) {
            return;
        }

        recordActivity(player.getServer(), player.getUuid(), player.getGameProfile().getName(), metric, amount);
    }

    public static void setActivityValue(ServerPlayerEntity player, String metric, int value) {
        if (player == null || player.getServer() == null) {
            return;
        }

        setActivityValue(player.getServer(), player.getUuid(), player.getGameProfile().getName(), metric, value);
    }

    public static boolean createMarket(MinecraftServer server, String id, String metric, String name, int basePrice) {
        return KoilGlobalActivityServer.createMarketFromApi(server, id, metric, name, basePrice);
    }

    public static boolean removeMarket(MinecraftServer server, String id) {
        return KoilGlobalActivityServer.removeMarketFromApi(server, id);
    }

    public static void forceSync(MinecraftServer server) {
        KoilGlobalActivityServer.forceSyncFromApi(server);
    }



    public static KoilMarketValueConfig marketValueConfig() {
        return KoilMarketValueConfig.current();
    }

    public static KoilMarketValueConfig reloadMarketValueConfig() {
        return KoilMarketValueConfig.reload();
    }

    public static KoilMarketPriceSnapshot marketPriceSnapshot(KoilGlobalMarketViewRow row, List<KoilGlobalMarketViewRow> marketRows) {
        return KoilMarketValueEngine.priceSnapshot(row, marketRows);
    }

    public static List<KoilMarketPriceSnapshot> marketPriceSnapshots(List<KoilGlobalMarketViewRow> marketRows) {
        return KoilMarketValueEngine.priceSnapshots(marketRows);
    }

    public static KoilGlobalMarketViewRow findMarket(List<KoilGlobalMarketViewRow> marketRows, String query) {
        return KoilMarketValueEngine.findMarket(marketRows, query);
    }

    public static String compareMarkets(KoilGlobalMarketViewRow first, KoilGlobalMarketViewRow second, List<KoilGlobalMarketViewRow> marketRows) {
        return KoilMarketValueEngine.compare(first, second, marketRows);
    }

    public static int[] marketValueSeries(KoilGlobalMarketViewRow row, List<KoilGlobalMarketViewRow> marketRows) {
        return KoilMarketValueEngine.valueSeries(row, marketRows);
    }

    public static List<String> reserveBasket() {
        return KoilMarketValueEngine.reserveBasketLabels();
    }


    public static String selectedMomensBase(List<KoilGlobalMarketViewRow> marketRows) {
        return KoilMarketValueEngine.selectedReserveBaseLabel(marketRows, KoilMarketValueConfig.current());
    }

    public static String oneMomenReserveLine(List<KoilGlobalMarketViewRow> marketRows) {
        return KoilMarketValueEngine.oneMomenReserveLine(marketRows);
    }

    public static boolean physicalMomensCurrencyAvailable() {
        return KoilPhysicalMomensCurrency.available();
    }

    public static List<String> physicalMomensCurrencyNotes() {
        return KoilPhysicalMomensCurrency.detectedNotes();
    }

    public static int physicalMomensValue(Identifier id) {
        return KoilPhysicalMomensCurrency.momensValue(id);
    }

    public static int physicalMomensValue(ItemStack stack) {
        return KoilPhysicalMomensCurrency.stackMomensValue(stack);
    }

    public static boolean isPhysicalMomens(ItemStack stack) {
        return KoilPhysicalMomensCurrency.isMomens(stack);
    }

    public static List<KoilPhysicalMomensCurrency.SourceEntry> physicalMomensSources(ItemStack stack) {
        return KoilPhysicalMomensCurrency.sources(stack);
    }

    public static int givePhysicalMomens(ServerPlayerEntity player, int amount, KoilPhysicalMomensCurrency.SourceInfo source) {
        return KoilPhysicalMomensCurrency.giveMomens(player, amount, source);
    }

    public static String ledgerCategory(String metric) {
        return KoilMarketLedger.categoryForMetric(metric);
    }

    public static boolean ledgerCategoryAffectsPrice(String category) {
        return KoilMarketLedger.shouldAffectPrice(category);
    }


    public static String momensIcon() {
        return KoilMarketValueConfig.current().momensIcon();
    }

    public static KoilMarketValueQuote quoteMarket(KoilGlobalMarketViewRow row, List<KoilGlobalMarketViewRow> marketRows) {
        return KoilMarketValueEngine.quote(row, marketRows);
    }

    public static double marketMomensValue(KoilGlobalMarketViewRow row, List<KoilGlobalMarketViewRow> marketRows) {
        return KoilMarketValueEngine.quote(row, marketRows).momensValue();
    }

    public static List<KoilGlobalActivityRow> clientRows() {
        return GlobalActivityClient.rows();
    }

    public static List<KoilGlobalActivityRow> clientMarkets() {
        return GlobalActivityClient.marketRows();
    }

    public static List<KoilGlobalActivityRow> clientActivities() {
        return GlobalActivityClient.activityRows();
    }

    public static boolean clientHasServerData() {
        return GlobalActivityClient.hasServerData();
    }

    public static boolean clientHasObservedData() {
        return GlobalActivityClient.hasObservedData();
    }

    public static String clientContextKey() {
        return GlobalActivityClient.contextKey();
    }
}
