package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.command.MinecraftCommandInspector;
import com.spirit.koil.api.minecraft.MinecraftKnowledgeService;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.LinkedHashSet;
import java.util.Locale;

/** One provider-neutral, read-only tool for active Minecraft knowledge. */
public final class MinecraftKnowledgeModelToolRegistry {
    public static final String TOOL_ID = "minecraft.knowledge";
    public static final String COMMAND_TOOL_ID = "minecraft.command_syntax";
    public static final String PLAYER_TOOL_ID = "minecraft.player_state";
    public static final String TARGET_TOOL_ID = "minecraft.target_info";
    public static final String REGISTRY_TOOL_ID = "minecraft.registry_search";
    public static final String RECIPE_TOOL_ID = "minecraft.recipe_info";
    public static final String ADVANCEMENT_TOOL_ID = "minecraft.advancement_info";
    public static final String STRUCTURE_TOOL_ID = "minecraft.structure_info";
    public static final String ITEM_TOOL_ID = "minecraft.item_info";
    public static final String BLOCK_TOOL_ID = "minecraft.block_info";
    public static final String ENTITY_TOOL_ID = "minecraft.entity_info";
    public static final String EFFECT_TOOL_ID = "minecraft.effect_info";
    public static final String ENCHANTMENT_TOOL_ID = "minecraft.enchantment_info";
    public static final String DIMENSION_TOOL_ID = "minecraft.dimension_info";
    public static final String NBT_TOOL_ID = "minecraft.nbt_info";
    private static final ModelToolDefinition DEFINITION = new ModelToolDefinition(
            TOOL_ID,
            """
                    Read exact data already available to the active Minecraft client. Query command syntax, item NBT/SNBT templates, player/world/context, crosshair target, registries, recipes, advancements, or structures. Use this instead of guessing vanilla, modded, datapack, or server-specific facts. It never executes or changes anything.
                    """.strip(),
            schema(),
            List.of("client_available"),
            Set.of(),
            true,
            Duration.ofSeconds(5),
            false,
            false,
            Set.of("completed", "failed")
    );
    private static final List<ModelToolDefinition> DEFINITIONS = List.of(
            DEFINITION,
            definition(COMMAND_TOOL_ID, "Validate and complete one Minecraft command against the active Brigadier command tree. Use this before minecraft.command; it never executes.", commandSchema()),
            definition(PLAYER_TOOL_ID, "Read bounded current player, dimension, biome, position, inventory, effects, footing, crosshair target, riding state, elytra/rocket/boat availability, and currently executable travel modes.", stateSchema()),
            definition(TARGET_TOOL_ID, "Inspect the exact block or entity currently under the crosshair, including namespaced registry id, mod owner, position, details, and tags.", stateSchema()),
            definition(REGISTRY_TOOL_ID, "Search active vanilla, modded, and synchronized registry identifiers for items, blocks, entity types, effects, enchantments, sounds, biomes, structures, or dimension types.", registrySchema()),
            definition(RECIPE_TOOL_ID, "Search synchronized recipes and return exact alternatives, outputs, and totals when they are deterministically known.", searchSchema()),
            definition(ADVANCEMENT_TOOL_ID, "Search synchronized advancements and return ids, titles, descriptions, frames, criteria counts, and requirement groups.", searchSchema()),
            definition(STRUCTURE_TOOL_ID, "Search the active synchronized structure registry, including modded and datapack structures.", searchSchema()),
            definition(ITEM_TOOL_ID, "Read detailed data for one exact namespaced vanilla or modded item id, including stack limits, durability, rarity, enchantability, use behavior, and food data.", exactIdSchema("item")),
            definition(BLOCK_TOOL_ID, "Read detailed data for one exact namespaced block id, including display data, placement item, default state, properties, luminance, and blast resistance.", exactIdSchema("block")),
            definition(ENTITY_TOOL_ID, "Read detailed data for one exact namespaced entity type id, including display data, dimensions, spawn group, and summon/fire/save properties.", exactIdSchema("entity")),
            definition(EFFECT_TOOL_ID, "Read detailed data for one exact namespaced vanilla or modded status-effect id.", exactIdSchema("status effect")),
            definition(ENCHANTMENT_TOOL_ID, "Read detailed data for one exact namespaced vanilla or modded enchantment id, including levels, rarity, target, treasure, and curse state.", exactIdSchema("enchantment")),
            definition(DIMENSION_TOOL_ID, "Search active synchronized dimension-type identifiers and report the player's current dimension through player_state when needed.", searchSchema()),
            definition(NBT_TOOL_ID, "Read Koil's version-local item SNBT templates and grammar guidance without executing a command.", searchSchema())
    );

    private MinecraftKnowledgeModelToolRegistry() {
    }

    public static String version() {
        return "minecraft-knowledge-v6";
    }

    public static List<ModelToolDefinition> modelTools() {
        return DEFINITIONS;
    }

