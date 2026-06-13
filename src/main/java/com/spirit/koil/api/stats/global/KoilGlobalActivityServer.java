package com.spirit.koil.api.stats.global;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.EntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.stat.Stat;
import net.minecraft.stat.StatHandler;
import net.minecraft.stat.StatType;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class KoilGlobalActivityServer {
    private static final int MARKET_SAMPLE_INTERVAL_TICKS = 200;
    private static final int SYNC_INTERVAL_TICKS = 1200;
    private static final int JOIN_RESYNC_DELAY_TICKS = 40;
    private static final int COMMAND_MARKET_LOOKUP_LIMIT = 2048;
    private static final int MARKET_SYNC_ROW_LIMIT = 768;
    private static final int SYNC_MAGIC = 1263487308;
    private static final int SYNC_PROTOCOL = 2;
    private static int ticks;
    private static boolean dirtySinceLastSync;
    private static final Map<String, Integer> packetSamples = new LinkedHashMap<>();
    private static final Map<UUID, Integer> pendingJoinSyncs = new LinkedHashMap<>();
    private static final UUID SERVER_PROCESS_UUID = new UUID(0L, 928347201L);
    private static boolean registered;
    private static final Map<UUID, Map<String, Integer>> lastRawStats = new LinkedHashMap<>();
    private static final Map<UUID, Map<String, Integer>> ignoredStatOffsets = new LinkedHashMap<>();
    private static final Map<UUID, CatchBaseline> activeCatchBaselines = new LinkedHashMap<>();
    private static final Map<String, HudQuoteBaseline> lastHudQuoteBaselines = new LinkedHashMap<>();

    private KoilGlobalActivityServer() {
    }

    private static void resetRuntimeState() {
        ticks = 0;
        dirtySinceLastSync = false;

        synchronized (packetSamples) {
            packetSamples.clear();
        }

        pendingJoinSyncs.clear();
        lastRawStats.clear();
        ignoredStatOffsets.clear();
        activeCatchBaselines.clear();
        lastHudQuoteBaselines.clear();
    }

    private static void tickPendingJoinSyncs(MinecraftServer server, KoilGlobalActivityState state) {
        Iterator<Map.Entry<UUID, Integer>> iterator = pendingJoinSyncs.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();
            int ticksLeft = entry.getValue() - 1;

            if (ticksLeft > 0) {
                entry.setValue(ticksLeft);
                continue;
            }

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());

            if (player != null) {
                sync(player, state);
            }

            iterator.remove();
        }
    }

    public static void register() {
        if (registered) {
            return;
        }

        registered = true;
        registerCommands();
        registerServerActivitySamplers();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> resetRuntimeState());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            try {
                KoilGlobalActivityState state = state(server);
                boolean changed = flushPacketSamples(state);

                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    changed |= updatePlayer(state, player);
                }

                if (changed) {
                    state.appendSnapshot();
                    state.markDirty();
                }
            } catch (Exception ignored) {
            }

            resetRuntimeState();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            KoilGlobalActivityState state = state(server);

            if (updatePlayer(state, handler.player)) {
                state.appendSnapshot();
                state.markDirty();
                dirtySinceLastSync = true;
            }

            pendingJoinSyncs.put(handler.player.getUuid(), JOIN_RESYNC_DELAY_TICKS);
            sync(handler.player, state);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            pendingJoinSyncs.remove(handler.player.getUuid());
            activeCatchBaselines.remove(handler.player.getUuid());
            removeHudBaselinesForPlayer(handler.player.getUuid());
            KoilGlobalActivityState state = state(server);
            boolean changed = flushPacketSamples(state) | updatePlayer(state, handler.player);

            if (changed) {
                state.appendSnapshot();
                state.markDirty();
                dirtySinceLastSync = true;
                syncAll(server, state);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ticks++;
            KoilGlobalActivityState state = null;

            if (!pendingJoinSyncs.isEmpty()) {
                state = state(server);
                tickPendingJoinSyncs(server, state);
            }

            if (ticks % MARKET_SAMPLE_INTERVAL_TICKS == 0) {
                if (state == null) {
                    state = state(server);
                }

                boolean changed = flushPacketSamples(state);

                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    changed |= updatePlayer(state, player);
                }

                if (changed || dirtySinceLastSync) {
                    state.appendSnapshot();
                    state.markDirty();
                    dirtySinceLastSync = true;
                }
            }

            if (ticks % SYNC_INTERVAL_TICKS != 0 || !dirtySinceLastSync) {
                return;
            }

            if (state == null) {
                state = state(server);
            }

            syncAll(server, state);
            dirtySinceLastSync = false;
        });
    }

    public static void recordExternalActivity(MinecraftServer server, UUID uuid, String playerName, String metric, int amount) {
        KoilGlobalActivityState state = state(server);

        if (state.addExternal(uuid, playerName, metric, amount)) {
            state.appendSnapshot();
            state.markDirty();
            syncAll(server, state);
        }
    }


    public static void recordLedgerActivity(MinecraftServer server, UUID uuid, String playerName, String category, String metric, int amount) {
        if (server == null || uuid == null || metric == null || metric.isBlank() || amount == 0) {
            return;
        }

        String ledgerCategory = category == null || category.isBlank() ? KoilMarketLedger.categoryForMetric(metric) : category.toLowerCase(Locale.ROOT).trim();

        if (KoilMarketLedger.ADMIN.equals(ledgerCategory) || KoilMarketLedger.CREATIVE.equals(ledgerCategory)) {
            return;
        }

        String cleanMetric = KoilMarketLedger.TRANSFER.equals(ledgerCategory) || KoilMarketLedger.LOSS.equals(ledgerCategory) ? ledgerCategory + "/" + metric : metric;
        KoilGlobalActivityState state = state(server);

        if (state.addExternal(uuid, playerName, cleanMetric, amount)) {
            state.appendSnapshot();
            state.markDirty();
            syncAll(server, state);
        }
    }



    public static void recordTransferActivity(MinecraftServer server, UUID uuid, String playerName, String metric, int amount) {
        recordLedgerActivity(server, uuid, playerName, KoilMarketLedger.TRANSFER, metric, amount);
    }

    public static void recordLossActivity(MinecraftServer server, UUID uuid, String playerName, String metric, int amount) {
        recordLedgerActivity(server, uuid, playerName, KoilMarketLedger.LOSS, metric, amount);
    }

    public static void recordPacketActivity(MinecraftServer server, UUID uuid, String playerName, String metric, int amount) {
        if (server == null || uuid == null || metric == null || metric.isBlank() || amount == 0) {
            return;
        }

        String cleanMetric = metric.startsWith("packet/") ? metric : "packet/" + metric;
        bufferActivity(uuid, playerName, cleanMetric, amount);
    }

    public static void recordProcessingActivity(MinecraftServer server, String processorName, String metric, int amount) {
        if (server == null || metric == null || metric.isBlank() || amount == 0) {
            return;
        }

        String cleanMetric = metric.startsWith("processing/") ? metric : "processing/" + metric;
        String safeProcessorName = processorName == null || processorName.isBlank() ? "server processing" : "server processing: " + processorName;
        bufferActivity(SERVER_PROCESS_UUID, safeProcessorName, cleanMetric, amount);
        dirtySinceLastSync = true;
    }

    public static void recordProcessingItem(MinecraftServer server, String processorName, String action, ItemStack stack, int amount) {
        if (stack == null || stack.isEmpty() || amount <= 0 || action == null || action.isBlank()) {
            return;
        }

        Identifier id = Registries.ITEM.getId(stack.getItem());
        recordProcessingActivity(server, processorName, action + "/" + id.getNamespace() + "/" + id.getPath(), amount);
    }

    public static void recordProcessingPair(MinecraftServer server, String processorName, String action, ItemStack first, ItemStack second, int amount) {
        if (first == null || first.isEmpty() || second == null || second.isEmpty() || amount <= 0 || action == null || action.isBlank()) {
            return;
        }

        Identifier firstId = Registries.ITEM.getId(first.getItem());
        Identifier secondId = Registries.ITEM.getId(second.getItem());
        recordProcessingActivity(server, processorName, action + "/" + firstId.getNamespace() + "/" + firstId.getPath() + "/" + secondId.getNamespace() + "/" + secondId.getPath(), amount);
    }

    public static void recordStationItem(MinecraftServer server, UUID uuid, String playerName, String stationName, String action, ItemStack stack, int amount) {
        if (server == null || uuid == null || stack == null || stack.isEmpty() || action == null || action.isBlank() || amount <= 0) {
            return;
        }

        Identifier id = Registries.ITEM.getId(stack.getItem());
        String safeStation = stationKey(stationName);
        String cleanAction = cleanMetricPart(action);
        String metric = "station/" + cleanAction + "/" + safeStation + "/" + id.getNamespace() + "/" + id.getPath();
        String category = KoilMarketLedger.categoryForMetric(metric);

        if (!KoilMarketLedger.ADMIN.equals(category) && !KoilMarketLedger.CREATIVE.equals(category)) {
            bufferActivity(uuid, playerName, metric, amount);
            dirtySinceLastSync = true;
        }
    }

    public static void recordStationMetric(MinecraftServer server, UUID uuid, String playerName, String stationName, String action, Identifier id, int amount) {
        if (server == null || uuid == null || id == null || action == null || action.isBlank() || amount <= 0) {
            return;
        }

        String safeStation = stationKey(stationName);
        String cleanAction = cleanMetricPart(action);
        String metric = "station/" + cleanAction + "/" + safeStation + "/" + id.getNamespace() + "/" + id.getPath();
        String category = KoilMarketLedger.categoryForMetric(metric);

        if (!KoilMarketLedger.ADMIN.equals(category) && !KoilMarketLedger.CREATIVE.equals(category)) {
            bufferActivity(uuid, playerName, metric, amount);
            dirtySinceLastSync = true;
        }
    }

    private static String stationKey(String stationName) {
        if (stationName == null || stationName.isBlank()) {
            return "unknown_station";
        }

        return stationName.toLowerCase(Locale.ROOT).replace(':', '_').replace('/', '_').replace(' ', '_').replace('@', '_').replace(',', '_');
    }

    private static String cleanMetricPart(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }

        StringBuilder builder = new StringBuilder();
        String clean = value.toLowerCase(Locale.ROOT);

        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            builder.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }

        return builder.toString();
    }

    private static void bufferActivity(UUID uuid, String playerName, String metric, int amount) {
        if (uuid == null || metric == null || metric.isBlank() || amount == 0) {
            return;
        }

        String safeName = playerName == null || playerName.isBlank() ? uuid.toString() : playerName;
        String key = uuid + "||KOIL||" + safeName + "||KOIL||" + metric;

        synchronized (packetSamples) {
            packetSamples.put(key, packetSamples.getOrDefault(key, 0) + amount);
        }
    }

    private static boolean flushPacketSamples(KoilGlobalActivityState state) {
        Map<String, Integer> copy;

        synchronized (packetSamples) {
            if (packetSamples.isEmpty()) {
                return false;
            }

            copy = new LinkedHashMap<>(packetSamples);
            packetSamples.clear();
        }

        boolean changed = false;

        for (Map.Entry<String, Integer> entry : copy.entrySet()) {
            String[] parts = entry.getKey().split("\\|\\|KOIL\\|\\|", 3);

            if (parts.length != 3) {
                continue;
            }

            try {
                changed |= state.addExternal(UUID.fromString(parts[0]), parts[1], parts[2], entry.getValue());
            } catch (Exception ignored) {
            }
        }

        return changed;
    }

    private static void registerServerActivitySamplers() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world == null || world.isClient || player == null || player.getServer() == null || shouldIgnorePlayerMarketAction((ServerPlayerEntity) player)) {
                return;
            }

            Identifier id = Registries.BLOCK.getId(state.getBlock());
            bufferActivity(player.getUuid(), player.getGameProfile().getName(), "block_broken/" + id.getNamespace() + "/" + id.getPath(), 1);
            dirtySinceLastSync = true;
            String resourceMetric = minedResourceMetric(id);

            if (!resourceMetric.isBlank()) {
                bufferActivity(player.getUuid(), player.getGameProfile().getName(), resourceMetric, minedResourceAmount(id));
                dirtySinceLastSync = true;
            }
        });
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world == null || world.isClient || player == null || player.getServer() == null || shouldIgnorePlayerMarketAction((ServerPlayerEntity) player)) {
                return ActionResult.PASS;
            }

            Item item = player.getStackInHand(hand).getItem();

            if (item instanceof BlockItem blockItem) {
                Identifier id = Registries.BLOCK.getId(blockItem.getBlock());
                recordPacketActivity(player.getServer(), player.getUuid(), player.getGameProfile().getName(), "block_place_intent/" + id.getNamespace() + "/" + id.getPath(), 1);
            }

            try {
                Block block = world.getBlockState(hitResult.getBlockPos()).getBlock();
                Identifier blockId = Registries.BLOCK.getId(block);
                String role = stationRole(blockId);

                recordStationMetric(player.getServer(), player.getUuid(), player.getName().toString(), blockId.getNamespace() + ":" + blockId.getPath(), role + "_interact", blockId, 1);
            } catch (Exception ignored) {
            }

            return ActionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world == null || world.isClient || player == null || player.getServer() == null || shouldIgnorePlayerMarketAction((ServerPlayerEntity) player)) {
                return TypedActionResult.pass(player.getStackInHand(hand));
            }

            Item item = player.getStackInHand(hand).getItem();

            if (item != Items.AIR && !(item instanceof BlockItem)) {
                Identifier id = Registries.ITEM.getId(item);

                if (item.getFoodComponent() != null) {
                    recordPacketActivity(player.getServer(), player.getUuid(), player.getGameProfile().getName(), "food_use_intent/" + id.getNamespace() + "/" + id.getPath(), 1);
                } else {
                    recordPacketActivity(player.getServer(), player.getUuid(), player.getGameProfile().getName(), "item_use_intent/" + id.getNamespace() + "/" + id.getPath(), 1);
                }
            }

            return TypedActionResult.pass(player.getStackInHand(hand));
        });
    }

    private static boolean isProcessingStation(Identifier id) {
        return !"block".equals(stationRole(id));
    }

    public static String stationRole(Identifier id) {
        if (id == null) {
            return "block";
        }

        String value = (id.getNamespace() + "/" + id.getPath()).toLowerCase(Locale.ROOT);

        if (value.contains("barrel") || value.contains("chest") || value.contains("shulker") || value.contains("hopper") || value.contains("dispenser") || value.contains("dropper") || value.contains("container") || value.contains("drawer") || value.contains("crate") || value.contains("tank")) {
            return "storage";
        }

        if (value.contains("brewing") || value.contains("brew")) {
            return "brewing";
        }

        if (value.contains("furnace") || value.contains("smoker") || value.contains("blast") || value.contains("smelt") || value.contains("kiln")) {
            return "smelting";
        }

        if (value.contains("smithing") || value.contains("anvil") || value.contains("grindstone") || value.contains("stonecutter") || value.contains("loom") || value.contains("cartography") || value.contains("crafting_table") || value.contains("workbench") || value.contains("table")) {
            return "crafting_station";
        }

        if (value.contains("enchant") || value.contains("beacon") || value.contains("spawner") || value.contains("lectern") || value.contains("jukebox") || value.contains("note_block")) {
            return "utility_station";
        }

        if (value.contains("composter")) {
            return "composting";
        }

        if (value.contains("crusher") || value.contains("grinder") || value.contains("macerator") || value.contains("pulverizer") || value.contains("press") || value.contains("sawmill") || value.contains("centrifuge") || value.contains("alloy") || value.contains("refinery") || value.contains("processor") || value.contains("factory") || value.contains("machine") || value.contains("assembler") || value.contains("compressor") || value.contains("extractor") || value.contains("separator") || value.contains("mixer") || value.contains("mill") || value.contains("generator")) {
            return "modded_machine";
        }

        return "block";
    }

    private static String minedResourceMetric(Identifier blockId) {
        if (blockId == null) {
            return "";
        }

        String namespace = blockId.getNamespace();
        String path = blockId.getPath().toLowerCase(Locale.ROOT);

        if (path.startsWith("deepslate_")) {
            path = path.substring("deepslate_".length());
        }

        String resource = switch (path) {
            case "coal_ore" -> "coal";
            case "iron_ore" -> "raw_iron";
            case "copper_ore" -> "raw_copper";
            case "gold_ore", "nether_gold_ore" -> "raw_gold";
            case "diamond_ore" -> "diamond";
            case "emerald_ore" -> "emerald";
            case "lapis_ore" -> "lapis_lazuli";
            case "redstone_ore" -> "redstone";
            case "nether_quartz_ore" -> "quartz";
            case "ancient_debris" -> "netherite_scrap";
            default -> "";
        };

        if (resource.isBlank()) {
            return "";
        }

        return "resource/" + namespace + "/" + resource;
    }

    private static int minedResourceAmount(Identifier blockId) {
        if (blockId == null) {
            return 1;
        }

        String path = blockId.getPath().toLowerCase(Locale.ROOT);

        if (path.contains("redstone_ore")) {
            return 4;
        }

        if (path.contains("lapis_ore")) {
            return 4;
        }

        if (path.contains("nether_gold_ore")) {
            return 2;
        }

        return 1;
    }
    public static void setExternalActivity(MinecraftServer server, UUID uuid, String playerName, String metric, int value) {
        KoilGlobalActivityState state = state(server);

        if (state.setExternal(uuid, playerName, metric, value)) {
            state.appendSnapshot();
            state.markDirty();
            syncAll(server, state);
        }
    }

    public static boolean createMarketFromApi(MinecraftServer server, String id, String metric, String name, int basePrice) {
        if (server == null) {
            return false;
        }

        KoilGlobalActivityState state = state(server);
        boolean changed = state.defineMarket("custom/" + id, metric, name, basePrice);
        state.appendSnapshot();
        state.markDirty();
        syncAll(server, state);
        return changed;
    }

    public static boolean removeMarketFromApi(MinecraftServer server, String id) {
        if (server == null) {
            return false;
        }

        KoilGlobalActivityState state = state(server);
        boolean changed = state.removeMarket("custom/" + id) || state.removeMarket(id);
        state.markDirty();
        syncAll(server, state);
        return changed;
    }

    public static void forceSyncFromApi(MinecraftServer server) {
        if (server == null) {
            return;
        }

        KoilGlobalActivityState state = state(server);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            updatePlayer(state, player);
        }

        state.appendSnapshot();
        state.markDirty();
        syncAll(server, state);
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(CommandManager.literal("market")
                .then(CommandManager.literal("create")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.argument("id", StringArgumentType.string())
                                .then(CommandManager.argument("metric", StringArgumentType.string())
                                        .executes(context -> createMarket(context.getSource(), StringArgumentType.getString(context, "id"), StringArgumentType.getString(context, "metric"), 100))
                                        .then(CommandManager.argument("base", IntegerArgumentType.integer(1, 100000))
                                                .executes(context -> createMarket(context.getSource(), StringArgumentType.getString(context, "id"), StringArgumentType.getString(context, "metric"), IntegerArgumentType.getInteger(context, "base")))))))
                .then(CommandManager.literal("remove")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.argument("id", StringArgumentType.string())
                                .executes(context -> removeMarket(context.getSource(), StringArgumentType.getString(context, "id")))))
                .then(CommandManager.literal("list")
                        .executes(context -> listMarkets(context.getSource())))
                .then(CommandManager.literal("sync")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> forceSync(context.getSource())))
                .then(CommandManager.literal("quote")
                        .then(CommandManager.argument("query", StringArgumentType.greedyString())
                                .suggests(KoilGlobalActivityServer::suggestMarketQueries)
                                .executes(context -> quoteMarket(context.getSource(), StringArgumentType.getString(context, "query")))))
                .then(CommandManager.literal("catch")
                        .then(CommandManager.literal("off")
                                .executes(context -> hideMarketCatch(context.getSource())))
                        .then(CommandManager.literal("update")
                                .executes(context -> updateMarketCatch(context.getSource())))
                        .then(CommandManager.argument("query", StringArgumentType.greedyString())
                                .suggests(KoilGlobalActivityServer::suggestMarketQueries)
                                .executes(context -> catchMarket(context.getSource(), StringArgumentType.getString(context, "query")))))
                .then(CommandManager.literal("compare")
                        .then(CommandManager.argument("expression", StringArgumentType.greedyString())
                                .suggests(KoilGlobalActivityServer::suggestMarketQueries)
                                .executes(context -> compareMarketsExpression(context.getSource(), StringArgumentType.getString(context, "expression")))))
                .then(CommandManager.literal("compare-id")
                        .then(CommandManager.argument("first", StringArgumentType.string())
                                .suggests(KoilGlobalActivityServer::suggestItemIds)
                                .then(CommandManager.argument("second", StringArgumentType.string())
                                        .suggests(KoilGlobalActivityServer::suggestItemIds)
                                        .executes(context -> compareMarketsById(context.getSource(), StringArgumentType.getString(context, "first"), StringArgumentType.getString(context, "second"))))))
                .then(CommandManager.literal("base")
                        .executes(context -> showBaseResource(context.getSource())))
                .then(CommandManager.literal("currency")
                        .executes(context -> showCurrency(context.getSource())))
                .then(CommandManager.literal("source")
                        .executes(context -> showSourceHelp(context.getSource()))
                        .then(CommandManager.literal("from")
                                .then(CommandManager.argument("expression", StringArgumentType.greedyString())
                                        .suggests(KoilGlobalActivityServer::suggestSourceFromExpression)
                                        .executes(context -> sourceMomensFromExpression(context.getSource(), StringArgumentType.getString(context, "expression")))))
                        .then(CommandManager.literal("to")
                                .then(CommandManager.argument("query", StringArgumentType.greedyString())
                                        .suggests(KoilGlobalActivityServer::suggestMarketQueries)
                                        .executes(context -> sourceMomensToQuery(context.getSource(), StringArgumentType.getString(context, "query")))))
                        .then(CommandManager.literal("all")
                                .executes(context -> redeemInventoryMomens(context.getSource()))))
                .then(CommandManager.literal("compact")
                        .executes(context -> compactMomens(context.getSource())))
                .then(CommandManager.literal("reserves")
                        .executes(context -> listReserveBasket(context.getSource())))
                .then(CommandManager.literal("config")
                        .executes(context -> showMarketConfig(context.getSource())))
                .then(CommandManager.literal("reload-config")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(context -> reloadMarketConfig(context.getSource())))));
    }

    private static CompletableFuture<Suggestions> suggestMarketQueries(com.mojang.brigadier.context.CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        Set<String> suggestions = new LinkedHashSet<>();

        for (KoilGlobalMarketViewRow row : marketViewRows(context.getSource())) {
            addMarketSuggestions(suggestions, row);
        }

        for (Identifier id : Registries.ITEM.getIds()) {
            suggestions.add(id.toString());
            suggestions.add(id.getPath().replace('_', ' '));
        }

        for (Identifier id : Registries.BLOCK.getIds()) {
            suggestions.add(id.toString());
            suggestions.add(id.getPath().replace('_', ' '));
            suggestions.add(titleWords(id.getPath()) + " Block Market");
        }

        return CommandSource.suggestMatching(suggestions, builder);
    }

    private static void addMarketSuggestions(Set<String> suggestions, KoilGlobalMarketViewRow row) {
        if (row == null) {
            return;
        }

        suggestions.add(row.title());
        suggestions.add(row.subject());
        suggestions.add(row.id());
        suggestions.add(row.title().replace(" Market", "").trim());

        if ("block".equals(row.domain())) {
            suggestions.add(titleWords(row.subject()) + " Block Market");
        }

        if ("item".equals(row.domain())) {
            suggestions.add(titleWords(row.subject()) + " Item Market");
        }

        if ("resource".equals(row.domain())) {
            suggestions.add(titleWords(row.subject()) + " Resource Market");
        }

        for (String metric : row.rawMetrics()) {
            suggestions.add(metric);
            Identifier itemId = parseItemId(metric);

            if (itemId != null) {
                suggestions.add(itemId.toString());
                suggestions.add(itemId.getPath().replace('_', ' '));
            }

            Identifier blockId = parseBlockId(metric);

            if (blockId != null) {
                suggestions.add(blockId.toString());
                suggestions.add(blockId.getPath().replace('_', ' '));
                suggestions.add(titleWords(blockId.getPath()) + " Block Market");
            }
        }
    }

    private static CompletableFuture<Suggestions> suggestItemIds(com.mojang.brigadier.context.CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        List<String> suggestions = new ArrayList<>();

        for (Identifier id : Registries.ITEM.getIds()) {
            suggestions.add(id.toString());
        }

        return CommandSource.suggestMatching(suggestions, builder);
    }

    private static CompletableFuture<Suggestions> suggestSourceFromExpression(com.mojang.brigadier.context.CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        List<String> suggestions = new ArrayList<>();

        for (Identifier id : Registries.ITEM.getIds()) {
            String value = id.toString();
            suggestions.add(value + " 1");
            suggestions.add(value + " 16");
            suggestions.add(value + " 64");
            suggestions.add(value + " bills 1 20_stack");
            suggestions.add(value + " bills 4 5_stack");
        }

        suggestions.add("minecraft:birch_planks 16");
        suggestions.add("minecraft:diamond 1");
        suggestions.add("minecraft:iron_ingot 8");
        suggestions.add("minecraft:birch_planks bills 1 20_stack");
        return CommandSource.suggestMatching(suggestions, builder);
    }

    private static int createMarket(ServerCommandSource source, String id, String metric, int base) {
        KoilGlobalActivityState state = state(source.getServer());
        boolean changed = state.defineMarket("custom/" + id, metric, id, base);
        state.appendSnapshot();
        state.markDirty();
        syncAll(source.getServer(), state);
        return showMarketCommandHud(source, "create", "Market Create", changed ? "Created custom market" : "Market already matched", List.of(
                "Market -> " + id,
                "Metric -> " + metric,
                "Base -> " + base
        ));
    }


    private static int removeMarket(ServerCommandSource source, String id) {
        KoilGlobalActivityState state = state(source.getServer());
        boolean removed = state.removeMarket("custom/" + id) || state.removeMarket(id);
        state.markDirty();
        syncAll(source.getServer(), state);
        showMarketCommandHud(source, "remove", "Market Remove", removed ? "Removed custom market" : "No matching market", List.of(
                "Market -> " + id,
                "Status -> " + (removed ? "removed" : "not found")
        ));
        return removed ? 1 : 0;
    }


    private static int listMarkets(ServerCommandSource source) {
        List<KoilGlobalMarketViewRow> rows = marketViewRows(source);
        List<String> notes = new ArrayList<>();

        if (rows.isEmpty()) {
            notes.add("No markets exist yet.");
            notes.add("Server activity will create automatic markets after snapshots.");
            showMarketCommandHud(source, "list", "Market List", "No markets available", notes);
            return 0;
        }

        int limit = Math.min(14, rows.size());

        for (int i = 0; i < limit; i++) {
            KoilGlobalMarketViewRow row = rows.get(i);
            notes.add(row.title() + " -> " + row.subject() + " | " + row.id());
        }

        if (rows.size() > limit) {
            notes.add("More -> " + (rows.size() - limit) + " additional market(s)");
        }

        showMarketCommandHud(source, "list", "Market List", rows.size() + " market entries", notes);
        return rows.size();
    }


    private static int forceSync(ServerCommandSource source) {
        forceSyncFromApi(source.getServer());
        return showMarketCommandHud(source, "sync", "Market Sync", "Global activity snapshot refreshed", List.of(
                "Status -> synced global activity and market data",
                "Snapshot -> server exact data pushed to connected clients"
        ));
    }


    private static int quoteMarket(ServerCommandSource source, String query) {
        List<KoilGlobalMarketViewRow> rows = marketViewRows(source);
        String resolvedQuery = resolveCommandQuery(source, query);
        KoilGlobalMarketViewRow row = resolveMarketRow(rows, resolvedQuery);

        if (row == null) {
            return showMarketCommandHud(source, "error", "Market Quote", "No market matched", List.of("Query -> " + resolvedQuery, "Try -> /market list or /market quote <item>"));
        }

        if (sendMarketHud(source, singleMarketSnapshot(source, "quote", false, resolvedQuery, "Market Quote", row.title(), rows, row))) {
            return 1;
        }

        KoilMarketValueQuote quote = KoilMarketValueEngine.quote(row, rows);
        source.sendFeedback(() -> Text.literal("Market Quote -> " + row.title() + " -> " + quote.compactMomens()), false);
        return 1;
    }

    private static int catchMarket(ServerCommandSource source, String query) {
        refreshMarketSnapshot(source);
        List<KoilGlobalMarketViewRow> rows = marketViewRows(source);
        String resolvedQuery = resolveCommandQuery(source, query);
        KoilGlobalMarketViewRow row = resolveMarketRow(rows, resolvedQuery);

        if (row == null) {
            source.sendFeedback(() -> Text.literal("Catch -> no market matched " + resolvedQuery).formatted(Formatting.RED), false);
            return 0;
        }

        KoilMarketHudSnapshot snapshot = singleMarketSnapshot(source, "catch", false, resolvedQuery, "Market Catch", row.title(), rows, row);

        if (sendMarketHud(source, snapshot)) {
            ServerPlayerEntity player = playerFromSource(source);

            if (player != null) {
                activeCatchBaselines.put(player.getUuid(), catchBaseline(resolvedQuery, row, rows));
            }

            source.sendFeedback(() -> Text.literal("Market catch popup opened for " + row.title()).formatted(Formatting.GRAY), false);
            return 1;
        }

        source.sendFeedback(() -> Text.literal("Catch -> this client cannot receive the Koil market popup channel.").formatted(Formatting.YELLOW), false);
        return quoteMarket(source, query);
    }

    private static int updateMarketCatch(ServerCommandSource source) {
        ServerPlayerEntity player = playerFromSource(source);

        if (player == null) {
            source.sendFeedback(() -> Text.literal("Catch update -> command must be run by a player."), false);
            return 0;
        }

        CatchBaseline previous = activeCatchBaselines.get(player.getUuid());

        if (previous == null) {
            source.sendFeedback(() -> Text.literal("Catch update -> no active catch. Use /market catch <market> first."), false);
            return 0;
        }

        refreshMarketSnapshot(source);
        List<KoilGlobalMarketViewRow> rows = marketViewRows(source);
        KoilGlobalMarketViewRow row = KoilMarketValueEngine.findMarketExactId(rows, previous.rowId());

        if (row == null) {
            row = resolveMarketRow(rows, previous.resolvedQuery());
        }

        if (row == null) {
            source.sendFeedback(() -> Text.literal("Catch update -> caught market no longer exists: " + previous.title()).formatted(Formatting.RED), false);
            return 0;
        }

        KoilMarketValueQuote quote = KoilMarketValueEngine.quote(row, rows);
        int[] series = marketHudSeries(row, rows);
        int lastPoint = lastPoint(series);
        KoilMarketHudSnapshot.Entry updatedEntry = marketHudEntryWithDeltas(row, rows, previous, quote, lastPoint);

        String sourceLine = (row.authoritative() ? "server exact" : "estimated") + " | confidence " + row.confidence() + "% | updated";
        KoilMarketHudSnapshot snapshot = new KoilMarketHudSnapshot("catch update", false, System.currentTimeMillis(), previous.resolvedQuery(), "Market Catch Update", row.title(), KoilMarketValueEngine.baseExchangeLine(rows), sourceLine, List.of(updatedEntry));

        if (sendMarketHud(source, snapshot)) {
            activeCatchBaselines.put(player.getUuid(), catchBaseline(previous.resolvedQuery(), row, rows));
            return 1;
        }

        KoilGlobalMarketViewRow finalRow = row;
        String valueDelta = signedDeltaText(quote.momensValue(), previous.momens(), "ƒ");
        source.sendFeedback(() -> Text.literal("Catch update -> " + finalRow.title() + " " + quote.compactMomens() + " " + valueDelta), false);

        activeCatchBaselines.put(player.getUuid(), catchBaseline(previous.resolvedQuery(), row, rows));
        return 1;
    }

    private static int hideMarketCatch(ServerCommandSource source) {
        ServerPlayerEntity player = playerFromSource(source);

        if (player != null) {
            activeCatchBaselines.remove(player.getUuid());
        }

        if (sendMarketHud(source, new KoilMarketHudSnapshot("hide", false, System.currentTimeMillis(), "", "Market", "", "", "", List.of()))) {
            source.sendFeedback(() -> Text.literal("Market catch popup hidden.").formatted(Formatting.GRAY), false);
            return 1;
        }

        source.sendFeedback(() -> Text.literal("Market popup channel is not available for this command source.").formatted(Formatting.YELLOW), false);
        return 0;
    }

    private static void refreshMarketSnapshot(ServerCommandSource source) {
        try {
            MinecraftServer server = source.getServer();
            KoilGlobalActivityState state = state(server);
            boolean changed = flushPacketSamples(state);

            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                changed |= updatePlayer(state, player);
            }

            if (changed) {
                state.appendSnapshot();
                state.markDirty();
                dirtySinceLastSync = true;
                syncAll(server, state);
            }
        } catch (Exception ignored) {
        }
    }

    private static CatchBaseline catchBaseline(String resolvedQuery, KoilGlobalMarketViewRow row, List<KoilGlobalMarketViewRow> rows) {
        KoilMarketValueQuote quote = KoilMarketValueEngine.quote(row, rows);
        return new CatchBaseline(resolvedQuery, row.id(), row.title(), quote.momensValue(), quote.demandScore(), quote.supplyScore(), quote.scarcityScore(), quote.trendScore(), row.confidence(), lastPoint(marketHudSeries(row, rows)), System.currentTimeMillis());
    }

    private static int lastPoint(int[] points) {
        return points == null || points.length == 0 ? 0 : points[points.length - 1];
    }

    private static String hudBaselineKey(ServerCommandSource source, KoilGlobalMarketViewRow row) {
        String playerKey = "server";
        ServerPlayerEntity player = playerFromSource(source);

        if (player != null) {
            playerKey = player.getUuid().toString();
        }

        return playerKey + "|" + (row == null ? "unknown" : row.id());
    }

    private static void removeHudBaselinesForPlayer(UUID uuid) {
        if (uuid == null || lastHudQuoteBaselines.isEmpty()) {
            return;
        }

        String prefix = uuid + "|";
        Iterator<String> iterator = lastHudQuoteBaselines.keySet().iterator();

        while (iterator.hasNext()) {
            if (iterator.next().startsWith(prefix)) {
                iterator.remove();
            }
        }
    }

    private static void trimHudBaselines() {
        Iterator<String> iterator = lastHudQuoteBaselines.keySet().iterator();

        while (lastHudQuoteBaselines.size() > 384 && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static String signedDeltaText(double current, double previous, String prefix) {
        double delta = current - previous;
        String arrow = delta > 0.000001D ? "↑" : delta < -0.000001D ? "↓" : "→";
        String sign = delta >= 0.0D ? "+" : "-";
        String safePrefix = prefix == null ? "" : prefix;
        return "(" + arrow + " " + sign + safePrefix + KoilMarketValueQuote.compactDecimal(Math.abs(delta)) + ")";
    }

    private static String signedDeltaText(double current, double previous) {
        return signedDeltaText(current, previous, "");
    }

    private static ServerPlayerEntity playerFromSource(ServerCommandSource source) {
        try {
            return source.getPlayer();
        } catch (Exception ignored) {
            return null;
        }
    }

    private record CatchBaseline(String resolvedQuery, String rowId, String title, double momens, double demand, double supply, double scarcity, double trend, int confidence, int lastPoint, long capturedAt) {
    }

    private record HudQuoteBaseline(double momens, double demand, double supply, double scarcity, double trend, int lastPoint) {
    }

    private static int compareMarketsExpression(ServerCommandSource source, String expression) {
        String[] parts = splitCompareExpression(expression);

        if (parts.length != 2) {
            source.sendFeedback(() -> Text.literal("Use: /market compare <first query> to <second query>"), false);
            source.sendFeedback(() -> Text.literal("Example: /market compare dirt block to diamond"), false);
            return 0;
        }

        List<KoilGlobalMarketViewRow> rows = marketViewRows(source);
        String first = resolveCommandQuery(source, parts[0]);
        String second = resolveCommandQuery(source, parts[1]);
        return compareResolvedMarkets(source, resolveMarketRow(rows, first), resolveMarketRow(rows, second), first, second);
    }

    private static int compareMarketsById(ServerCommandSource source, String first, String second) {
        List<KoilGlobalMarketViewRow> rows = marketViewRows(source);
        String resolvedFirst = resolveCommandQuery(source, first);
        String resolvedSecond = resolveCommandQuery(source, second);
        return compareResolvedMarkets(source, KoilMarketValueEngine.findMarketExactId(rows, resolvedFirst), KoilMarketValueEngine.findMarketExactId(rows, resolvedSecond), resolvedFirst, resolvedSecond);
    }

    private static int compareResolvedMarkets(ServerCommandSource source, KoilGlobalMarketViewRow firstRow, KoilGlobalMarketViewRow secondRow, String firstQuery, String secondQuery) {
        if (firstRow == null || secondRow == null) {
            List<String> notes = new ArrayList<>();
            notes.add("First -> " + firstQuery + (firstRow == null ? " not found" : " matched " + firstRow.title()));
            notes.add("Second -> " + secondQuery + (secondRow == null ? " not found" : " matched " + secondRow.title()));
            notes.add("Use -> /market compare <first> to <second>");
            return showMarketCommandHud(source, "error", "Market Compare", "Unable to resolve both markets", notes);
        }

        List<KoilGlobalMarketViewRow> rows = marketViewRows(source);
        KoilMarketValueQuote first = KoilMarketValueEngine.quote(firstRow, rows);
        KoilMarketValueQuote second = KoilMarketValueEngine.quote(secondRow, rows);
        double ratio = first.momensValue() / Math.max(0.001D, second.momensValue());
        boolean firstStronger = ratio >= 1.0D;
        String ratioText = firstStronger
                ? "1 " + firstRow.subject() + " -> " + KoilMarketValueQuote.compactDecimal(ratio) + " " + secondRow.subject()
                : "1 " + secondRow.subject() + " -> " + KoilMarketValueQuote.compactDecimal(1.0D / Math.max(0.001D, ratio)) + " " + firstRow.subject();
        int confidence = Math.min(firstRow.confidence(), secondRow.confidence());

        if (sendMarketHud(source, compareMarketSnapshot(source, firstRow, secondRow, rows, ratioText, confidence))) {
            return 1;
        }

        source.sendFeedback(() -> Text.literal("Market Compare -> " + ratioText), false);
        return 1;
    }


    private static KoilMarketHudSnapshot singleMarketSnapshot(ServerCommandSource commandSource, String mode, boolean watch, String watchQuery, String title, String subtitle, List<KoilGlobalMarketViewRow> rows, KoilGlobalMarketViewRow row) {
        String source = (row.authoritative() ? "server exact" : "estimated") + " | confidence " + row.confidence() + "%";
        return new KoilMarketHudSnapshot(mode, watch, System.currentTimeMillis(), watchQuery, title, subtitle, KoilMarketValueEngine.baseExchangeLine(rows), source, List.of(marketHudEntry(commandSource, row, rows)));
    }

    private static KoilMarketHudSnapshot compareMarketSnapshot(ServerCommandSource commandSource, KoilGlobalMarketViewRow firstRow, KoilGlobalMarketViewRow secondRow, List<KoilGlobalMarketViewRow> rows, String ratioText, int confidence) {
        String source = sourceLabel(firstRow, secondRow) + " | confidence " + confidence + "%";
        return new KoilMarketHudSnapshot("compare", false, System.currentTimeMillis(), "", "Market Portfolio", ratioText, KoilMarketValueEngine.baseExchangeLine(rows), source, List.of(marketHudEntry(commandSource, firstRow, rows), marketHudEntry(commandSource, secondRow, rows)));
    }

    private static KoilMarketHudSnapshot.Entry marketHudEntry(ServerCommandSource source, KoilGlobalMarketViewRow row, List<KoilGlobalMarketViewRow> rows) {
        KoilMarketValueQuote quote = KoilMarketValueEngine.quote(row, rows);
        int[] series = marketHudSeries(row, rows);
        return new KoilMarketHudSnapshot.Entry(
                row.id(),
                row.title(),
                row.subject(),
                quote.compactMomens(),
                quote.compactExchange(row.subject()),
                quote.reserveTitle(),
                row.confidence(),
                row.authoritative(),
                quote.demandScore(),
                quote.supplyScore(),
                quote.scarcityScore(),
                quote.trendScore(),
                series,
                "",
                "",
                "",
                "",
                "",
                ""
        );
    }

    private static KoilMarketHudSnapshot.Entry marketHudEntryWithDeltas(KoilGlobalMarketViewRow row, List<KoilGlobalMarketViewRow> rows, CatchBaseline previous, KoilMarketValueQuote quote, int lastPoint) {
        return new KoilMarketHudSnapshot.Entry(
                row.id(),
                row.title(),
                row.subject(),
                quote.compactMomens(),
                quote.compactExchange(row.subject()),
                quote.reserveTitle(),
                row.confidence(),
                row.authoritative(),
                quote.demandScore(),
                quote.supplyScore(),
                quote.scarcityScore(),
                quote.trendScore(),
                marketHudSeries(row, rows),
                signedDeltaText(quote.momensValue(), previous.momens(), "ƒ"),
                signedDeltaText(quote.demandScore(), previous.demand()),
                signedDeltaText(quote.supplyScore(), previous.supply()),
                signedDeltaText(quote.scarcityScore(), previous.scarcity()),
                signedDeltaText(quote.trendScore(), previous.trend()),
                signedDeltaText(lastPoint, previous.lastPoint())
        );
    }


    private static int[] marketHudSeries(KoilGlobalMarketViewRow row, List<KoilGlobalMarketViewRow> rows) {
        if (row != null && row.chartRow() != null) {
            int[] raw = row.chartRow().pointsView();

            if (raw.length > 0) {
                return marketHudMainLine(row, raw);
            }
        }

        return KoilMarketValueEngine.valueSeries(row, rows);
    }

    private static int[] marketHudMainLine(KoilGlobalMarketViewRow row, int[] source) {
        if (source == null || source.length == 0) {
            return new int[0];
        }

        int[] copy = Arrays.copyOf(source, source.length);

        if (row == null || !"raw".equals(row.tier())) {
            return copy;
        }

        String metric = row.rawMetrics().isEmpty() ? row.chartRow().metric() : row.rawMetrics().get(0);
        String key = row.action() + " " + metric;
        String category = KoilMarketLedger.categoryForMetric(key);

        if (!KoilMarketLedger.shouldAffectPrice(category)) {
            return copy;
        }

        int direction = KoilMarketLedger.isSupply(category, key) || KoilMarketLedger.isNegative(category, key) ? -1 : 1;
        int[] out = new int[copy.length];
        out[0] = 0;

        for (int i = 1; i < copy.length; i++) {
            int movement = Math.max(0, copy[i] - copy[i - 1]);
            out[i] = out[i - 1] + direction * movement;
        }

        return out;
    }

    private static boolean hasVisibleSpread(int[] points) {
        if (points == null || points.length < 2) {
            return false;
        }

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (int point : points) {
            min = Math.min(min, point);
            max = Math.max(max, point);
        }

        return max - min >= 2;
    }

    private static boolean sendMarketHud(ServerCommandSource source, KoilMarketHudSnapshot snapshot) {
        ServerPlayerEntity player;

        try {
            player = source.getPlayer();
        } catch (Exception ignored) {
            return false;
        }

        if (player == null || !ServerPlayNetworking.canSend(player, GlobalActivityApi.MARKET_HUD_CHANNEL)) {
            return false;
        }

        PacketByteBuf buf = PacketByteBufs.create();
        snapshot.write(buf);
        ServerPlayNetworking.send(player, GlobalActivityApi.MARKET_HUD_CHANNEL, buf);
        return true;
    }

    private static int showMarketCommandHud(ServerCommandSource source, String mode, String title, String subtitle, List<String> notes) {
        List<KoilGlobalMarketViewRow> rows = marketViewRows(source);
        String reserveLine = KoilMarketValueEngine.baseExchangeLine(rows);
        String sourceLine = "server command | " + rows.size() + " market row(s)";
        KoilMarketHudSnapshot snapshot = new KoilMarketHudSnapshot(mode, false, System.currentTimeMillis(), "", title, subtitle, reserveLine, sourceLine, List.of(), notes);

        if (sendMarketHud(source, snapshot)) {
            return 1;
        }

        source.sendFeedback(() -> Text.literal(title + " -> " + subtitle), false);

        for (String note : notes) {
            source.sendFeedback(() -> Text.literal(note), false);
        }

        return 1;
    }

    private static String padRight(String value, int width) {
        String safe = value == null ? "" : value;

        if (safe.length() >= width) {
            return safe;
        }

        return safe + " ".repeat(width - safe.length());
    }

    private static String clipText(String value, int max) {
        String safe = value == null ? "" : value;

        if (safe.length() <= max) {
            return safe;
        }

        return safe.substring(0, Math.max(0, max - 1)) + "~";
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

    private static String[] splitCompareExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return new String[0];
        }

        String clean = expression.trim();
        String lower = clean.toLowerCase(Locale.ROOT);
        int separator = lower.indexOf(" to ");

        if (separator < 0) {
            separator = lower.indexOf(" -> ");
        }

        if (separator < 0) {
            return new String[0];
        }

        String token = lower.startsWith(" -> ", separator) ? " -> " : " to ";
        String first = clean.substring(0, separator).replace('"', ' ').trim();
        String second = clean.substring(separator + token.length()).replace('"', ' ').trim();

        if (first.isBlank() || second.isBlank()) {
            return new String[0];
        }

        return new String[]{first, second};
    }

    private static int showBaseResource(ServerCommandSource source) {
        List<KoilGlobalMarketViewRow> rows = marketViewRows(source);
        KoilMarketValueConfig config = KoilMarketValueConfig.current();
        return showMarketCommandHud(source, "base", "Momens Base", "Current reserve conversion", List.of(
                KoilMarketValueEngine.baseExchangeLine(rows),
                "Mode -> " + (config.dynamicReserveBase() ? "dynamic strongest resource" : "configured reserve basket"),
                "Unit -> " + config.momensIcon()
        ));
    }


    private static int showCurrency(ServerCommandSource source) {
        KoilMarketValueConfig config = KoilMarketValueConfig.current();
        List<KoilGlobalMarketViewRow> rows = marketViewRows(source);
        List<String> notes = new ArrayList<>();
        notes.add("Momens -> " + config.momensIcon());
        notes.add(KoilMarketValueEngine.baseExchangeLine(rows));
        notes.add(KoilPhysicalMomensCurrency.summary());

        if (KoilPhysicalMomensCurrency.available()) {
            notes.add(KoilPhysicalMomensCurrency.detailedSummary());
        }

        return showMarketCommandHud(source, "currency", "Momens Currency", "Physical currency status", notes);
    }


    private static int showSourceHelp(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("Momens source commands:"), false);
        source.sendFeedback(() -> Text.literal("-> /market source from minecraft:birch_planks 16"), false);
        source.sendFeedback(() -> Text.literal("-> /market source from minecraft:birch_planks bills 1 20_stack"), false);
        source.sendFeedback(() -> Text.literal("-> /market source to minecraft:birch_planks"), false);
        source.sendFeedback(() -> Text.literal("Short aliases: /market from <item> <count>, /market to <item>"), false);
        return 1;
    }

    private static int sourceMomensFromExpression(ServerCommandSource source, String expression) {
        String clean = cleanCommandExpression(expression);

        if (clean.isBlank()) {
            return showSourceHelp(source);
        }

        String lower = clean.toLowerCase(Locale.ROOT);
        int billsIndex = lower.indexOf(" bills ");

        if (billsIndex >= 0) {
            String query = cleanCommandExpression(clean.substring(0, billsIndex));
            String after = clean.substring(billsIndex + 7).trim();
            String[] tokens = after.split("\\s+");

            if (query.isBlank() || tokens.length < 2) {
                source.sendFeedback(() -> Text.literal("Source from -> use /market source from <item> bills <bills> <denomination>"), false);
                return 0;
            }

            int bills = parsePositiveInt(tokens[0], 0);
            String denomination = tokens[1];

            if (bills <= 0) {
                source.sendFeedback(() -> Text.literal("Source from -> bill count must be greater than 0."), false);
                return 0;
            }

            return exchangeBillsForMomens(source, bills, denomination, query);
        }

        int count = 1;
        String query = clean;
        int lastSpace = clean.lastIndexOf(' ');

        if (lastSpace > 0) {
            String last = clean.substring(lastSpace + 1).trim();
            int parsedCount = parsePositiveInt(last, -1);

            if (parsedCount > 0) {
                count = parsedCount;
                query = cleanCommandExpression(clean.substring(0, lastSpace));
            }
        }

        if (query.isBlank()) {
            source.sendFeedback(() -> Text.literal("Source from -> missing item or market query."), false);
            return 0;
        }

        return exchangeForMomens(source, count, query);
    }

    private static String koilReadableItemName(Item item) {
        try {
            return item.getName().getString();
        } catch (Exception ignored) {
            Identifier id = Registries.ITEM.getId(item);
            return id == null ? "unknown item" : id.toString();
        }
    }

    private static int sourceMomensToQuery(ServerCommandSource source, String query) {
        String clean = cleanCommandExpression(query);

        if (clean.isBlank() || "all".equalsIgnoreCase(clean)) {
            return redeemInventoryMomens(source);
        }

        ServerPlayerEntity player;

        try {
            player = source.getPlayer();
        } catch (Exception ignored) {
            source.sendFeedback(() -> Text.literal("Source to -> command must be run by a player."), false);
            return 0;
        }

        if (shouldIgnorePlayerMarketAction(player)) {
            source.sendFeedback(() -> Text.literal("Source to -> survival players only. Creative/spectator redemption is blocked."), false);
            return 0;
        }

        String resolvedClean = resolveCommandQuery(source, clean);
        List<KoilGlobalMarketViewRow> rows = marketViewRows(source);
        KoilGlobalMarketViewRow targetRow = resolveMarketRow(rows, resolvedClean);
        Identifier targetId = resolveItemIdForMarket(targetRow, resolvedClean);

        if (targetId == null || !Registries.ITEM.containsId(targetId)) {
            source.sendFeedback(() -> Text.literal("Source to -> no redeemable item matched " + resolvedClean).formatted(Formatting.RED), false);
            return 0;
        }

        Item targetItem = Registries.ITEM.get(targetId);

        if (targetItem == Items.AIR) {
            source.sendFeedback(() -> Text.literal("Source to -> air cannot be redeemed."), false);
            return 0;
        }

        List<Integer> consumedSlots = new ArrayList<>();
        List<KoilPhysicalMomensCurrency.SourceInfo> restoreSources = new ArrayList<>();
        int totalMatchingMomens = 0;
        int totalMatchingUnits = 0;
        int totalSourceMomens = 0;
        int fallbackUnitMomens = 0;
        int fallbackUnitSamples = 0;
        int matchingSourceCount = 0;

        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            ItemStack stack = player.getInventory().getStack(slot);

            if (!KoilPhysicalMomensCurrency.isMomens(stack)) {
                continue;
            }

            List<KoilPhysicalMomensCurrency.SourceEntry> entries = KoilPhysicalMomensCurrency.sources(stack);

            if (entries.isEmpty()) {
                continue;
            }

            int sourceTotal = 0;

            for (KoilPhysicalMomensCurrency.SourceEntry entry : entries) {
                sourceTotal += Math.max(0, entry.momens());
            }

            double safetyScale = Math.min(1.0D, KoilPhysicalMomensCurrency.stackMomensValue(stack) / (double) Math.max(1, sourceTotal));
            boolean slotHasMatchingSource = false;

            for (KoilPhysicalMomensCurrency.SourceEntry entry : entries) {
                int safeMomens = (int) Math.floor(Math.max(0, entry.momens()) * safetyScale);
                int safeUnits = (int) Math.floor(Math.max(0, entry.units()) * safetyScale);
                int safeUnitMomens = (int) Math.max(0, entry.unitMomens());
                boolean matching = entry.redeemable() && sourceEntryMatches(entry, targetId);

                if (matching) {
                    slotHasMatchingSource = true;
                    matchingSourceCount++;
                    totalMatchingMomens += safeMomens;
                    totalMatchingUnits += safeUnits;
                    totalSourceMomens += safeMomens;

                    if (safeUnitMomens > 0) {
                        fallbackUnitMomens += safeUnitMomens;
                        fallbackUnitSamples++;
                    }
                } else {
                    restoreSources.add(new KoilPhysicalMomensCurrency.SourceInfo(entry.id(), entry.title(), safeMomens, safeUnitMomens, safeUnits, entry.issuer(), entry.redeemable(), entry.reason()));
                }
            }

            if (slotHasMatchingSource) {
                consumedSlots.add(slot);
            }
        }

        if (matchingSourceCount <= 0 || totalMatchingMomens <= 0) {
            source.sendFeedback(() -> Text.literal("Source to -> no sourced Momens backed by " + targetId + " were found in your inventory."), false);
            return 0;
        }

        double fallbackUnitValue = fallbackUnitSamples <= 0 ? 1.0D : fallbackUnitMomens / (double) fallbackUnitSamples;
        double unitMomens = currentUnitMomens(rows, targetId, resolvedClean, fallbackUnitValue);
        unitMomens = Math.max(0.0001D, unitMomens);

        double valueBasedItemsRaw = totalMatchingMomens / unitMomens;
        int valueBasedItemsFloor = (int) Math.floor(valueBasedItemsRaw);
        int valueBasedItemsRounded = (int) Math.round(valueBasedItemsRaw);
        double unitDifference = Math.abs(valueBasedItemsRaw - totalMatchingUnits);
        double roundingUnitTolerance = Math.max(0.01D, Math.min(1.25D, totalMatchingUnits * 0.025D));

        int redeemableItems;

        if (unitDifference <= roundingUnitTolerance) {
            redeemableItems = totalMatchingUnits;
        } else if (Math.abs(valueBasedItemsRaw - valueBasedItemsRounded) <= 0.05D) {
            redeemableItems = valueBasedItemsRounded;
        } else {
            redeemableItems = valueBasedItemsFloor;
        }

        redeemableItems = Math.min(Math.max(0, totalMatchingUnits), Math.max(0, redeemableItems));

        if (redeemableItems <= 0) {
            int finalTotalMatchingMomens = totalMatchingMomens;
            double finalUnitMomens = unitMomens;
            source.sendFeedback(() -> Text.literal("Source to -> found ƒ" + finalTotalMatchingMomens + " backed by " + targetId + ", but current market value needs about ƒ" + String.format(Locale.ROOT, "%.2f", finalUnitMomens) + " per item."), false);
            return 0;
        }

        if (redeemableItems > 4096) {
            int finalRedeemableItems = redeemableItems;
            source.sendFeedback(() -> Text.literal("Source to -> payout would create " + finalRedeemableItems + " items. Split or compact first to avoid lag."), false);
            return 0;
        }

        int consumedMatchingMomens = (int) Math.ceil(redeemableItems * unitMomens);
        int consumedMatchingUnits = redeemableItems;
        int remainingMatchingMomens = Math.max(0, totalMatchingMomens - consumedMatchingMomens);
        int remainingMatchingUnits = Math.max(0, totalMatchingUnits - consumedMatchingUnits);
        int remainingUnitMomens = fallbackUnitSamples <= 0 ? Math.max(1, (int) Math.round(unitMomens)) : Math.max(1, Math.round(fallbackUnitMomens / (float) fallbackUnitSamples));

        for (int slot : consumedSlots) {
            player.getInventory().setStack(slot, ItemStack.EMPTY);
        }

        if (remainingMatchingMomens > 0 && remainingMatchingUnits > 0) {
            restoreSources.add(new KoilPhysicalMomensCurrency.SourceInfo(
                    targetId.toString(),
                    koilReadableItemName(targetItem),
                    remainingMatchingMomens,
                    remainingUnitMomens,
                    remainingMatchingUnits,
                    player.getGameProfile().getName(),
                    true,
                    "remaining source backing after partial redemption"
            ));
        }

        for (KoilPhysicalMomensCurrency.SourceInfo sourceInfo : restoreSources) {
            if (sourceInfo.momens() > 0) {
                KoilPhysicalMomensCurrency.giveMomens(player, sourceInfo.momens(), sourceInfo);
            }
        }

        giveItems(player, targetItem, redeemableItems);
        recordTransferActivity(source.getServer(), player.getUuid(), player.getGameProfile().getName(), "momens_redeem/" + targetId.getNamespace() + "/" + targetId.getPath(), redeemableItems);

        int finalRedeemableItems = redeemableItems;
        int finalTotalMatchingMomens = totalMatchingMomens;
        double finalUnitMomens = unitMomens;
        source.sendFeedback(() -> Text.literal("Source to -> ƒ" + finalTotalMatchingMomens + " sourced Momens -> " + finalRedeemableItems + "x " + targetId), false);
        source.sendFeedback(() -> Text.literal("Cashed out -> " + targetId + " from every matching sourced bill in inventory."), false);
        source.sendFeedback(() -> Text.literal("Rate -> about ƒ" + String.format(Locale.ROOT, "%.2f", finalUnitMomens) + " each, with small rounding tolerance."), false);
        source.sendFeedback(() -> Text.literal("Accounting -> matched source backing consumed, capped by original sourced units."), false);
        return 1;
    }

    private static String cleanCommandExpression(String value) {
        if (value == null) {
            return "";
        }

        String clean = value.trim();

        if (clean.endsWith(",")) {
            clean = clean.substring(0, clean.length() - 1).trim();
        }

        if ((clean.startsWith("\"") && clean.endsWith("\"")) || (clean.startsWith("'") && clean.endsWith("'"))) {
            clean = clean.substring(1, clean.length() - 1).trim();
        }

        return clean;
    }

    private static String resolveCommandQuery(ServerCommandSource source, String query) {
        String clean = cleanCommandExpression(query);
        String selected = selectedItemQuery(source);

        if (selected == null || clean.isBlank()) {
            return clean;
        }

        String lower = clean.toLowerCase(Locale.ROOT);

        if (lower.equals("this") || lower.equals("selected") || lower.equals("held")) {
            return selected;
        }

        return clean.replaceAll("(?i)\\bthis\\b", java.util.regex.Matcher.quoteReplacement(selected));
    }

    private static String selectedItemQuery(ServerCommandSource source) {
        ServerPlayerEntity player;

        try {
            player = source.getPlayer();
        } catch (Exception ignored) {
            return null;
        }

        ItemStack stack = ItemStack.EMPTY;

        try {
            stack = player.getInventory().getStack(player.getInventory().selectedSlot);
        } catch (Exception ignored) {
        }

        if (stack.isEmpty()) {
            stack = player.getMainHandStack();
        }

        if (stack.isEmpty() || stack.getItem() == Items.AIR) {
            return null;
        }

        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id == null ? null : id.toString();
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean sourceEntryMatches(KoilPhysicalMomensCurrency.SourceEntry entry, Identifier targetId) {
        Identifier id = parseItemId(entry.id());
        return id != null && id.equals(targetId);
    }

    private static double currentUnitMomens(List<KoilGlobalMarketViewRow> rows, Identifier itemId, String query, double fallback) {
        KoilGlobalMarketViewRow row = KoilMarketValueEngine.findMarketExactId(rows, itemId.toString());

        if (row == null) {
            row = resolveMarketRow(rows, query);
        }

        if (row != null) {
            double value = KoilMarketValueEngine.quote(row, rows).momensValue();

            if (value > 0.0D) {
                return value;
            }
        }

        return Math.max(0.0001D, fallback);
    }

    private static int exchangeForMomens(ServerCommandSource source, int count, String query) {
        if (!KoilPhysicalMomensCurrency.available()) {
            source.sendFeedback(() -> Text.literal("Exchange -> physical Momens not detected. Install items like shit:1_momen and shit:1_momen_stack."), false);
            return 0;
        }

        ServerPlayerEntity player;

        try {
            player = source.getPlayer();
        } catch (Exception ignored) {
            source.sendFeedback(() -> Text.literal("Exchange -> command must be run by a player."), false);
            return 0;
        }

        if (shouldIgnorePlayerMarketAction(player)) {
            source.sendFeedback(() -> Text.literal("Exchange -> survival players only. Creative/spectator resources cannot back Momens."), false);
            return 0;
        }

        String resolvedQuery = resolveCommandQuery(source, query);
        List<KoilGlobalMarketViewRow> rows = marketViewRows(source);
        KoilGlobalMarketViewRow row = resolveMarketRow(rows, resolvedQuery);

        if (row == null) {
            source.sendFeedback(() -> Text.literal("Exchange -> no market matched " + resolvedQuery).formatted(Formatting.RED), false);
            return 0;
        }

        Identifier itemId = resolveItemIdForMarket(row, resolvedQuery);

        if (itemId == null || !Registries.ITEM.containsId(itemId)) {
            source.sendFeedback(() -> Text.literal("Exchange -> market has no redeemable item id: " + row.title()), false);
            return 0;
        }

        Item item = Registries.ITEM.get(itemId);

        if (item == Items.AIR) {
            source.sendFeedback(() -> Text.literal("Exchange -> air cannot back Momens."), false);
            return 0;
        }

        int available = countInventoryItems(player, item);

        if (available < count) {
            source.sendFeedback(() -> Text.literal("Exchange -> need " + count + "x " + itemId + " but inventory has " + available), false);
            return 0;
        }

        KoilMarketValueQuote quote = KoilMarketValueEngine.quote(row, rows);
        double unitMomens = Math.max(0.0D, quote.momensValue());
        int totalMomens = (int) Math.floor(unitMomens * count);

        if (totalMomens <= 0) {
            int needed = Math.max(1, (int) Math.ceil(1.0D / Math.max(0.0001D, unitMomens)));
            source.sendFeedback(() -> Text.literal("Exchange -> " + row.title() + " is worth less than ƒ1 each. Try at least " + needed + " items."), false);
            return 0;
        }

        if (removeInventoryItems(player, item, count) < count) {
            source.sendFeedback(() -> Text.literal("Exchange -> inventory changed before the exchange could complete."), false);
            return 0;
        }

        KoilPhysicalMomensCurrency.SourceInfo sourceInfo = new KoilPhysicalMomensCurrency.SourceInfo(itemId.toString(), row.title(), totalMomens, unitMomens, count, player.getGameProfile().getName(), true, "survival_exchange");
        int given = KoilPhysicalMomensCurrency.giveMomens(player, totalMomens, sourceInfo);
        recordTransferActivity(source.getServer(), player.getUuid(), player.getGameProfile().getName(), "momens_exchange/" + itemId.getNamespace() + "/" + itemId.getPath(), count);
        source.sendFeedback(() -> Text.literal("Exchange -> " + count + "x " + itemId + " -> ƒ" + given + " sourced Momens"), false);
        source.sendFeedback(() -> Text.literal("Backed by -> " + row.title() + " at " + quote.compactMomens() + " each"), false);
        source.sendFeedback(() -> Text.literal("Accounting -> reserve transfer, not counted as new supply or demand."), false);
        return given > 0 ? 1 : 0;
    }

    private static int exchangeBillsForMomens(ServerCommandSource source, int bills, String denomination, String query) {
        if (!KoilPhysicalMomensCurrency.available()) {
            source.sendFeedback(() -> Text.literal("Exchange bills -> physical Momens not detected."), false);
            return 0;
        }

        ServerPlayerEntity player;

        try {
            player = source.getPlayer();
        } catch (Exception ignored) {
            source.sendFeedback(() -> Text.literal("Exchange bills -> command must be run by a player."), false);
            return 0;
        }

        if (shouldIgnorePlayerMarketAction(player)) {
            source.sendFeedback(() -> Text.literal("Exchange bills -> survival players only."), false);
            return 0;
        }

        int billValue = KoilPhysicalMomensCurrency.denominationValue(denomination);

        if (billValue <= 0) {
            source.sendFeedback(() -> Text.literal("Exchange bills -> denomination must be one of 1, 5, 10, 20, 50, 100, 500, or *_stack."), false);
            return 0;
        }

        Item billItem = KoilPhysicalMomensCurrency.itemForDenomination(denomination);

        if (billItem == null) {
            source.sendFeedback(() -> Text.literal("Exchange bills -> missing physical item for denomination " + denomination), false);
            return 0;
        }

        String resolvedQuery = resolveCommandQuery(source, query);
        List<KoilGlobalMarketViewRow> rows = marketViewRows(source);
        KoilGlobalMarketViewRow row = resolveMarketRow(rows, resolvedQuery);

        if (row == null) {
            source.sendFeedback(() -> Text.literal("Exchange bills -> no market matched " + resolvedQuery).formatted(Formatting.RED), false);
            return 0;
        }

        Identifier itemId = resolveItemIdForMarket(row, resolvedQuery);

        if (itemId == null || !Registries.ITEM.containsId(itemId)) {
            source.sendFeedback(() -> Text.literal("Exchange bills -> market has no backing item id: " + row.title()), false);
            return 0;
        }

        Item backingItem = Registries.ITEM.get(itemId);
        KoilMarketValueQuote quote = KoilMarketValueEngine.quote(row, rows);
        double unitMomens = Math.max(0.0001D, quote.momensValue());
        int targetMomens = Math.multiplyExact(bills, billValue);
        int requiredItems = Math.max(1, (int) Math.ceil(targetMomens / unitMomens));
        int available = countInventoryItems(player, backingItem);

        if (available < requiredItems) {
            source.sendFeedback(() -> Text.literal("Exchange bills -> need " + requiredItems + "x " + itemId + " for " + bills + " bill(s), but inventory has " + available), false);
            return 0;
        }

        if (removeInventoryItems(player, backingItem, requiredItems) < requiredItems) {
            source.sendFeedback(() -> Text.literal("Exchange bills -> inventory changed before the exchange could complete."), false);
            return 0;
        }

        KoilPhysicalMomensCurrency.SourceInfo sourceInfo = new KoilPhysicalMomensCurrency.SourceInfo(itemId.toString(), row.title(), targetMomens, unitMomens, requiredItems, player.getGameProfile().getName(), true, "survival_exchange");
        int given = KoilPhysicalMomensCurrency.giveExactMomens(player, billItem, bills, billValue, sourceInfo);
        recordTransferActivity(source.getServer(), player.getUuid(), player.getGameProfile().getName(), "momens_exchange/" + itemId.getNamespace() + "/" + itemId.getPath(), requiredItems);
        source.sendFeedback(() -> Text.literal("Exchange bills -> " + requiredItems + "x " + itemId + " -> " + bills + "x " + denomination + " (ƒ" + given + ")"), false);
        source.sendFeedback(() -> Text.literal("Backed by -> " + row.title() + " at " + quote.compactMomens() + " each"), false);
        return given > 0 ? 1 : 0;
    }

    private static int redeemInventoryMomens(ServerCommandSource source) {
        ServerPlayerEntity player;

        try {
            player = source.getPlayer();
        } catch (Exception ignored) {
            source.sendFeedback(() -> Text.literal("Redeem -> command must be run by a player."), false);
            return 0;
        }

        if (shouldIgnorePlayerMarketAction(player)) {
            source.sendFeedback(() -> Text.literal("Redeem -> survival players only. Creative/spectator redemption is blocked."), false);
            return 0;
        }

        List<KoilPhysicalMomensCurrency.SourceEntry> sources = new ArrayList<>();
        int physicalMomens = 0;
        int sourcedSlots = 0;

        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);

            if (!KoilPhysicalMomensCurrency.isMomens(stack)) {
                continue;
            }

            List<KoilPhysicalMomensCurrency.SourceEntry> stackSources = KoilPhysicalMomensCurrency.sources(stack);

            if (!hasRedeemableSource(stackSources)) {
                continue;
            }

            sources.addAll(stackSources);
            physicalMomens += KoilPhysicalMomensCurrency.stackMomensValue(stack);
            sourcedSlots++;
        }

        if (sources.isEmpty()) {
            source.sendFeedback(() -> Text.literal("Redeem -> no sourced Momens were found in your inventory."), false);
            source.sendFeedback(() -> Text.literal("Legacy/unsourced or admin-issued notes cannot redeem resources."), false);
            return 0;
        }

        List<KoilGlobalMarketViewRow> rows = marketViewRows(source);
        Map<Item, Integer> payout = new LinkedHashMap<>();
        Map<String, Integer> payoutIds = new LinkedHashMap<>();
        Map<String, Integer> originalUnits = new LinkedHashMap<>();
        int sourceMomensTotal = 0;

        for (KoilPhysicalMomensCurrency.SourceEntry entry : sources) {
            if (!entry.redeemable()) {
                continue;
            }

            Identifier id = parseItemId(entry.id());

            if (id == null || !Registries.ITEM.containsId(id)) {
                continue;
            }

            sourceMomensTotal += Math.max(0, entry.momens());
            originalUnits.put(id.toString(), originalUnits.getOrDefault(id.toString(), 0) + Math.max(0, entry.units()));
        }

        int safeRedeemMomens = Math.min(physicalMomens, sourceMomensTotal);

        if (safeRedeemMomens <= 0) {
            source.sendFeedback(() -> Text.literal("Redeem -> sourced Momens were found, but none are resource-backed."), false);
            return 0;
        }

        int totalItems = 0;
        List<String> redeemLines = new ArrayList<>();

        for (KoilPhysicalMomensCurrency.SourceEntry entry : sources) {
            if (!entry.redeemable() || entry.momens() <= 0) {
                continue;
            }

            Identifier id = parseItemId(entry.id());

            if (id == null || !Registries.ITEM.containsId(id)) {
                continue;
            }

            Item item = Registries.ITEM.get(id);
            KoilGlobalMarketViewRow row = KoilMarketValueEngine.findMarketExactId(rows, id.toString());

            if (row == null) {
                row = resolveMarketRow(rows, id.getPath().replace('_', ' '));
            }

            double unitMomens = row == null ? entry.unitMomens() : KoilMarketValueEngine.quote(row, rows).momensValue();

            if (unitMomens <= 0.0D) {
                unitMomens = Math.max(0.0001D, entry.unitMomens());
            }

            int assignedMomens = Math.max(0, (int) Math.floor(safeRedeemMomens * (entry.momens() / (double) Math.max(1, sourceMomensTotal))));
            int marketItems = (int) Math.floor(assignedMomens / unitMomens);
            int cappedItems = Math.min(Math.max(0, entry.units()), marketItems);

            if (cappedItems <= 0) {
                continue;
            }

            payout.put(item, payout.getOrDefault(item, 0) + cappedItems);
            payoutIds.put(id.toString(), payoutIds.getOrDefault(id.toString(), 0) + cappedItems);
            totalItems += cappedItems;
            redeemLines.add(cappedItems + "x " + id + " from ƒ" + assignedMomens + " backing");
        }

        if (totalItems <= 0) {
            source.sendFeedback(() -> Text.literal("Redeem -> current market value is too high for your sourced bills to redeem one backed item."), false);
            int finalSourcedSlots = sourcedSlots;
            source.sendFeedback(() -> Text.literal("Redeemable backing -> ƒ" + safeRedeemMomens + " across " + finalSourcedSlots + " inventory slot(s)."), false);
            return 0;
        }

        if (totalItems > 4096) {
            int finalTotalItems = totalItems;
            source.sendFeedback(() -> Text.literal("Redeem -> payout would create " + finalTotalItems + " items. Compact or split redemption later to avoid lag."), false);
            return 0;
        }

        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);

            if (!KoilPhysicalMomensCurrency.isMomens(stack) || !hasRedeemableSource(KoilPhysicalMomensCurrency.sources(stack))) {
                continue;
            }

            player.getInventory().setStack(i, ItemStack.EMPTY);
        }

        for (Map.Entry<Item, Integer> entry : payout.entrySet()) {
            giveItems(player, entry.getKey(), entry.getValue());
        }

        for (Map.Entry<String, Integer> entry : payoutIds.entrySet()) {
            Identifier id = parseItemId(entry.getKey());

            if (id != null) {
                recordTransferActivity(source.getServer(), player.getUuid(), player.getGameProfile().getName(), "momens_redeem/" + id.getNamespace() + "/" + id.getPath(), entry.getValue());
            }
        }

        source.sendFeedback(() -> Text.literal("Redeem -> cashing out ƒ" + safeRedeemMomens + " sourced Momens from your inventory"), false);

        int limit = Math.min(6, redeemLines.size());

        for (int i = 0; i < limit; i++) {
            String line = redeemLines.get(i);
            source.sendFeedback(() -> Text.literal("-> " + line), false);
        }

        if (redeemLines.size() > limit) {
            source.sendFeedback(() -> Text.literal("-> " + (redeemLines.size() - limit) + " more source payout(s)"), false);
        }

        source.sendFeedback(() -> Text.literal("Accounting -> reserve transfer, capped by original source units, not new market supply."), false);
        return 1;
    }

    private static int compactMomens(ServerCommandSource source) {
        ServerPlayerEntity player;

        try {
            player = source.getPlayer();
        } catch (Exception ignored) {
            source.sendFeedback(() -> Text.literal("Compact -> command must be run by a player."), false);
            return 0;
        }

        int compacted = KoilPhysicalMomensCurrency.compactInventory(player);
        source.sendFeedback(() -> Text.literal("Compact -> rebuilt " + compacted + " physical Momens item(s) with merged source weighting."), false);
        return compacted > 0 ? 1 : 0;
    }

    private static int mintMomens(ServerCommandSource source, String playerName, int amount) {
        if (!KoilPhysicalMomensCurrency.available()) {
            source.sendFeedback(() -> Text.literal("Mint -> physical Momens not detected. Install items like shit:1_momen and shit:1_momen_stack."), false);
            return 0;
        }

        ServerPlayerEntity player = source.getServer().getPlayerManager().getPlayer(playerName);

        if (player == null) {
            source.sendFeedback(() -> Text.literal("Mint -> player not found: " + playerName), false);
            return 0;
        }

        int given = KoilPhysicalMomensCurrency.giveMomens(player, amount);
        source.sendFeedback(() -> Text.literal("Mint -> ƒ" + given + " physical Momens issued to " + player.getGameProfile().getName()), false);
        source.sendFeedback(() -> Text.literal("Accounting -> admin-issued currency, ignored by market value pressure."), false);
        return given > 0 ? 1 : 0;
    }

    private static int listReserveBasket(ServerCommandSource source) {
        List<String> reserves = KoilMarketValueEngine.reserveBasketLabels();
        List<String> notes = new ArrayList<>();

        if (reserves.isEmpty()) {
            notes.add("No reserve resources are configured.");
        } else {
            for (String reserve : reserves) {
                notes.add("Reserve -> " + reserve);
            }
        }

        showMarketCommandHud(source, "reserves", "Momens Reserves", "Reserve basket", notes);
        return reserves.size();
    }


    private static int showMarketConfig(ServerCommandSource source) {
        KoilMarketValueConfig config = KoilMarketValueConfig.current();
        return showMarketCommandHud(source, "config", "Momens Config", "Market value settings", List.of(
                "Momens -> " + config.momensEnabled() + " | icon " + config.momensIcon(),
                "Tick -> " + config.marketTickSeconds() + "s | smoothing " + config.priceSmoothing(),
                "Anti-spam -> " + config.antiSpamStrength(),
                "Minimum confidence -> " + config.minimumConfidenceToShowPrice(),
                "Estimated markets -> " + config.allowClientEstimatedMarkets(),
                "Dynamic base -> " + config.dynamicReserveBase(),
                "Physical Momens -> " + config.physicalMomensCurrency(),
                "Reserve basket -> " + String.join(", ", config.reserveItems())
        ));
    }


    private static int reloadMarketConfig(ServerCommandSource source) {
        KoilMarketValueConfig config = KoilMarketValueConfig.reload();
        return showMarketCommandHud(source, "config", "Momens Config", "Reloaded market settings", List.of(
                "Icon -> " + config.momensIcon(),
                "Reserves -> " + config.reserveItems().size(),
                "Dynamic base -> " + config.dynamicReserveBase(),
                "Physical Momens -> " + config.physicalMomensCurrency()
        ));
    }


    private static List<KoilGlobalMarketViewRow> marketViewRows(ServerCommandSource source) {
        List<KoilGlobalActivityRow> activityRows = state(source.getServer()).snapshotRows(COMMAND_MARKET_LOOKUP_LIMIT);
        Map<String, List<KoilGlobalMarketViewRow>> hierarchy = KoilGlobalMarketViewRow.buildHierarchy(activityRows, "");
        List<KoilGlobalMarketViewRow> rows = new ArrayList<>();

        for (List<KoilGlobalMarketViewRow> group : hierarchy.values()) {
            rows.addAll(group);
        }

        return dedupeMarketViewRows(rows);
    }

    private static List<KoilGlobalMarketViewRow> dedupeMarketViewRows(List<KoilGlobalMarketViewRow> rows) {
        Map<String, KoilGlobalMarketViewRow> byKey = new LinkedHashMap<>();

        for (KoilGlobalMarketViewRow row : rows) {
            if (row == null) {
                continue;
            }

            String key = row.tier() + "|" + row.id();
            byKey.putIfAbsent(key, row);
        }

        return new ArrayList<>(byKey.values());
    }

    private static KoilGlobalMarketViewRow resolveMarketRow(List<KoilGlobalMarketViewRow> rows, String query) {
        KoilGlobalMarketViewRow row = KoilMarketValueEngine.findMarketExactId(rows, query);

        if (row != null) {
            return row;
        }

        return KoilMarketValueEngine.findMarket(rows, query);
    }

    private static String titleWords(String value) {
        String clean = value == null ? "" : value.replace(':', ' ').replace('/', ' ').replace('_', ' ').replace('-', ' ').trim();

        if (clean.isBlank()) {
            return "Unknown";
        }

        StringBuilder builder = new StringBuilder();

        for (String part : clean.split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }

            if (builder.length() > 0) {
                builder.append(' ');
            }

            builder.append(Character.toUpperCase(part.charAt(0))).append(part.length() > 1 ? part.substring(1) : "");
        }

        return builder.length() == 0 ? "Unknown" : builder.toString();
    }

    private static Identifier resolveItemIdForMarket(KoilGlobalMarketViewRow row, String query) {
        List<String> candidates = new ArrayList<>();
        candidates.add(query);

        if (row != null) {
            candidates.add(row.subject());
            candidates.add(row.id());
            candidates.add(row.title());
            candidates.addAll(row.rawMetrics());
        }

        for (String candidate : candidates) {
            Identifier id = parseItemId(candidate);

            if (id != null && Registries.ITEM.containsId(id) && Registries.ITEM.get(id) != Items.AIR) {
                return id;
            }
        }

        for (String candidate : candidates) {
            Identifier blockId = parseBlockId(candidate);

            if (blockId == null || !Registries.BLOCK.containsId(blockId)) {
                continue;
            }

            Item item = Registries.BLOCK.get(blockId).asItem();

            if (item != Items.AIR) {
                return Registries.ITEM.getId(item);
            }
        }

        return null;
    }

    private static Identifier parseItemId(String value) {
        Identifier exact = parseIdentifier(value);

        if (exact != null && Registries.ITEM.containsId(exact)) {
            return exact;
        }

        Identifier pair = parseIdentifierPair(value, true);

        if (pair != null && Registries.ITEM.containsId(pair)) {
            return pair;
        }

        return null;
    }

    private static Identifier parseBlockId(String value) {
        Identifier exact = parseIdentifier(value);

        if (exact != null && Registries.BLOCK.containsId(exact)) {
            return exact;
        }

        Identifier pair = parseIdentifierPair(value, false);

        if (pair != null && Registries.BLOCK.containsId(pair)) {
            return pair;
        }

        return null;
    }

    private static Identifier parseIdentifier(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String clean = value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');

        try {
            if (clean.contains(":")) {
                return new Identifier(clean);
            }

            if (clean.contains("/")) {
                String[] parts = clean.split("/");

                for (int i = 0; i < parts.length - 1; i++) {
                    Identifier id = new Identifier(parts[i], parts[i + 1]);

                    if (Registries.ITEM.containsId(id) || Registries.BLOCK.containsId(id)) {
                        return id;
                    }
                }
            }

            Identifier minecraft = new Identifier("minecraft", clean);

            if (Registries.ITEM.containsId(minecraft) || Registries.BLOCK.containsId(minecraft)) {
                return minecraft;
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private static Identifier parseIdentifierPair(String value, boolean item) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String[] parts = value.toLowerCase(Locale.ROOT).replace(':', '/').replace(' ', '/').replace('-', '_').split("/+");

        for (int i = 0; i < parts.length - 1; i++) {
            try {
                Identifier id = new Identifier(parts[i], parts[i + 1]);

                if (item && Registries.ITEM.containsId(id)) {
                    return id;
                }

                if (!item && Registries.BLOCK.containsId(id)) {
                    return id;
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private static int countInventoryItems(ServerPlayerEntity player, Item item) {
        int count = 0;

        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);

            if (!stack.isEmpty() && stack.isOf(item)) {
                count += stack.getCount();
            }
        }

        return count;
    }

    private static int removeInventoryItems(ServerPlayerEntity player, Item item, int count) {
        int remaining = count;
        int removed = 0;

        for (int i = 0; i < player.getInventory().size() && remaining > 0; i++) {
            ItemStack stack = player.getInventory().getStack(i);

            if (stack.isEmpty() || !stack.isOf(item)) {
                continue;
            }

            int take = Math.min(remaining, stack.getCount());
            stack.decrement(take);
            remaining -= take;
            removed += take;

            if (stack.isEmpty()) {
                player.getInventory().setStack(i, ItemStack.EMPTY);
            }
        }

        return removed;
    }

    private static void giveItems(ServerPlayerEntity player, Item item, int count) {
        int remaining = count;
        int max = Math.max(1, new ItemStack(item).getMaxCount());

        while (remaining > 0) {
            int size = Math.min(max, remaining);
            ItemStack stack = new ItemStack(item, size);

            if (!player.getInventory().insertStack(stack)) {
                player.dropItem(stack, false);
            }

            remaining -= size;
        }
    }

    private static boolean hasRedeemableSource(List<KoilPhysicalMomensCurrency.SourceEntry> sources) {
        for (KoilPhysicalMomensCurrency.SourceEntry entry : sources) {
            if (entry.redeemable() && entry.momens() > 0) {
                return true;
            }
        }

        return false;
    }

    private static KoilGlobalActivityState state(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(KoilGlobalActivityState::fromNbt, KoilGlobalActivityState::new, "koil_global_activity");
    }

    private static boolean updatePlayer(KoilGlobalActivityState state, ServerPlayerEntity player) {
        Map<String, Integer> rawStats = collectPlayerStats(player);

        if (shouldIgnorePlayerMarketAction(player)) {
            absorbIgnoredDeltas(player.getUuid(), rawStats);
            return false;
        }

        Map<String, Integer> adjustedStats = applyIgnoredOffsets(player.getUuid(), rawStats);
        lastRawStats.put(player.getUuid(), new LinkedHashMap<>(rawStats));
        return state.updatePlayer(player.getUuid(), player.getGameProfile().getName(), adjustedStats);
    }


    private static boolean shouldIgnorePlayerMarketAction(ServerPlayerEntity player) {
        if (player == null) {
            return true;
        }

        try {
            return player.isCreative() || player.isSpectator();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void absorbIgnoredDeltas(UUID uuid, Map<String, Integer> rawStats) {
        if (uuid == null || rawStats == null) {
            return;
        }

        Map<String, Integer> previous = lastRawStats.getOrDefault(uuid, Collections.emptyMap());
        Map<String, Integer> offsets = ignoredStatOffsets.computeIfAbsent(uuid, ignored -> new LinkedHashMap<>());

        for (Map.Entry<String, Integer> entry : rawStats.entrySet()) {
            String key = entry.getKey();
            int raw = Math.max(0, entry.getValue());
            int old = Math.max(0, previous.getOrDefault(key, raw));
            int delta = Math.max(0, raw - old);

            if (delta > 0) {
                offsets.put(key, offsets.getOrDefault(key, 0) + delta);
            }
        }

        lastRawStats.put(uuid, new LinkedHashMap<>(rawStats));
    }

    private static Map<String, Integer> applyIgnoredOffsets(UUID uuid, Map<String, Integer> rawStats) {
        Map<String, Integer> adjusted = new LinkedHashMap<>();
        Map<String, Integer> offsets = ignoredStatOffsets.getOrDefault(uuid, Collections.emptyMap());

        for (Map.Entry<String, Integer> entry : rawStats.entrySet()) {
            String key = entry.getKey();
            int raw = Math.max(0, entry.getValue());
            int offset = Math.max(0, offsets.getOrDefault(key, 0));
            adjusted.put(key, Math.max(0, raw - offset));
        }

        return adjusted;
    }

    private static Map<String, Integer> collectPlayerStats(ServerPlayerEntity player) {
        StatHandler stats = player.getStatHandler();
        Map<String, Integer> values = new LinkedHashMap<>();
        int blocksBroken = collectBlockStats(stats, Stats.MINED, "block_broken", values);
        int blocksPlaced = collectBlockItems(stats, values);
        int itemsUsed = collectUsedItemStats(stats, values);
        int itemsCrafted = collectItemStats(stats, Stats.CRAFTED, "item_crafted", values);
        int itemsPickedUp = collectItemStats(stats, Stats.PICKED_UP, "item_picked_up", values);
        int itemsDropped = collectItemStats(stats, Stats.DROPPED, "item_dropped", values);
        int foodEaten = collectFoodStats(stats, values);
        int mobsKilled = collectEntityStats(stats, Stats.KILLED, "mob_killed", values);
        int entityDeaths = collectEntityStats(stats, Stats.KILLED_BY, "killed_by", values);

        values.put("blocks_broken", blocksBroken);
        values.put("blocks_placed", blocksPlaced);
        values.put("items_used", itemsUsed);
        values.put("items_crafted", itemsCrafted);
        values.put("items_picked_up", itemsPickedUp);
        values.put("items_dropped", itemsDropped);
        values.put("food_eaten", foodEaten);
        values.put("mobs_killed", mobsKilled);
        values.put("entity_deaths", entityDeaths);
        int deaths = raw(stats, new Identifier("minecraft", "deaths"));
        int damageDealt = raw(stats, new Identifier("minecraft", "damage_dealt"));
        int damageTaken = raw(stats, new Identifier("minecraft", "damage_taken"));
        int playTime = raw(stats, new Identifier("minecraft", "play_time"));
        int travelDistance = travelDistance(stats);
        int jumps = raw(stats, new Identifier("minecraft", "jump"));
        values.put("deaths", deaths);
        values.put("damage_dealt", damageDealt);
        values.put("damage_taken", damageTaken);
        values.put("play_time", playTime);
        values.put("travel_distance", travelDistance);
        values.put("jumps", jumps);
        values.put("combat_activity", mobsKilled + entityDeaths + deaths + damageDealt + damageTaken);
        return values;
    }

    private static int travelDistance(StatHandler stats) {
        return raw(stats, new Identifier("minecraft", "walk_one_cm"))
                + raw(stats, new Identifier("minecraft", "crouch_one_cm"))
                + raw(stats, new Identifier("minecraft", "sprint_one_cm"))
                + raw(stats, new Identifier("minecraft", "swim_one_cm"))
                + raw(stats, new Identifier("minecraft", "fall_one_cm"))
                + raw(stats, new Identifier("minecraft", "climb_one_cm"))
                + raw(stats, new Identifier("minecraft", "fly_one_cm"))
                + raw(stats, new Identifier("minecraft", "walk_on_water_one_cm"))
                + raw(stats, new Identifier("minecraft", "walk_under_water_one_cm"))
                + raw(stats, new Identifier("minecraft", "minecart_one_cm"))
                + raw(stats, new Identifier("minecraft", "boat_one_cm"))
                + raw(stats, new Identifier("minecraft", "pig_one_cm"))
                + raw(stats, new Identifier("minecraft", "horse_one_cm"))
                + raw(stats, new Identifier("minecraft", "aviate_one_cm"))
                + raw(stats, new Identifier("minecraft", "strider_one_cm"));
    }

    private static int raw(StatHandler stats, Identifier id) {
        try {
            Stat<Identifier> stat = Stats.CUSTOM.getOrCreateStat(id);
            return Math.max(0, stats.getStat(stat));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int collectBlockStats(StatHandler stats, StatType<Block> statType, String prefix, Map<String, Integer> values) {
        int total = 0;

        for (Block block : Registries.BLOCK) {
            try {
                int value = Math.max(0, stats.getStat(statType.getOrCreateStat(block)));

                if (value > 0) {
                    Identifier id = Registries.BLOCK.getId(block);
                    values.put(prefix + "/" + id.getNamespace() + "/" + id.getPath(), value);
                    total += value;
                }
            } catch (Exception ignored) {
            }
        }

        return total;
    }

    private static int collectItemStats(StatHandler stats, StatType<Item> statType, String prefix, Map<String, Integer> values) {
        int total = 0;

        for (Item item : Registries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }

            try {
                int value = Math.max(0, stats.getStat(statType.getOrCreateStat(item)));

                if (value > 0) {
                    Identifier id = Registries.ITEM.getId(item);
                    values.put(prefix + "/" + id.getNamespace() + "/" + id.getPath(), value);
                    total += value;
                }
            } catch (Exception ignored) {
            }
        }

        return total;
    }

    private static int collectUsedItemStats(StatHandler stats, Map<String, Integer> values) {
        int total = 0;

        for (Item item : Registries.ITEM) {
            if (item == Items.AIR || item instanceof BlockItem || item.getFoodComponent() != null) {
                continue;
            }

            try {
                int value = Math.max(0, stats.getStat(Stats.USED.getOrCreateStat(item)));

                if (value > 0) {
                    Identifier id = Registries.ITEM.getId(item);
                    values.put("item_used/" + id.getNamespace() + "/" + id.getPath(), value);
                    total += value;
                }
            } catch (Exception ignored) {
            }
        }

        return total;
    }

    private static int collectEntityStats(StatHandler stats, StatType<EntityType<?>> statType, String prefix, Map<String, Integer> values) {
        int total = 0;

        for (EntityType<?> entityType : Registries.ENTITY_TYPE) {
            try {
                int value = Math.max(0, stats.getStat(statType.getOrCreateStat(entityType)));

                if (value > 0) {
                    Identifier id = Registries.ENTITY_TYPE.getId(entityType);
                    values.put(prefix + "/" + id.getNamespace() + "/" + id.getPath(), value);
                    total += value;
                }
            } catch (Exception ignored) {
            }
        }

        return total;
    }

    private static int collectBlockItems(StatHandler stats, Map<String, Integer> values) {
        int total = 0;

        for (Item item : Registries.ITEM) {
            if (!(item instanceof BlockItem blockItem)) {
                continue;
            }

            try {
                int value = Math.max(0, stats.getStat(Stats.USED.getOrCreateStat(item)));

                if (value > 0) {
                    Identifier id = Registries.BLOCK.getId(blockItem.getBlock());
                    values.put("block_placed/" + id.getNamespace() + "/" + id.getPath(), value);
                    total += value;
                }
            } catch (Exception ignored) {
            }
        }

        return total;
    }

    private static int collectFoodStats(StatHandler stats, Map<String, Integer> values) {
        int total = 0;

        for (Item item : Registries.ITEM) {
            try {
                if (item.getFoodComponent() == null) {
                    continue;
                }

                int value = Math.max(0, stats.getStat(Stats.USED.getOrCreateStat(item)));

                if (value > 0) {
                    Identifier id = Registries.ITEM.getId(item);
                    values.put("food_eaten/" + id.getNamespace() + "/" + id.getPath(), value);
                    total += value;
                }
            } catch (Exception ignored) {
            }
        }

        return total;
    }

    private static void syncAll(MinecraftServer server, KoilGlobalActivityState state) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            sync(player, state);
        }
    }

    private static void sync(ServerPlayerEntity player, KoilGlobalActivityState state) {
        if (!ServerPlayNetworking.canSend(player, GlobalActivityApi.CHANNEL)) {
            return;
        }

        ServerPlayNetworking.send(player, GlobalActivityApi.CHANNEL, writeSnapshot(state));
    }

    private static PacketByteBuf writeSnapshot(KoilGlobalActivityState state) {
        List<KoilGlobalActivityRow> rows = state.snapshotRows(MARKET_SYNC_ROW_LIMIT);
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(SYNC_MAGIC);
        buf.writeVarInt(SYNC_PROTOCOL);
        buf.writeLong(state.snapshotId());
        buf.writeLong(Math.max(0L, state.lastSnapshotAt()));
        buf.writeVarInt(Math.min(MARKET_SYNC_ROW_LIMIT, rows.size()));

        for (KoilGlobalActivityRow row : rows.subList(0, Math.min(MARKET_SYNC_ROW_LIMIT, rows.size()))) {
            buf.writeString(row.type(), 32);
            buf.writeString(row.id(), 192);
            buf.writeString(row.metric(), 192);
            buf.writeString(row.title(), 96);
            buf.writeVarInt(Math.max(0, row.value()));
            buf.writeString(row.leader(), 64);
            buf.writeVarInt(Math.max(0, row.leaderValue()));
            int[] points = row.points();
            int pointCount = Math.min(KoilMarketSeriesWindow.historySampleLimit(), points.length);
            buf.writeVarInt(pointCount);

            for (int pointIndex = Math.max(0, points.length - pointCount); pointIndex < points.length; pointIndex++) {
                int point = points[pointIndex];
                buf.writeVarInt(Math.max(0, point));
            }

            buf.writeString(row.source(), 96);
            buf.writeVarInt(Math.max(0, row.base()));
            buf.writeInt(row.change());
            buf.writeVarInt(Math.max(0, row.volume()));
        }

        return buf;
    }
}
