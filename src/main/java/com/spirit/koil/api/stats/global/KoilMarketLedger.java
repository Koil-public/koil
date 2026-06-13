package com.spirit.koil.api.stats.global;

import java.util.*;

public final class KoilMarketLedger {
    public static final String PRODUCTION = "production";
    public static final String DEMAND = "demand";
    public static final String CONSUMPTION = "consumption";
    public static final String TRANSFER = "transfer";
    public static final String LOSS = "loss";
    public static final String TRADE = "trade";
    public static final String PROCESSING = "processing";
    public static final String COMBAT = "combat";
    public static final String ADMIN = "admin";
    public static final String CREATIVE = "creative";
    public static final String ESTIMATED = "estimated";
    public static final String UNKNOWN = "unknown";

    private KoilMarketLedger() {
    }

    public static String categoryForMetric(String metric) {
        String key = clean(metric);

        if (key.startsWith("admin/") || key.contains("command_created") || key.contains("op_adjustment") || key.contains("command_give") || key.contains("admin_adjustment")) {
            return ADMIN;
        }

        if (key.startsWith("creative/") || key.contains("creative")) {
            return CREATIVE;
        }

        if (key.contains("container_in/")) {
            return PRODUCTION;
        }

        if (key.contains("container_out/")) {
            return DEMAND;
        }

        if (key.contains("momens_exchange") || key.contains("momens_redeem") || key.contains("death_drop") || key.contains("pickup_after_death") || key.contains("inventory_transfer") || key.contains("container_click") || key.contains("container_transfer") || key.contains("storage_interact") || key.contains("manual_drop") || key.contains("player_pickup") || key.contains("item_picked_up") || key.contains("item_dropped") || key.equals("items_picked_up") || key.equals("items_dropped")) {
            return TRANSFER;
        }

        if (key.contains("void") || key.contains("lava_destroyed") || key.contains("despawned") || key.contains("lost") || key.contains("removed") || key.contains("depleted") || key.contains("destroyed")) {
            return LOSS;
        }

        if (key.contains("processing/station_interact") || key.contains("station/block_interact") || key.contains("station/storage_interact") || key.contains("station/crafting_station_interact") || key.contains("station/utility_station_interact") || key.contains("station/modded_machine_interact") || key.contains("station/brewing_interact") || key.contains("station/smelting_interact") || key.contains("_interact/")) {
            return TRANSFER;
        }

        if (key.contains("station/station_output_taken") || key.contains("station/station_output_created") || key.contains("station/brew_output") || key.contains("station/craft_output") || key.contains("station/smith_output") || key.contains("station/stonecut_output")) {
            return PRODUCTION;
        }

        if (key.contains("station/station_input_consumed") || key.contains("station/station_material_inserted") || key.contains("station/brew_ingredient") || key.contains("station/brew_fuel") || key.contains("station/craft_input") || key.contains("station/smith_input") || key.contains("station/stonecut_input")) {
            return CONSUMPTION;
        }

        if (key.contains("processing/fuel_burned") || key.contains("fuel_burned") || key.contains("processing/smelt_input") || key.contains("smelt_input") || key.contains("processing/compost_input") || key.contains("compost_input") || key.contains("brew_ingredient") || key.contains("brew_fuel")) {
            return CONSUMPTION;
        }

        if (key.contains("processing/smelt_output") || key.contains("smelt_output") || key.contains("processing/compost_output") || key.contains("compost_output") || key.contains("brew_output") || key.contains("station_output_taken") || key.contains("station_output_created")) {
            return PRODUCTION;
        }

        if (key.contains("processing/fuel_supported_output") || key.contains("fuel_supported_output") || key.contains("processing/")) {
            return PROCESSING;
        }

        if (key.contains("trade") || key.contains("sold") || key.contains("bought") || key.contains("shop")) {
            return TRADE;
        }

        if (key.contains("killed_by") || key.contains("entity_deaths") || key.contains("death") || key.contains("damage_taken")) {
            return LOSS;
        }

        if (key.contains("mob_killed") || key.contains("mobs_killed") || key.contains("damage_dealt") || key.contains("combat")) {
            return COMBAT;
        }

        if (key.contains("block_broken") || key.contains("blocks_broken") || key.contains("mined") || key.contains("crafted") || key.contains("harvest") || key.contains("produced") || key.contains("resource/")) {
            return PRODUCTION;
        }

        if (key.contains("food_eaten") || key.contains("food_use") || key.contains("item_used") || key.contains("items_used") || key.contains("block_placed") || key.contains("block_place") || key.contains("used") || key.contains("placed") || key.contains("consumed")) {
            return DEMAND;
        }

        if (key.startsWith("packet/") || key.contains("intent")) {
            return ESTIMATED;
        }

        return UNKNOWN;
    }

