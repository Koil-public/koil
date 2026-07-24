package com.spirit.koil.api.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.registry.definition.ContentDefinition;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.block.enums.Instrument;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.UseAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Version-local reader for physical settings that Minecraft fixes when a holder is registered. */
final class DefinitionValueReader {
    private DefinitionValueReader() {
    }

    static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object == null ? null : object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return fallback;
        }
        try {
            String result = value.getAsString();
            return result == null || result.isBlank() ? fallback : result;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object == null ? null : object.get(key);
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static float decimal(JsonObject object, String key, float fallback) {
        JsonElement value = object == null ? null : object.get(key);
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsFloat() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object == null ? null : object.get(key);
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsBoolean() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static String displayName(WorldContentIndex.DefinitionEntry definition) {
        return string(object(definition.definition(), "display"), "name", definition.id());
    }

    static String languageKey(WorldContentIndex.DefinitionEntry definition, boolean block) {
        String fallback = (block ? "block." : "item.") + definition.namespace() + "." + definition.localId();
        return string(object(definition.definition(), "display"), "lang_key", fallback);
    }

    static List<String> lore(WorldContentIndex.DefinitionEntry definition) {
        JsonElement value = object(definition.definition(), "display").get("lore");
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (JsonElement line : value.getAsJsonArray()) {
            if (line.isJsonPrimitive()) {
                lines.add(line.getAsString());
            }
        }
        return List.copyOf(lines);
    }

    static String behaviorProfile(WorldContentIndex.DefinitionEntry definition) {
        return string(object(definition.definition(), "behavior"), "profile", "normal").toLowerCase(Locale.ROOT);
    }

    static String behaviorProfile(ContentDefinition definition) {
        return behaviorProfile(definition.source());
    }

    static int enchantability(WorldContentIndex.DefinitionEntry definition) {
        return Math.max(0, integer(object(definition.definition(), "properties"), "enchantability", 0));
    }

    static int enchantability(ContentDefinition definition) {
        return enchantability(definition.source());
    }

    static String repairIngredient(WorldContentIndex.DefinitionEntry definition) {
        return string(object(definition.definition(), "properties"), "repair_ingredient", "minecraft:air");
    }

    static String repairIngredient(ContentDefinition definition) {
        return repairIngredient(definition.source());
    }

    static UseAction useAction(WorldContentIndex.DefinitionEntry definition) {
        String value = string(object(definition.definition(), "behavior"), "use_action", "none");
        try {
            return UseAction.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UseAction.NONE;
        }
    }

    static Rarity rarity(WorldContentIndex.DefinitionEntry definition) {
        String value = string(object(definition.definition(), "properties"), "rarity", "common");
        try {
            return Rarity.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Rarity.COMMON;
        }
    }

    static Item.Settings itemSettings(WorldContentIndex.DefinitionEntry definition) {
        JsonObject properties = object(definition.definition(), "properties");
        Item.Settings settings = new Item.Settings();
        int durability = Math.max(0, integer(properties, "durability", 0));
        if (durability > 0) {
            settings.maxDamage(durability);
        } else {
            settings.maxCount(Math.max(1, Math.min(64, integer(properties, "max_count", 64))));
        }
        settings.rarity(rarity(definition));
        if (bool(properties, "fireproof", false)) {
            settings.fireproof();
        }
        Identifier remainderId = Identifier.tryParse(string(properties, "recipe_remainder", "minecraft:air"));
        if (remainderId != null && Registries.ITEM.containsId(remainderId)) {
            Item remainder = Registries.ITEM.get(remainderId);
            if (remainder != Items.AIR) {
                settings.recipeRemainder(remainder);
            }
        }
        return settings;
    }

    static Item.Settings itemSettings(ContentDefinition definition) {
        return itemSettings(definition.source());
    }

    static AbstractBlock.Settings blockSettings(WorldContentIndex.DefinitionEntry definition) {
        JsonObject properties = object(definition.definition(), "properties");
        Identifier copyId = Identifier.tryParse(string(properties, "copy_from", "minecraft:stone"));
        Block copy = copyId != null && Registries.BLOCK.containsId(copyId) ? Registries.BLOCK.get(copyId) : Blocks.STONE;
        AbstractBlock.Settings settings = AbstractBlock.Settings.copy(copy);

        settings.strength(
                Math.max(0.0F, decimal(properties, "hardness", copy.getHardness())),
                Math.max(0.0F, decimal(properties, "resistance", copy.getBlastResistance()))
        );
        settings.slipperiness(decimal(properties, "slipperiness", copy.getSlipperiness()));
        settings.velocityMultiplier(decimal(properties, "velocity_multiplier", copy.getVelocityMultiplier()));
        settings.jumpVelocityMultiplier(decimal(properties, "jump_velocity_multiplier", copy.getJumpVelocityMultiplier()));
        int luminance = Math.max(0, Math.min(15, integer(properties, "luminance", 0)));
        settings.luminance(state -> luminance);
        settings.mapColor(mapColor(string(properties, "map_color", "stone_gray")));
        settings.sounds(soundGroup(string(properties, "sound_group", "stone")));
        settings.instrument(instrument(string(properties, "instrument", "harp")));
        settings.pistonBehavior(pistonBehavior(string(properties, "piston_behavior", "normal")));

        if (!bool(properties, "collision", true)) {
            settings.noCollision();
        }
        if (!bool(properties, "opaque", true)) {
            settings.nonOpaque();
        }
        if (bool(properties, "solid", true)) {
            settings.solid();
        } else {
            settings.notSolid();
        }
        if (bool(properties, "requires_tool", false)) {
            settings.requiresTool();
        }
        if (bool(properties, "burnable", false)) {
            settings.burnable();
        }
        if (bool(properties, "replaceable", false)) {
            settings.replaceable();
        }
        if (bool(object(definition.definition(), "behavior"), "random_ticks", false)) {
            settings.ticksRandomly();
        }
        return settings;
    }

    static AbstractBlock.Settings blockSettings(
            ContentDefinition definition,
            DynamicBlockStateSchema schema
    ) {
        WorldContentIndex.DefinitionEntry source = definition.source();
        JsonObject properties = object(source.definition(), "properties");
        AbstractBlock.Settings settings = blockSettings(source);
        int bootstrapLuminance = Math.max(0, Math.min(15, integer(properties, "luminance", 0)));
        settings.luminance(state -> DynamicContentBlockBehavior.luminance(
                definition.id(),
                schema,
                state,
                bootstrapLuminance
        ));
        return settings;
    }

    static String creativeTab(WorldContentIndex.DefinitionEntry definition) {
        return string(object(definition.definition(), "creative"), "tab", "");
    }

    static int creativeOrder(WorldContentIndex.DefinitionEntry definition) {
        return integer(object(definition.definition(), "creative"), "order", 0);
    }

    static boolean creativeHidden(WorldContentIndex.DefinitionEntry definition) {
        return bool(object(definition.definition(), "creative"), "hidden", false);
    }

    private static MapColor mapColor(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "red" -> MapColor.RED;
            case "bright_red" -> MapColor.BRIGHT_RED;
            case "diamond", "diamond_blue" -> MapColor.DIAMOND_BLUE;
            case "gold" -> MapColor.GOLD;
            case "emerald", "emerald_green" -> MapColor.EMERALD_GREEN;
            case "black" -> MapColor.BLACK;
            case "white" -> MapColor.WHITE;
            case "blue" -> MapColor.BLUE;
            case "green" -> MapColor.GREEN;
            case "purple" -> MapColor.PURPLE;
            default -> MapColor.STONE_GRAY;
        };
    }

    private static BlockSoundGroup soundGroup(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "amethyst", "amethyst_block" -> BlockSoundGroup.AMETHYST_BLOCK;
            case "metal" -> BlockSoundGroup.METAL;
            case "glass" -> BlockSoundGroup.GLASS;
            case "wood" -> BlockSoundGroup.WOOD;
            case "wool" -> BlockSoundGroup.WOOL;
            case "netherite" -> BlockSoundGroup.NETHERITE;
            default -> BlockSoundGroup.STONE;
        };
    }

    private static Instrument instrument(String value) {
        try {
            return Instrument.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Instrument.HARP;
        }
    }

    private static PistonBehavior pistonBehavior(String value) {
        try {
            return PistonBehavior.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PistonBehavior.NORMAL;
        }
    }
}
