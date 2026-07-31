package com.spirit.koil.api.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.f3.F3DataLine;
import com.spirit.koil.api.f3.F3Mode;
import com.spirit.koil.api.f3.F3TargetInspector;
import com.spirit.koil.api.f3.F3TargetSnapshot;
import net.minecraft.advancement.Advancement;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
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
                            "Unknown knowledge query. Use catalog, player, target, registry, recipe, advancement, structure, or nbt."
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
                        "offHand", "standingOn", "effects", "inventory", "lookingAt"
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
            rows.add(encoded);
        }
        JsonObject output = new JsonObject();
        output.addProperty("query", query == null ? "" : query);
        output.addProperty("matchCount", matches.size());
        output.addProperty("truncated", matches.size() > limit);
        output.add("advancements", rows);
        return success(output, "Advancements were read from the active synchronized advancement manager.");
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
            default -> unavailable(
                    "Unknown registry. Use item, block, entity_type, status_effect, enchantment, sound_event, biome, or structure."
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
