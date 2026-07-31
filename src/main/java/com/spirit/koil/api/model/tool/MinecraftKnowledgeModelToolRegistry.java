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

/** One provider-neutral, read-only tool for active Minecraft knowledge. */
public final class MinecraftKnowledgeModelToolRegistry {
    public static final String TOOL_ID = "minecraft.knowledge";
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

    private MinecraftKnowledgeModelToolRegistry() {
    }

    public static String version() {
        return "minecraft-knowledge-v4";
    }

    public static List<ModelToolDefinition> modelTools() {
        return List.of(DEFINITION);
    }

    public static boolean supports(String toolId) {
        return TOOL_ID.equals(toolId);
    }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        if (call == null || !supports(call.toolId())) {
            return CompletableFuture.completedFuture(failure(call, "unknown_tool", "Unknown Minecraft knowledge tool."));
        }
        String query = string(call.arguments(), "query", "");
        String value = string(call.arguments(), "value", "");
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
                "catalog", "command", "nbt", "player", "target", "registry", "recipe", "advancement", "structure"
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
                "For registry queries: item, block, entity_type, status_effect, enchantment, sound_event, biome, or structure."
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
