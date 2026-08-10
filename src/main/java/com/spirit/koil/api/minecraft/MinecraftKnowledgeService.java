package com.spirit.koil.api.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.f3.F3DataLine;
import com.spirit.koil.api.f3.F3Mode;
import com.spirit.koil.api.f3.F3TargetInspector;
import com.spirit.koil.api.f3.F3TargetSnapshot;
import net.minecraft.advancement.Advancement;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Bounded, read-only access to data already synchronized to the active
 * Minecraft client. The service never queries server internals or mutates the
 * world, and every snapshot is captured on the client thread.
 */
public final class MinecraftKnowledgeService {
    private static final int MAXIMUM_RESULTS = 32;
    private static final int MAXIMUM_ALTERNATIVES = 12;

    private MinecraftKnowledgeService() {
    }

    public static CompletableFuture<Result> query(String query, String value, String registry, int requestedLimit) {
        return query(query, value, registry, requestedLimit, List.of());
    }

    public static CompletableFuture<Result> query(
            String query,
            String value,
            String registry,
            int requestedLimit,
            List<String> requestedFields
    ) {
        CompletableFuture<Result> result = new CompletableFuture<>();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            result.complete(new Result(false, new JsonObject(), "Minecraft client is unavailable."));
            return result;
        }
        String kind = normalize(query);
        String needle = value == null ? "" : value.strip();
        String registryKind = normalize(registry);
        int limit = Math.max(1, Math.min(MAXIMUM_RESULTS, requestedLimit));
        List<String> fields = normalizeFields(requestedFields);
        client.execute(() -> {
            try {
                result.complete(switch (kind) {
                    case "catalog", "capabilities" -> catalog(client);
                    case "player" -> player(client, limit, fields);
                    case "target" -> target(client, fields);
                    case "registry" -> registry(client, registryKind, needle, limit);
                    case "recipe", "recipes" -> recipes(client, needle, limit);
                    case "advancement", "advancements" -> advancements(client, needle, limit);
                    case "block", "block_info" -> blockInfo(needle);
                    case "item", "item_info" -> itemInfo(needle);
                    case "entity", "entity_info", "creature" -> entityInfo(needle);
                    case "effect", "status_effect", "effect_info" -> effectInfo(needle);
                    case "enchantment", "enchantment_info" -> enchantmentInfo(needle);
                    case "biome", "biomes" -> dynamicRegistry(client, "biome", RegistryKeys.BIOME, needle, limit);
                    case "dimension", "dimensions" -> dynamicRegistry(
                            client,
                            "dimension_type",
                            RegistryKeys.DIMENSION_TYPE,
                            needle,
                            limit
                    );
                    case "structure", "structures" -> dynamicRegistry(
                            client,
                            "structure",
                            RegistryKeys.STRUCTURE,
                            needle,
                            limit
                    );
                    case "nbt", "snbt", "item_nbt" -> nbt(needle, limit);
                    default -> new Result(
                            false,
                            new JsonObject(),
                            "Unknown knowledge query. Use catalog, player, target, registry, item, block, entity, effect, enchantment, biome, dimension, recipe, advancement, structure, command, or nbt."
                    );
                });
            } catch (RuntimeException failure) {
                result.complete(new Result(
                        false,
                        new JsonObject(),
                        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()
                ));
            }
        });
        return result;
    }

    private static Result catalog(MinecraftClient client) {
        JsonObject output = new JsonObject();
        output.addProperty("scope", "authoritative client-visible and synchronized data");
        output.addProperty("serverInternalsIncluded", false);
        output.addProperty("bounded", true);
        JsonArray categories = new JsonArray();
        addCategory(
                categories,
                "player",
                client.player != null && client.world != null,
                "active client player and world snapshot",
                List.of(
                        "name", "uuid", "entityId", "dimension", "biome", "position",
                        "facing", "yaw", "pitch", "gameMode", "health", "maximumHealth",
                        "food", "saturation", "armor", "experienceLevel", "onGround",
                        "sprinting", "sneaking", "swimming", "flying", "mainHand",
                        "offHand", "standingOn", "effects", "inventory", "lookingAt", "travelOptions"
                )
        );
        addCategory(
                categories,
                "target",
                client.player != null && client.world != null,
                "shared F3 developer target inspector",
                List.of(
                        "type", "title", "description", "registryId", "modOwner",
                        "position", "danger", "details", "tags"
                )
        );
        addCategory(
                categories,
                "registry",
                true,
                "static registries plus synchronized dynamic registries",
                List.of(
                        "item", "block", "entity_type", "status_effect", "enchantment",
                        "sound_event", "biome", "structure"
                )
        );
        addCategory(
                categories,
                "item",
                true,
                "active static item registry and default stack",
                List.of("id", "name", "translationKey", "maximumCount", "maximumDamage", "damageable", "fireproof", "rarity", "enchantable", "enchantability", "food")
        );
        addCategory(
                categories,
                "block",
                true,
                "active static block registry and default block state",
                List.of("id", "name", "item", "translationKey", "blastResistance", "luminance", "air", "properties", "defaultState")
        );
        addCategory(
                categories,
                "entity",
                true,
                "active static entity-type registry",
                List.of("id", "name", "translationKey", "spawnGroup", "width", "height", "summonable", "fireImmune", "saveable")
        );
        addCategory(
                categories,
                "effect",
                true,
                "active static status-effect registry",
                List.of("id", "name", "translationKey", "category", "color", "beneficial")
        );
        addCategory(
                categories,
                "enchantment",
                true,
                "active static enchantment registry",
                List.of("id", "name", "translationKey", "rarity", "target", "minimumLevel", "maximumLevel", "treasure", "cursed")
        );
        addCategory(
                categories,
                "dimension",
                client.getNetworkHandler() != null,
                "active synchronized dimension-type registry",
                List.of("identifier")
        );
        addCategory(
                categories,
                "recipe",
                client.getNetworkHandler() != null && client.world != null,
                "active synchronized recipe manager",
                List.of(
                        "id", "type", "serializer", "output", "outputName", "outputCount",
                        "ingredients", "ingredientSlotCount", "exactIngredientTotals",
                        "exactIngredientTotalsComplete"
                )
        );
        addCategory(
                categories,
                "advancement",
                client.getNetworkHandler() != null,
                "active synchronized advancement manager",
                List.of(
                        "id", "title", "description", "hidden", "frame",
                        "criteriaCount", "requirementGroups"
                )
        );
        addCategory(
                categories,
                "structure",
                client.getNetworkHandler() != null,
                "active synchronized structure registry",
                List.of("identifier")
        );
        addCategory(
                categories,
                "nbt",
                true,
                "Koil version-local item SNBT grammar and active registry identifiers",
                List.of("format", "guidance", "templates")
        );
        output.add("categories", categories);
        return success(
                output,
                "Minecraft knowledge capabilities and their current availability were listed."
        );
    }

    private static void addCategory(
            JsonArray categories,
            String id,
            boolean available,
            String source,
            List<String> fields
    ) {
        JsonObject category = new JsonObject();
        category.addProperty("id", id);
        category.addProperty("available", available);
        category.addProperty("source", source);
        JsonArray encodedFields = new JsonArray();
        fields.forEach(encodedFields::add);
        category.add("fields", encodedFields);
        categories.add(category);
    }

    private static Result player(MinecraftClient client, int limit, List<String> fields) {
        if (client.player == null || client.world == null) {
            return unavailable("A loaded player and world are required.");
        }
        JsonObject output = new JsonObject();
        BlockPos position = client.player.getBlockPos();
        output.addProperty("name", client.player.getName().getString());
        output.addProperty("uuid", client.player.getUuidAsString());
        output.addProperty("entityId", client.player.getId());
        output.addProperty("dimension", client.world.getRegistryKey().getValue().toString());
        output.addProperty(
                "biome",
                client.world.getBiome(position).getKey()
                        .map(key -> key.getValue().toString())
                        .orElse("unknown")
        );
        JsonObject coordinates = new JsonObject();
        coordinates.addProperty("x", client.player.getX());
        coordinates.addProperty("y", client.player.getY());
        coordinates.addProperty("z", client.player.getZ());
        coordinates.addProperty("blockX", position.getX());
        coordinates.addProperty("blockY", position.getY());
        coordinates.addProperty("blockZ", position.getZ());
        output.add("position", coordinates);
        output.addProperty("facing", client.player.getHorizontalFacing().asString());
        output.addProperty("yaw", client.player.getYaw());
        output.addProperty("pitch", client.player.getPitch());
        output.addProperty(
                "gameMode",
                client.interactionManager == null
                        ? "unknown"
                        : client.interactionManager.getCurrentGameMode().getName()
        );
        output.addProperty("health", client.player.getHealth());
        output.addProperty("maximumHealth", client.player.getMaxHealth());
        output.addProperty("food", client.player.getHungerManager().getFoodLevel());
        output.addProperty("saturation", client.player.getHungerManager().getSaturationLevel());
        output.addProperty("armor", client.player.getArmor());
        output.addProperty("experienceLevel", client.player.experienceLevel);
        output.addProperty("onGround", client.player.isOnGround());
        output.addProperty("sprinting", client.player.isSprinting());
        output.addProperty("sneaking", client.player.isSneaking());
        output.addProperty("swimming", client.player.isSwimming());
        output.addProperty("flying", client.player.getAbilities().flying);
        output.addProperty("mainHand", stackId(client.player.getMainHandStack()));
        output.addProperty("offHand", stackId(client.player.getOffHandStack()));
        BlockState standingOn = client.world.getBlockState(position.down());
        output.addProperty("standingOn", Registries.BLOCK.getId(standingOn.getBlock()).toString());

        JsonArray effects = new JsonArray();
        for (StatusEffectInstance effect : client.player.getStatusEffects()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("id", Registries.STATUS_EFFECT.getId(effect.getEffectType()).toString());
            encoded.addProperty("amplifier", effect.getAmplifier());
            encoded.addProperty("durationTicks", effect.getDuration());
            effects.add(encoded);
        }
        output.add("effects", effects);

        JsonArray inventory = new JsonArray();
        for (int slot = 0; slot < client.player.getInventory().size() && inventory.size() < limit; slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject encoded = new JsonObject();
            encoded.addProperty("slot", slot);
            encoded.addProperty("id", stackId(stack));
            encoded.addProperty("name", stack.getName().getString());
            encoded.addProperty("count", stack.getCount());
            encoded.addProperty("damage", stack.getDamage());
            encoded.addProperty("maximumDamage", stack.getMaxDamage());
            inventory.add(encoded);
        }
        output.add("inventory", inventory);
        JsonObject travel = new JsonObject();
        travel.addProperty("riding", client.player.hasVehicle());
        travel.addProperty("vehicle", client.player.getVehicle() == null
                ? ""
                : Registries.ENTITY_TYPE.getId(client.player.getVehicle().getType()).toString());
        travel.addProperty("fallFlying", client.player.isFallFlying());
        travel.addProperty("inWater", client.player.isSubmergedInWater());
        String chestItem = stackId(client.player.getEquippedStack(EquipmentSlot.CHEST));
        travel.addProperty("chestItem", chestItem);
        travel.addProperty("elytraEquipped", "minecraft:elytra".equals(chestItem));
        int fireworks = 0;
        int boats = 0;
        int saddles = 0;
        for (int slot = 0; slot < client.player.getInventory().size(); slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            String id = stackId(stack);
            if ("minecraft:firework_rocket".equals(id)) fireworks += stack.getCount();
            if (id.endsWith("_boat") || id.endsWith("_raft")) boats += stack.getCount();
            if ("minecraft:saddle".equals(id)) saddles += stack.getCount();
        }
        travel.addProperty("fireworkRockets", fireworks);
        travel.addProperty("boatItems", boats);
        travel.addProperty("saddles", saddles);
        JsonArray availableModes = new JsonArray();
        availableModes.add("walk");
        availableModes.add("sprint");
        if (client.player.isSubmergedInWater() || client.player.isSwimming()) availableModes.add("swim");
        if (client.player.hasVehicle()) availableModes.add("mounted");
        if ("minecraft:elytra".equals(chestItem)) availableModes.add("elytra");
        if (boats > 0) availableModes.add("boat_available");
        travel.add("availableModes", availableModes);
        output.add("travelOptions", travel);
        output.add("lookingAt", targetJson(F3TargetInspector.inspect(client, F3Mode.DEVELOPER)));
        JsonObject selected = selectFields(output, fields);
        return success(
                selected,
                fields.isEmpty()
                        ? "Current player, world, inventory, effects, footing, and target data were captured."
                        : "Only the requested current-player fields were captured: " + String.join(", ", fields) + "."
        );
    }

    private static Result target(MinecraftClient client, List<String> fields) {
        if (client.player == null || client.world == null) {
            return unavailable("A loaded player and world are required.");
        }
        return success(
                selectFields(targetJson(F3TargetInspector.inspect(client, F3Mode.DEVELOPER)), fields),
                fields.isEmpty()
                        ? "The current crosshair target was inspected."
                        : "Only the requested crosshair-target fields were captured: "
                        + String.join(", ", fields) + "."
        );
    }

    private static JsonObject targetJson(F3TargetSnapshot target) {
        JsonObject output = new JsonObject();
        output.addProperty("type", target.type().name().toLowerCase(Locale.ROOT));
        output.addProperty("title", target.title());
        output.addProperty("description", target.subtitle());
        output.addProperty("registryId", target.registryId());
        output.addProperty("modOwner", target.modOwner());
        output.addProperty("position", target.position());
        output.addProperty("danger", target.danger());
        JsonArray details = new JsonArray();
        for (F3DataLine line : target.lines()) {
            if (line.label().isBlank() && line.value().isBlank()) {
                continue;
            }
            JsonObject detail = new JsonObject();
            detail.addProperty("label", line.label());
            detail.addProperty("value", line.value());
            detail.addProperty("state", line.state());
            details.add(detail);
        }
        output.add("details", details);
        JsonArray tags = new JsonArray();
        target.tags().forEach(tags::add);
        output.add("tags", tags);
        return output;
    }

    private static Result recipes(MinecraftClient client, String query, int limit) {
        if (client.getNetworkHandler() == null || client.world == null) {
            return unavailable("A connection with synchronized recipes is required.");
        }
        String needle = normalize(query);
        List<Recipe<?>> matches = new ArrayList<>();
        for (Recipe<?> recipe : client.getNetworkHandler().getRecipeManager().values()) {
            ItemStack output = recipe.getOutput(client.world.getRegistryManager());
            String id = recipe.getId().toString();
            String outputId = stackId(output);
            String outputName = output.isEmpty() ? "" : output.getName().getString();
            if (needle.isBlank()
                    || contains(id, needle)
                    || contains(outputId, needle)
                    || contains(outputName, needle)) {
                matches.add(recipe);
            }
        }
        matches.sort(Comparator.comparing(recipe -> recipe.getId().toString()));
        JsonArray rows = new JsonArray();
        for (Recipe<?> recipe : matches.stream().limit(limit).toList()) {
            ItemStack result = recipe.getOutput(client.world.getRegistryManager());
            JsonObject encoded = new JsonObject();
            encoded.addProperty("id", recipe.getId().toString());
            encoded.addProperty("type", idOf(Registries.RECIPE_TYPE, recipe.getType()));
            encoded.addProperty("serializer", idOf(Registries.RECIPE_SERIALIZER, recipe.getSerializer()));
            encoded.addProperty("output", stackId(result));
            encoded.addProperty("outputName", result.isEmpty() ? "" : result.getName().getString());
            encoded.addProperty("outputCount", result.getCount());
            JsonArray ingredients = new JsonArray();
            Map<String, Integer> exactTotals = new LinkedHashMap<>();
            boolean everySlotHasOneExactItem = true;
            int ingredientSlot = 0;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient == null || ingredient.isEmpty()) {
                    continue;
                }
                JsonArray alternatives = new JsonArray();
                ItemStack[] stacks = ingredient.getMatchingStacks();
                LinkedHashSet<String> alternativeIds = new LinkedHashSet<>();
                for (int index = 0; index < stacks.length && index < MAXIMUM_ALTERNATIVES; index++) {
                    JsonObject alternative = new JsonObject();
                    String alternativeId = stackId(stacks[index]);
                    alternative.addProperty("id", alternativeId);
                    alternative.addProperty("count", Math.max(1, stacks[index].getCount()));
                    alternatives.add(alternative);
                    if (!alternativeId.isBlank()) {
                        alternativeIds.add(alternativeId);
                    }
                }
                JsonObject encodedIngredient = new JsonObject();
                encodedIngredient.addProperty("slot", ingredientSlot++);
                encodedIngredient.add("alternatives", alternatives);
                encodedIngredient.addProperty("truncated", stacks.length > MAXIMUM_ALTERNATIVES);
                ingredients.add(encodedIngredient);
                if (stacks.length <= MAXIMUM_ALTERNATIVES && alternativeIds.size() == 1) {
                    exactTotals.merge(alternativeIds.iterator().next(), 1, Integer::sum);
                } else {
                    everySlotHasOneExactItem = false;
                }
            }
            encoded.add("ingredients", ingredients);
            JsonArray totals = new JsonArray();
            exactTotals.forEach((id, count) -> {
                JsonObject total = new JsonObject();
                total.addProperty("id", id);
                total.addProperty("count", count);
                totals.add(total);
            });
            encoded.addProperty("ingredientSlotCount", ingredientSlot);
            encoded.addProperty("exactIngredientTotalsComplete", everySlotHasOneExactItem);
            encoded.add("exactIngredientTotals", totals);
            rows.add(encoded);
        }
        JsonObject output = new JsonObject();
        output.addProperty("query", query == null ? "" : query);
        output.addProperty("matchCount", matches.size());
        output.addProperty("truncated", matches.size() > limit);
        output.add("recipes", rows);
        return success(output, "Recipes were read from the active synchronized recipe manager.");
    }

    private static Result nbt(String query, int limit) {
        MinecraftNbtSuggestionService.Knowledge knowledge =
                MinecraftNbtSuggestionService.nbtKnowledge(query, limit);
        JsonObject output = new JsonObject();
        output.addProperty("query", query == null ? "" : query);
        output.addProperty("format", knowledge.format());
        output.addProperty("guidance", knowledge.guidance());
        JsonArray templates = new JsonArray();
        for (MinecraftNbtSuggestionService.Template template : knowledge.templates()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("snbt", template.text());
            encoded.addProperty("description", template.description());
            templates.add(encoded);
        }
        output.add("templates", templates);
        return success(
                output,
                "Item NBT templates were read from Koil's version-local Minecraft syntax knowledge."
        );
    }

    private static Result advancements(MinecraftClient client, String query, int limit) {
        if (client.getNetworkHandler() == null) {
            return unavailable("A connection with synchronized advancements is required.");
        }
        String needle = normalize(query);
        List<Advancement> matches = new ArrayList<>();
        for (Advancement advancement : client.getNetworkHandler()
                .getAdvancementHandler()
                .getManager()
                .getAdvancements()) {
            String title = advancement.getDisplay() == null
                    ? ""
                    : advancement.getDisplay().getTitle().getString();
            String description = advancement.getDisplay() == null
                    ? ""
                    : advancement.getDisplay().getDescription().getString();
            if (needle.isBlank()
                    || contains(advancement.getId().toString(), needle)
                    || contains(title, needle)
                    || contains(description, needle)) {
                matches.add(advancement);
            }
        }
        matches.sort(Comparator.comparing(advancement -> advancement.getId().toString()));
        var progressSnapshot = com.spirit.koil.api.automation.AutomationCompletionModeController
                .advancementProgressSnapshot(client);
        JsonArray rows = new JsonArray();
        for (Advancement advancement : matches.stream().limit(limit).toList()) {
            JsonObject encoded = new JsonObject();
            encoded.addProperty("id", advancement.getId().toString());
            if (advancement.getDisplay() != null) {
                encoded.addProperty("title", advancement.getDisplay().getTitle().getString());
                encoded.addProperty("description", advancement.getDisplay().getDescription().getString());
                encoded.addProperty("hidden", advancement.getDisplay().isHidden());
                encoded.addProperty("frame", advancement.getDisplay().getFrame().getId());
            }
            encoded.addProperty("criteriaCount", advancement.getCriteria().size());
            encoded.addProperty("requirementGroups", advancement.getRequirements().length);
            boolean completed = progressSnapshot.containsKey(advancement) && progressSnapshot.get(advancement).isDone();
            encoded.addProperty("completed", completed);
            rows.add(encoded);
        }
        JsonObject output = new JsonObject();
        output.addProperty("query", query == null ? "" : query);
        output.addProperty("matchCount", matches.size());
        output.addProperty("truncated", matches.size() > limit);
        output.add("advancements", rows);
        return success(output, "Advancements were read from the active synchronized advancement manager.");
    }

    private static Result blockInfo(String query) {
        Identifier id = Identifier.tryParse(query);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return unavailable("No active block registry entry matches '" + query + "'. Use a namespaced id such as minecraft:stone.");
        }
        Block block = Registries.BLOCK.get(id);
        BlockState state = block.getDefaultState();
        JsonObject output = new JsonObject();
        output.addProperty("id", id.toString());
        output.addProperty("name", block.getName().getString());
        output.addProperty("translationKey", block.getTranslationKey());
        output.addProperty("item", Registries.ITEM.getId(block.asItem()).toString());
        output.addProperty("blastResistance", block.getBlastResistance());
        output.addProperty("luminance", state.getLuminance());
        output.addProperty("air", state.isAir());
        output.addProperty("defaultState", state.toString());
        JsonObject properties = new JsonObject();
        state.getProperties().forEach(property -> {
            JsonArray values = new JsonArray();
            property.getValues().forEach(value -> values.add(String.valueOf(value)));
            properties.add(property.getName(), values);
        });
        output.add("properties", properties);
        return success(output, "Block data was read from the active registry and its default state.");
    }

    private static Result itemInfo(String query) {
        Identifier id = Identifier.tryParse(query);
        if (id == null || !Registries.ITEM.containsId(id)) {
            return unavailable("No active item registry entry matches '" + query + "'. Search item identifiers first when the namespace is unknown.");
        }
        Item item = Registries.ITEM.get(id);
        ItemStack stack = item.getDefaultStack();
        JsonObject output = new JsonObject();
        output.addProperty("id", id.toString());
        output.addProperty("name", item.getName(stack).getString());
        output.addProperty("translationKey", item.getTranslationKey(stack));
        output.addProperty("maximumCount", item.getMaxCount());
        output.addProperty("maximumDamage", item.getMaxDamage());
        output.addProperty("damageable", item.isDamageable());
        output.addProperty("fireproof", item.isFireproof());
        output.addProperty("rarity", item.getRarity(stack).name().toLowerCase(Locale.ROOT));
        output.addProperty("enchantable", item.isEnchantable(stack));
        output.addProperty("enchantability", item.getEnchantability());
        output.addProperty("useAction", item.getUseAction(stack).name().toLowerCase(Locale.ROOT));
        output.addProperty("maximumUseTicks", item.getMaxUseTime(stack));
        output.addProperty("food", item.isFood());
        if (item.getFoodComponent() != null) {
            JsonObject food = new JsonObject();
            food.addProperty("hunger", item.getFoodComponent().getHunger());
            food.addProperty("saturationModifier", item.getFoodComponent().getSaturationModifier());
            food.addProperty("meat", item.getFoodComponent().isMeat());
            food.addProperty("alwaysEdible", item.getFoodComponent().isAlwaysEdible());
            food.addProperty("snack", item.getFoodComponent().isSnack());
            output.add("foodData", food);
        }
        return success(output, "Item data was read from the active vanilla/modded registry and default stack.");
    }

    private static Result entityInfo(String query) {
        Identifier id = Identifier.tryParse(query);
        if (id == null || !Registries.ENTITY_TYPE.containsId(id)) {
            return unavailable("No active entity-type registry entry matches '" + query + "'. Use a namespaced id such as minecraft:sheep.");
        }
        EntityType<?> type = Registries.ENTITY_TYPE.get(id);
        JsonObject output = new JsonObject();
        output.addProperty("id", id.toString());
        output.addProperty("name", type.getName().getString());
        output.addProperty("translationKey", type.getTranslationKey());
        output.addProperty("spawnGroup", type.getSpawnGroup().getName());
        output.addProperty("width", type.getWidth());
        output.addProperty("height", type.getHeight());
        output.addProperty("summonable", type.isSummonable());
        output.addProperty("fireImmune", type.isFireImmune());
        output.addProperty("saveable", type.isSaveable());
        return success(output, "Entity-type data was read from the active registry.");
    }

    private static Result effectInfo(String query) {
        Identifier id = Identifier.tryParse(query);
        if (id == null || !Registries.STATUS_EFFECT.containsId(id)) {
            return unavailable("No active status-effect registry entry matches '" + query + "'. Search status_effect identifiers first when the namespace is unknown.");
        }
        StatusEffect effect = Registries.STATUS_EFFECT.get(id);
        JsonObject output = new JsonObject();
        output.addProperty("id", id.toString());
        output.addProperty("name", effect.getName().getString());
        output.addProperty("translationKey", effect.getTranslationKey());
        output.addProperty("category", effect.getCategory().name().toLowerCase(Locale.ROOT));
        output.addProperty("color", String.format(Locale.ROOT, "#%06X", effect.getColor() & 0x00FFFFFF));
        output.addProperty("beneficial", effect.isBeneficial());
        return success(output, "Status-effect data was read from the active vanilla/modded registry.");
    }

    private static Result enchantmentInfo(String query) {
        Identifier id = Identifier.tryParse(query);
        if (id == null || !Registries.ENCHANTMENT.containsId(id)) {
            return unavailable("No active enchantment registry entry matches '" + query + "'. Search enchantment identifiers first when the namespace is unknown.");
        }
        Enchantment enchantment = Registries.ENCHANTMENT.get(id);
        JsonObject output = new JsonObject();
        output.addProperty("id", id.toString());
        output.addProperty("name", enchantment.getName(enchantment.getMinLevel()).getString());
        output.addProperty("translationKey", enchantment.getTranslationKey());
        output.addProperty("rarity", enchantment.getRarity().name().toLowerCase(Locale.ROOT));
        output.addProperty("target", enchantment.target.name().toLowerCase(Locale.ROOT));
        output.addProperty("minimumLevel", enchantment.getMinLevel());
        output.addProperty("maximumLevel", enchantment.getMaxLevel());
        output.addProperty("treasure", enchantment.isTreasure());
        output.addProperty("cursed", enchantment.isCursed());
        return success(output, "Enchantment data was read from the active vanilla/modded registry.");
    }

    private static Result registry(MinecraftClient client, String kind, String query, int limit) {
        return switch (kind) {
            case "item", "items" -> staticRegistry("item", Registries.ITEM, query, limit);
            case "block", "blocks" -> staticRegistry("block", Registries.BLOCK, query, limit);
            case "entity", "entity_type", "entities" ->
                    staticRegistry("entity_type", Registries.ENTITY_TYPE, query, limit);
            case "effect", "effects", "status_effect" ->
                    staticRegistry("status_effect", Registries.STATUS_EFFECT, query, limit);
            case "enchantment", "enchantments" ->
                    staticRegistry("enchantment", Registries.ENCHANTMENT, query, limit);
            case "sound", "sounds" -> staticRegistry("sound_event", Registries.SOUND_EVENT, query, limit);
            case "biome", "biomes" -> dynamicRegistry(client, "biome", RegistryKeys.BIOME, query, limit);
            case "structure", "structures" ->
                    dynamicRegistry(client, "structure", RegistryKeys.STRUCTURE, query, limit);
            case "dimension", "dimensions", "dimension_type", "dimension_types" ->
                    dynamicRegistry(client, "dimension_type", RegistryKeys.DIMENSION_TYPE, query, limit);
            default -> unavailable(
                    "Unknown registry. Use item, block, entity_type, status_effect, enchantment, sound_event, biome, structure, or dimension_type."
            );
        };
    }

    private static <T> Result staticRegistry(String kind, Registry<T> registry, String query, int limit) {
        return registryIds(kind, registry.getIds(), query, limit);
    }

    private static <T> Result dynamicRegistry(
            MinecraftClient client,
            String kind,
            net.minecraft.registry.RegistryKey<? extends Registry<? extends T>> key,
            String query,
            int limit
    ) {
        if (client.getNetworkHandler() == null) {
            return unavailable("An active connection is required for dynamic registry data.");
        }
        return client.getNetworkHandler().getRegistryManager()
                .getOptional(key)
                .<Result>map(registry -> registryIds(kind, registry.getIds(), query, limit))
                .orElseGet(() -> unavailable("The active connection did not provide the requested registry."));
    }

    private static Result registryIds(String kind, Iterable<Identifier> ids, String query, int limit) {
        MinecraftRegistrySuggestions.SearchResult matches =
                MinecraftRegistrySuggestions.search(ids, query, limit);
        JsonArray rows = new JsonArray();
        matches.candidates().stream()
                .map(candidate -> candidate.identifier().toString())
                .forEach(rows::add);
        JsonObject output = new JsonObject();
        output.addProperty("registry", kind);
        output.addProperty("query", query == null ? "" : query);
        output.addProperty("matchCount", matches.matchCount());
        output.addProperty("truncated", matches.truncated());
        output.add("ids", rows);
        return success(output, "Registry identifiers were read from the active client connection.");
    }

    private static String stackId(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "" : Registries.ITEM.getId(stack.getItem()).toString();
    }

    private static <T> String idOf(Registry<T> registry, T value) {
        Identifier id = value == null ? null : registry.getId(value);
        return id == null ? "" : id.toString();
    }

    private static boolean contains(String value, String normalizedNeedle) {
        return value != null && normalize(value).contains(normalizedNeedle);
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .strip();
    }

    private static List<String> normalizeFields(List<String> requestedFields) {
        if (requestedFields == null || requestedFields.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (String field : requestedFields) {
            if (field == null || field.isBlank()) {
                continue;
            }
            String normalized = field.strip();
            if (normalized.length() <= 48) {
                fields.add(normalized);
            }
            if (fields.size() >= 24) {
                break;
            }
        }
        return List.copyOf(fields);
    }

    private static JsonObject selectFields(JsonObject source, List<String> fields) {
        if (source == null) {
            return new JsonObject();
        }
        if (fields == null || fields.isEmpty()) {
            return source;
        }
        JsonObject selected = new JsonObject();
        for (String field : fields) {
            if (source.has(field)) {
                selected.add(field, source.get(field).deepCopy());
            }
        }
        return selected;
    }

    private static Result success(JsonObject output, String detail) {
        return new Result(true, output, detail);
    }

    private static Result unavailable(String detail) {
        return new Result(false, new JsonObject(), detail);
    }

    public record Result(boolean available, JsonObject output, String detail) {
        public Result {
            output = output == null ? new JsonObject() : output.deepCopy();
            detail = detail == null ? "" : detail;
        }
    }
}
