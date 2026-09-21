package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.command.MinecraftCommandInspector;
import com.spirit.koil.api.minecraft.MinecraftKnowledgeGraphService;
import com.spirit.koil.api.minecraft.MinecraftKnowledgeService;
import com.spirit.koil.api.minecraft.MinecraftNameResolver;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ToolExecutionPolicy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Provider-neutral, read-only Minecraft knowledge surface.
 *
 * <p>The main {@code minecraft.knowledge} tool is intentionally broader than a
 * registry lookup. It can resolve a natural Minecraft concept and traverse
 * client-visible relationships between items, blocks, recipes, tags,
 * advancements, enchantments, registries, dimensions, structures, installed
 * mods, and other synchronized data. Narrow tools remain available so the
 * model can request the smallest exact evidence surface when the target is
 * already known.</p>
 *
 * <p>No tool in this registry executes commands or mutates the game.</p>
 */
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
    public static final String TAG_TOOL_ID = "minecraft.tag_info";
    public static final String RESOURCE_TOOL_ID = "minecraft.resource_info";
    public static final String MOD_TOOL_ID = "minecraft.mod_info";

    private static final Set<String> GRAPH_QUERIES = Set.of(
            "search",
            "resolve",
            "explain",
            "relations",
            "uses",
            "sources",
            "crafting_path",
            "recipe_graph",
            "advancement_graph",
            "investigate",
            "expand",
            "references",
            "evidence",
            "recipe",
            "advancement"
    );

    private static final ModelToolDefinition DEFINITION = new ModelToolDefinition(
            TOOL_ID,
            """
                    General Minecraft knowledge and relationship resolver for the active game.
                    Use it when the answer depends on what a Minecraft concept is, what it is connected to, how it is made, what it is used for, what can produce it, what tags or block/item forms it has, what enchantments apply, what advancements reference it, or when the exact knowledge category is not known yet.

                    The semantic modes search, resolve, explain, relations, uses, sources, crafting_path, recipe_graph, and advancement_graph traverse every discoverable active static/synchronized registry family plus synchronized recipes and advancements. This includes ordinary gameplay registries and less obvious domains such as potions, particles, fluids, game events, block-entity types, villager/POI data, variants, instruments, and modded registry families.

                    For unfamiliar or complex modded mechanics, use investigate. It combines the normalized game-data graph with active client resources and bounded files packaged inside the owning Fabric mod, including data/assets JSON, configuration/mixin metadata, documentation-like text, and JVM class-file constant-pool references. It correlates namespaced identifiers found in those artifacts back into Minecraft facts and returns a bounded frontier of related subjects. Use expand on high-value frontier nodes to continue an investigation iteratively without dumping an entire modpack into one prompt. Use references/evidence when only implementation/resource references are needed.

                    Packaged resources and class constants are implementation clues, not automatic proof of runtime behavior. Important behavioral conclusions should be verified with synchronized/runtime evidence or a supported test. The tool never executes mod code, mutates the world, decompiles classes, or invents unavailable server-only state.

                    The exact modes remain available for command syntax, player/world state, crosshair target, registries, tags, resources, installed mods, exact item/block/entity/effect/enchantment data, biomes, dimensions, structures, NBT/SNBT guidance, recipes, advancements, and bounded artifact evidence.
                    """.strip(),
            schema(),
            List.of("client_available"),
            Set.of(),
            true,
            Duration.ofSeconds(8),
            false,
            false,
            Set.of("completed", "failed"),
            ToolExecutionPolicy.readOnly(
                    ToolExecutionPolicy.FreshnessMode.LIVE,
                    ToolExecutionPolicy.CostClass.MODERATE
            )
    );

    private static final List<ModelToolDefinition> DEFINITIONS = List.of(
            DEFINITION,
            definition(
                    COMMAND_TOOL_ID,
                    "Validate one drafted Minecraft command against the active Brigadier tree and return exact completions. Use command_help to discover an unfamiliar command first; this tool never executes.",
                    commandSchema()
            ),
            definition(
                    PLAYER_TOOL_ID,
                    "Read bounded current player, dimension, biome, position, inventory, effects, footing, crosshair target, riding state, elytra/rocket/boat availability, and currently executable travel modes.",
                    stateSchema()
            ),
            definition(
                    TARGET_TOOL_ID,
                    "Inspect the exact block or entity currently under the crosshair, including namespaced registry id, mod owner, position, details, and tags.",
                    stateSchema()
            ),
            definition(
                    REGISTRY_TOOL_ID,
                    "Search active vanilla, modded, and synchronized registry identifiers for items, blocks, entity types, effects, enchantments, sounds, biomes, structures, or dimension types.",
                    registrySchema()
            ),
            definition(
                    RECIPE_TOOL_ID,
                    """
                            Search the synchronized recipe manager semantically. Matches recipe ids, outputs, output display names, and ingredient alternatives. Returned recipes preserve ingredient slots and alternatives, so the model can connect components to outputs and discover what an item is used to craft.
                            """.strip(),
                    searchSchema()
            ),
            definition(
                    ADVANCEMENT_TOOL_ID,
                    """
                            Search synchronized advancements semantically. Matches ids, titles, descriptions, icons, parents/children, criterion names, and criterion condition data. Returns advancement ancestry, requirement groups, completion state, identifier references, and bounded exact criterion JSON when available.
                            """.strip(),
                    searchSchema()
            ),
            definition(
                    STRUCTURE_TOOL_ID,
                    "Search the active synchronized structure registry, including modded and datapack structures.",
                    searchSchema()
            ),
            definition(
                    ITEM_TOOL_ID,
                    "Read detailed data for one exact namespaced vanilla or modded item id, including stack limits, durability, rarity, enchantability, use behavior, and food data. Use minecraft.knowledge explain when relationships are also needed.",
                    exactIdSchema("item")
            ),
            definition(
                    BLOCK_TOOL_ID,
                    "Read detailed data for one exact namespaced block id, including display data, placement item, default state, properties, luminance, and blast resistance. Use minecraft.knowledge explain when relationships are also needed.",
                    exactIdSchema("block")
            ),
            definition(
                    ENTITY_TOOL_ID,
                    "Read detailed data for one exact namespaced entity type id, including display data, dimensions, spawn group, and summon/fire/save properties. Use minecraft.knowledge explain when related advancements or tags are also needed.",
                    exactIdSchema("entity")
            ),
            definition(
                    EFFECT_TOOL_ID,
                    "Read detailed data for one exact namespaced vanilla or modded status-effect id.",
                    exactIdSchema("status effect")
            ),
            definition(
                    ENCHANTMENT_TOOL_ID,
                    "Read detailed data for one exact namespaced vanilla or modded enchantment id, including levels, rarity, target, treasure, and curse state. Use minecraft.knowledge explain to relate it to acceptable items.",
                    exactIdSchema("enchantment")
            ),
            definition(
                    DIMENSION_TOOL_ID,
                    "Search active synchronized dimension-type identifiers and report the player's current dimension through player_state when needed.",
                    searchSchema()
            ),
            definition(
                    NBT_TOOL_ID,
                    "Read Koil's version-local item SNBT templates and grammar guidance without executing a command.",
                    searchSchema()
            ),
            definition(
                    TAG_TOOL_ID,
                    "Read one exact active item, block, entity-type, fluid, or biome tag and its bounded namespaced members. This includes synchronized mod/datapack tag membership.",
                    registrySchema()
            ),
            definition(
                    RESOURCE_TOOL_ID,
                    "Search active client resource-pack JSON ids, then read one exact bounded JSON resource. Use fields to return only required top-level keys; oversized JSON is never misreported as complete.",
                    resourceSchema()
            ),
            definition(
                    MOD_TOOL_ID,
                    "Search installed mod metadata by id or name, including exact version, environment, authors, licenses, and provided aliases.",
                    searchSchema()
            )
    );

    private MinecraftKnowledgeModelToolRegistry() {
    }

    public static String version() {
        return "minecraft-knowledge-v12-command-aware";
    }

    public static List<ModelToolDefinition> modelTools() {
        return DEFINITIONS;
    }

    /**
     * Smallest read-only Minecraft evidence group for normal grounded /ask.
     * If the shared selector does not recognize the wording but the prompt
     * appears to name an active Minecraft registry subject, keep the generic
     * semantic resolver available rather than forcing the model to guess.
     */
    public static List<ModelToolDefinition> toolsForQuestion(String prompt) {
        List<ModelToolDefinition> selected = LocalModelToolCatalog.informationToolsForPrompt(prompt).stream()
                .filter(tool -> supports(tool.id()))
                .limit(4)
                .toList();
        if (!selected.isEmpty()) {
            return selected;
        }
        if (MinecraftKnowledgeGraphService.likelyMinecraftSubject(prompt)) {
            return List.of(DEFINITION);
        }
        return List.of();
    }

    public static boolean supports(String toolId) {
        return DEFINITIONS.stream().anyMatch(definition -> definition.id().equals(toolId));
    }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        if (call == null || !supports(call.toolId())) {
            return CompletableFuture.completedFuture(
                    failure(call, "unknown_tool", "Unknown Minecraft knowledge tool.")
            );
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
            case TAG_TOOL_ID -> "tag";
            case RESOURCE_TOOL_ID -> "resource";
            case MOD_TOOL_ID -> "mod";
            default -> string(call.arguments(), "query", "");
        };

        String value = string(
                call.arguments(),
                "value",
                string(call.arguments(), "id", string(call.arguments(), "command", ""))
        );
        String registry = string(call.arguments(), "registry", "");
        int limit = integer(call.arguments(), "limit", 12);
        int depth = integer(call.arguments(), "depth", 2);
        List<String> fields = strings(call.arguments(), "fields");

        if ("command".equals(query)) {
            return MinecraftCommandInspector.inspect(value).thenApply(inspection -> {
                JsonObject output = new JsonObject();
                output.addProperty("query", "command");
                output.addProperty(
                        "command",
                        inspection.normalizedCommand().isBlank()
                                ? ""
                                : "/" + inspection.normalizedCommand()
                );
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

        if (GRAPH_QUERIES.contains(query) || MinecraftKnowledgeGraphService.supports(query)) {
            return MinecraftKnowledgeGraphService.query(
                    query,
                    value,
                    registry,
                    limit,
                    depth,
                    fields
            ).thenApply(result -> result.available()
                    ? completed(call, result.output(), result.detail())
                    : failure(call, "knowledge_unavailable", result.detail()));
        }

        String exactKind = exactLookupKind(query);
        if (!exactKind.isBlank() && !value.isBlank()) {
            MinecraftNameResolver.Match match = MinecraftNameResolver.best(exactKind, value);
            if (match != null && match.score() >= 520) {
                String resolvedValue = match.id().toString();
                return MinecraftKnowledgeService.query(query, resolvedValue, registry, limit, fields)
                        .thenApply(result -> {
                            if (!result.available()) return failure(call, "knowledge_unavailable", result.detail());
                            JsonObject output = result.output().deepCopy();
                            output.addProperty("requestedValue", value);
                            output.addProperty("resolvedValue", resolvedValue);
                            output.addProperty("resolutionScore", match.score());
                            output.addProperty("resolutionMode", resolvedValue.equalsIgnoreCase(value) ? "exact" : "fuzzy_name");
                            return completed(call, output, result.detail());
                        });
            }
        }

        return MinecraftKnowledgeService.query(query, value, registry, limit, fields)
                .thenApply(result -> result.available()
                        ? completed(call, result.output(), result.detail())
                        : failure(call, "knowledge_unavailable", result.detail()));
    }

    private static String exactLookupKind(String query) {
        return switch (query == null ? "" : query) {
            case "item" -> "item";
            case "block" -> "block";
            case "entity" -> "entity_type";
            case "effect" -> "status_effect";
            case "enchantment" -> "enchantment";
            default -> "";
        };
    }

    private static JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);

        JsonObject properties = new JsonObject();

        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty(
                "description",
                """
                        Knowledge operation. Prefer explain for a normal concept dossier. Use investigate for unfamiliar/complex modded mechanics: it combines normalized Minecraft facts with bounded active-resource and installed-mod artifact evidence, correlates identifiers, and returns a continuation frontier. Use expand on a frontier node to continue the investigation; references/evidence returns only artifact references. Use search/resolve when the exact registry kind is unknown; uses for recipes consuming an item; sources for recipes producing it; crafting_path for recursive ingredient dependencies; recipe_graph for semantic recipe search; advancement_graph for advancement relationships. Exact modes remain available.
                        """.strip()
        );
        JsonArray queryValues = new JsonArray();
        for (String value : List.of(
                "search",
                "resolve",
                "explain",
                "relations",
                "uses",
                "sources",
                "crafting_path",
                "recipe_graph",
                "advancement_graph",
                "investigate",
                "expand",
                "references",
                "evidence",
                "catalog",
                "command",
                "nbt",
                "player",
                "target",
                "registry",
                "tag",
                "resource",
                "mod",
                "recipe",
                "advancement",
                "structure",
                "item",
                "block",
                "entity",
                "effect",
                "enchantment",
                "biome",
                "dimension"
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
                """
                        Natural Minecraft subject, namespaced id, search fragment, command text, advancement title, recipe output/ingredient, or exact resource/tag id depending on query. Semantic modes accept ordinary wording such as "torch", "what makes a torch", "stick", or "Stone Age".
                        """.strip()
        );
        properties.add("value", value);

        JsonObject registry = new JsonObject();
        registry.addProperty("type", "string");
        registry.addProperty("maxLength", 64);
        registry.addProperty(
                "description",
                """
                        Optional kind hint for semantic resolution, or required registry for registry/tag queries. Common values: item, block, entity_type, status_effect, enchantment, sound_event, biome, structure, dimension_type, recipe, advancement, or mod.
                        """.strip()
        );
        properties.add("registry", registry);

        JsonObject limit = integerSchema(1, 32);
        limit.addProperty(
                "description",
                "Maximum bounded result rows or relationship entries. Prefer the smallest limit that can answer the question."
        );
        limit.addProperty("default", 12);
        properties.add("limit", limit);

        JsonObject depth = integerSchema(0, 4);
        depth.addProperty(
                "description",
                "Semantic traversal depth. Used by explain/crafting_path/investigate. 0 disables recursive crafting dependencies. Investigate/expand remain bounded and expose a continuation frontier instead of attempting unbounded recursion. 1-2 is normally sufficient; 4 is the hard bound."
        );
        depth.addProperty("default", 2);
        properties.add("depth", depth);

        JsonObject fields = new JsonObject();
        fields.addProperty("type", "array");
        fields.addProperty("maxItems", 24);
        fields.addProperty(
                "description",
                """
                        Optional exact top-level fields/sections to return. For semantic explain this can narrow the knowledge dossier to sections such as tags, blockForm, itemForm, recipesProducing, recipesUsing, applicableEnchantments, advancementReferences, craftingDependencies, or mod. For player/resource/target modes it retains the existing exact-field behavior.
                        """.strip()
        );
        fields.add("items", text(1, 64));
        properties.add("fields", fields);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("query");
        schema.add("required", required);
        return schema;
    }

    private static ModelToolDefinition definition(
            String id,
            String description,
            JsonObject schema
    ) {
        return new ModelToolDefinition(
                id,
                description,
                schema,
                List.of("client_available"),
                Set.of(),
                true,
                Duration.ofSeconds(8),
                false,
                false,
                Set.of("completed", "failed"),
                ToolExecutionPolicy.readOnly(
                        ToolExecutionPolicy.FreshnessMode.LIVE,
                        ToolExecutionPolicy.CostClass.MODERATE
                )
        );
    }

    private static JsonObject commandSchema() {
        JsonObject schema = baseSchema();
        JsonObject command = text(1, 2_048);
        command.addProperty(
                "description",
                "Command text with or without a leading slash. The command is validated but never executed."
        );
        schema.getAsJsonObject("properties").add("command", command);
        schema.getAsJsonArray("required").add("command");
        return schema;
    }

    private static JsonObject stateSchema() {
        JsonObject schema = baseSchema();

        JsonObject fields = new JsonObject();
        fields.addProperty("type", "array");
        fields.addProperty("maxItems", 24);
        fields.addProperty(
                "description",
                "Optional exact field names. Omit to return the normal bounded state snapshot."
        );
        fields.add("items", text(1, 64));
        schema.getAsJsonObject("properties").add("fields", fields);

        JsonObject limit = integerSchema(1, 32);
        limit.addProperty("description", "Bound for list-like state such as inventory entries.");
        schema.getAsJsonObject("properties").add("limit", limit);
        return schema;
    }

    private static JsonObject registrySchema() {
        JsonObject schema = searchSchema();
        JsonObject registry = text(1, 64);
        registry.addProperty(
                "description",
                "Registry kind such as item, block, entity_type, status_effect, enchantment, sound_event, biome, structure, dimension_type, or the supported tag registry kinds."
        );
        schema.getAsJsonObject("properties").add("registry", registry);
        schema.getAsJsonArray("required").add("registry");
        return schema;
    }

    private static JsonObject searchSchema() {
        JsonObject schema = baseSchema();

        JsonObject value = text(0, 2_048);
        value.addProperty(
                "description",
                "Natural search text, namespaced id, title, output, ingredient, or search fragment."
        );
        schema.getAsJsonObject("properties").add("value", value);

        JsonObject limit = integerSchema(1, 32);
        limit.addProperty("default", 12);
        schema.getAsJsonObject("properties").add("limit", limit);
        return schema;
    }

    private static JsonObject exactIdSchema(String label) {
        JsonObject schema = baseSchema();
        JsonObject id = text(3, 128);
        id.addProperty(
                "description",
                "Exact namespaced " + label + " id, including modded ids."
        );
        schema.getAsJsonObject("properties").add("id", id);
        schema.getAsJsonArray("required").add("id");
        return schema;
    }

    private static JsonObject resourceSchema() {
        JsonObject schema = searchSchema();

        JsonObject fields = new JsonObject();
        fields.addProperty("type", "array");
        fields.addProperty("maxItems", 24);
        fields.addProperty(
                "description",
                "Optional exact top-level JSON keys to return for one exact resource id."
        );
        fields.add("items", text(1, 64));
        schema.getAsJsonObject("properties").add("fields", fields);
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

    private static String string(
            JsonObject object,
            String key,
            String fallback
    ) {
        try {
            return object != null && object.has(key)
                    ? object.get(key).getAsString()
                    : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int integer(
            JsonObject object,
            String key,
            int fallback
    ) {
        try {
            return object != null && object.has(key)
                    ? object.get(key).getAsInt()
                    : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static List<String> strings(JsonObject object, String key) {
        try {
            if (object == null
                    || !object.has(key)
                    || !object.get(key).isJsonArray()) {
                return List.of();
            }
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            for (var element : object.getAsJsonArray(key)) {
                if (element != null && element.isJsonPrimitive()) {
                    values.add(element.getAsString());
                    if (values.size() >= 24) break;
                }
            }
            return List.copyOf(values);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static ModelToolResult completed(
            ModelToolCall call,
            JsonObject output,
            String detail
    ) {
        return new ModelToolResult(
                call.id(),
                call.toolId(),
                "completed",
                output,
                "",
                detail
        );
    }

    private static ModelToolResult failure(
            ModelToolCall call,
            String code,
            String detail
    ) {
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