    public static boolean shouldAffectPrice(String category) {
        String key = clean(category);
        return !(ADMIN.equals(key) || CREATIVE.equals(key) || TRANSFER.equals(key));
    }

    public static double priceWeight(String category, boolean authoritative) {
        String key = clean(category);

        if (ADMIN.equals(key) || CREATIVE.equals(key)) {
            return 0.0D;
        }

        if (TRANSFER.equals(key)) {
            return 0.0D;
        }

        if (ESTIMATED.equals(key)) {
            return authoritative ? 0.42D : 0.26D;
        }

        if (UNKNOWN.equals(key)) {
            return authoritative ? 0.22D : 0.1D;
        }

        if (TRADE.equals(key)) {
            return 1.12D;
        }

        if (PROCESSING.equals(key)) {
            return 0.86D;
        }

        if (CONSUMPTION.equals(key) || DEMAND.equals(key)) {
            return 1.0D;
        }

        if (PRODUCTION.equals(key)) {
            return 0.96D;
        }

        if (LOSS.equals(key)) {
            return 0.9D;
        }

        if (COMBAT.equals(key)) {
            return 0.72D;
        }

        return authoritative ? 0.35D : 0.18D;
    }

    public static boolean isSupply(String category, String metric) {
        String key = clean(category);
        String name = clean(metric);
        return PRODUCTION.equals(key) || name.contains("container_in/") || name.contains("smelt_output") || name.contains("compost_output") || name.contains("brew_output") || name.contains("station_output_taken") || name.contains("station_output_created") || name.contains("craft_output") || name.contains("smith_output") || name.contains("stonecut_output") || name.contains("crafted") || name.contains("mined") || name.contains("harvest") || name.contains("produced") || name.contains("block_broken") || name.contains("blocks_broken");
    }

    public static boolean isDemand(String category, String metric) {
        String key = clean(category);
        String name = clean(metric);
        return DEMAND.equals(key) || CONSUMPTION.equals(key) || TRADE.equals(key) || name.contains("container_out/") || name.contains("fuel_burned") || name.contains("eaten") || name.contains("placed") || name.contains("used") || name.contains("smelt_input") || name.contains("compost_input") || name.contains("brew_ingredient") || name.contains("brew_fuel") || name.contains("station_input_consumed") || name.contains("craft_input") || name.contains("smith_input") || name.contains("stonecut_input") || name.contains("fuel_supported");
    }

    public static boolean isNegative(String category, String metric) {
        String key = clean(category);
        String name = clean(metric);
        return LOSS.equals(key) || name.contains("killed_by") || name.contains("death") || name.contains("damage_taken") || name.contains("lost") || name.contains("despawn") || name.contains("void") || name.contains("depleted") || name.contains("destroyed");
    }

    public static String label(String category) {
        String key = clean(category);

        if (PRODUCTION.equals(key)) {
            return "supply production";
        }

        if (DEMAND.equals(key)) {
            return "demand";
        }

        if (CONSUMPTION.equals(key)) {
            return "consumption";
        }

        if (TRANSFER.equals(key)) {
            return "transfer only";
        }

        if (LOSS.equals(key)) {
            return "loss";
        }

        if (TRADE.equals(key)) {
            return "trade";
        }

        if (PROCESSING.equals(key)) {
            return "processing";
        }

        if (COMBAT.equals(key)) {
            return "combat";
        }

        if (ADMIN.equals(key)) {
            return "admin ignored";
        }

        if (CREATIVE.equals(key)) {
            return "creative ignored";
        }

        if (ESTIMATED.equals(key)) {
            return "estimated signal";
        }

        return "developer/unknown";
    }

    private static String clean(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