    /** Smallest read-only Minecraft evidence group for normal grounded /ask. */
    public static List<ModelToolDefinition> toolsForQuestion(String prompt) {
        String text = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (has(text, "command", "syntax", "what do i type", "slash command")) ids.add(COMMAND_TOOL_ID);
        if (has(text, "nbt", "snbt", "item data", "component")) ids.add(NBT_TOOL_ID);
        if (has(text, "recipe", "craft", "ingredient", "smelt", "cook")) ids.add(RECIPE_TOOL_ID);
        if (has(text, "advancement", "criterion", "criteria")) ids.add(ADVANCEMENT_TOOL_ID);
        if (has(text, "structure", "fortress", "temple", "village")) ids.add(STRUCTURE_TOOL_ID);
        if (has(text, "where am i", "my inventory", "my position", "standing on", "travel mode")) ids.add(PLAYER_TOOL_ID);
        if (has(text, "looking at", "crosshair", "target")) ids.add(TARGET_TOOL_ID);
        if (has(text, "dimension", "nether", "overworld", "the end")) ids.add(DIMENSION_TOOL_ID);
        if (has(text, "enchantment", "enchanted")) ids.add(ENCHANTMENT_TOOL_ID);
        if (has(text, "effect", "potion")) ids.add(EFFECT_TOOL_ID);
        if (has(text, "entity", "mob", "creature", "summon")) ids.add(ENTITY_TOOL_ID);
        if (has(text, "block")) ids.add(BLOCK_TOOL_ID);
        if (has(text, "item", "tool", "weapon", "food")) ids.add(ITEM_TOOL_ID);
        if (has(text, "registry", "identifier", " id", "modded", "datapack", "tag", "exists")) ids.add(REGISTRY_TOOL_ID);
        if (ids.isEmpty()) ids.add(TOOL_ID);
        if (ids.size() > 1 && !ids.contains(REGISTRY_TOOL_ID)
                && has(text, "modded", "datapack", "namespaced", "exact id", "exists")) {
            ids.add(REGISTRY_TOOL_ID);
        }
        return DEFINITIONS.stream().filter(tool -> ids.contains(tool.id())).limit(4).toList();
    }

    public static boolean supports(String toolId) {
        return DEFINITIONS.stream().anyMatch(definition -> definition.id().equals(toolId));
    }

    private static boolean has(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        if (call == null || !supports(call.toolId())) {
            return CompletableFuture.completedFuture(failure(call, "unknown_tool", "Unknown Minecraft knowledge tool."));
        }
        String query = switch (call.toolId()) {
            case COMMAND_TOOL_ID -> "command";
            case PLAYER_TOOL_ID -> "player";
            case TARGET_TOOL_ID -> "target";
            case REGISTRY_TOOL_ID -> "registry";
            case RECIPE_TOOL_ID -> "recipe";
            case ADVANCEMENT_TOOL_ID -> "advancement";
            case STRUCTURE_TOOL_ID -> "structure";
            case ITEM_TOOL_ID -> "item";
            case BLOCK_TOOL_ID -> "block";
            case ENTITY_TOOL_ID -> "entity";
            case EFFECT_TOOL_ID -> "effect";
            case ENCHANTMENT_TOOL_ID -> "enchantment";
            case DIMENSION_TOOL_ID -> "dimension";
            case NBT_TOOL_ID -> "nbt";
            default -> string(call.arguments(), "query", "");
        };
        String value = string(call.arguments(), "value",
                string(call.arguments(), "id", string(call.arguments(), "command", "")));
        String registry = string(call.arguments(), "registry", "");
        int limit = integer(call.arguments(), "limit", 12);
        List<String> fields = strings(call.arguments(), "fields");
        if ("command".equals(query)) {
            return MinecraftCommandInspector.inspect(value).thenApply(inspection -> {
                JsonObject output = new JsonObject();
                output.addProperty("query", "command");
                output.addProperty("command", inspection.normalizedCommand().isBlank()
                        ? ""
                        : "/" + inspection.normalizedCommand());
                output.addProperty("valid", inspection.executable());
                output.addProperty("cursor", inspection.cursor());
                output.addProperty("problem", inspection.problem());
                output.addProperty("rootAvailable", inspection.rootAvailable());
                JsonArray roots = new JsonArray();
                inspection.availableRoots().forEach(roots::add);
                output.add("availableRoots", roots);
                JsonArray suggestions = new JsonArray();
                inspection.suggestions().forEach(suggestions::add);
                output.add("suggestions", suggestions);
                return completed(
                        call,
                        output,
                        inspection.executable()
                                ? "The active command tree accepts this command."
                                : "The active command tree rejected this syntax; use the problem and suggestions to repair it."
                );
            });
        }
        return MinecraftKnowledgeService.query(query, value, registry, limit, fields)
                .thenApply(result -> result.available()
                        ? completed(call, result.output(), result.detail())
                        : failure(call, "knowledge_unavailable", result.detail()));
    }

    private static JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject();
        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        JsonArray queryValues = new JsonArray();
        for (String value : List.of(
                "catalog", "command", "nbt", "player", "target", "registry", "recipe", "advancement", "structure", "item", "block", "entity", "effect", "enchantment", "biome", "dimension"
        )) {
            queryValues.add(value);
        }
        query.add("enum", queryValues);
        properties.add("query", query);
        JsonObject value = new JsonObject();
        value.addProperty("type", "string");
        value.addProperty("maxLength", 2_048);
        value.addProperty(
                "description",
                "Command text, item/NBT/recipe/advancement/structure name, identifier, or search fragment."
        );
        properties.add("value", value);
        JsonObject registry = new JsonObject();
        registry.addProperty("type", "string");
        registry.addProperty(
                "description",
                "For registry queries: item, block, entity_type, status_effect, enchantment, sound_event, biome, structure, or dimension_type."
        );
        properties.add("registry", registry);
        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("minimum", 1);
        limit.addProperty("maximum", 32);
        limit.addProperty("default", 12);
        properties.add("limit", limit);
        JsonObject fields = new JsonObject();
        fields.addProperty("type", "array");
        fields.addProperty("maxItems", 24);
        fields.addProperty(
                "description",
                "Optional exact field names to return. Always request the smallest needed subset. Player fields include name, uuid, entityId, dimension, biome, position, facing, yaw, pitch, gameMode, health, maximumHealth, food, saturation, armor, experienceLevel, onGround, sprinting, sneaking, swimming, flying, mainHand, offHand, standingOn, effects, inventory, lookingAt. Target fields include type, title, description, registryId, modOwner, position, danger, details, tags."
        );
        JsonObject fieldItems = new JsonObject();
        fieldItems.addProperty("type", "string");
        fieldItems.addProperty("maxLength", 48);
        fields.add("items", fieldItems);
        properties.add("fields", fields);
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("query");
        schema.add("required", required);
        return schema;
    }

    private static ModelToolDefinition definition(String id, String description, JsonObject schema) {
        return new ModelToolDefinition(
                id,
                description,
                schema,
                List.of("client_available"),
                Set.of(),
                true,
                Duration.ofSeconds(5),
                false,
                false,
                Set.of("completed", "failed")
        );
    }

    private static JsonObject commandSchema() {
        JsonObject schema = baseSchema();
        schema.getAsJsonObject("properties").add("command", text(1, 2_048));
        schema.getAsJsonArray("required").add("command");
        return schema;
    }

    private static JsonObject stateSchema() {
        JsonObject schema = baseSchema();
        JsonObject fields = new JsonObject();
        fields.addProperty("type", "array");
        fields.addProperty("maxItems", 24);
        JsonObject item = text(1, 48);
        fields.add("items", item);
        schema.getAsJsonObject("properties").add("fields", fields);
        JsonObject limit = integerSchema(1, 32);
        schema.getAsJsonObject("properties").add("limit", limit);
        return schema;
    }

    private static JsonObject registrySchema() {
        JsonObject schema = searchSchema();
        schema.getAsJsonObject("properties").add("registry", text(1, 64));
        schema.getAsJsonArray("required").add("registry");
        return schema;
    }

    private static JsonObject searchSchema() {
        JsonObject schema = baseSchema();
        schema.getAsJsonObject("properties").add("value", text(0, 2_048));
        schema.getAsJsonObject("properties").add("limit", integerSchema(1, 32));
        return schema;
    }

    private static JsonObject exactIdSchema(String label) {
        JsonObject schema = baseSchema();
        JsonObject id = text(3, 128);
        id.addProperty("description", "Exact namespaced " + label + " id, including modded ids.");
        schema.getAsJsonObject("properties").add("id", id);
        schema.getAsJsonArray("required").add("id");
        return schema;
    }

    private static JsonObject baseSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        schema.add("properties", new JsonObject());
        schema.add("required", new JsonArray());
        return schema;
    }

    private static JsonObject text(int minimum, int maximum) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("minLength", minimum);
        schema.addProperty("maxLength", maximum);
        return schema;
    }

    private static JsonObject integerSchema(int minimum, int maximum) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "integer");
        schema.addProperty("minimum", minimum);
        schema.addProperty("maximum", maximum);
        return schema;
    }

    private static String string(JsonObject object, String key, String fallback) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static List<String> strings(JsonObject object, String key) {
        try {
            if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
                return List.of();
            }
            return object.getAsJsonArray(key).asList().stream()
                    .filter(element -> element != null && element.isJsonPrimitive())
                    .map(element -> element.getAsString())
                    .limit(24)
                    .toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static ModelToolResult completed(ModelToolCall call, JsonObject output, String detail) {
        return new ModelToolResult(call.id(), call.toolId(), "completed", output, "", detail);
    }

    private static ModelToolResult failure(ModelToolCall call, String code, String detail) {
        return new ModelToolResult(
                call == null ? "" : call.id(),
                call == null ? "" : call.toolId(),
                "failed",
                new JsonObject(),
                code,
                detail
        );
    }
}
