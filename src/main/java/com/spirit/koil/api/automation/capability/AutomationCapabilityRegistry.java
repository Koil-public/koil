package com.spirit.koil.api.automation.capability;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.AutomationRequest;
import com.spirit.koil.api.model.ModelToolDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Model-facing automation boundary. Every schema, validation rule and plan
 * compiler comes from the same immutable definition.
 */
public final class AutomationCapabilityRegistry {
    private static final Pattern RESOURCE_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    private static final Map<String, AutomationCapabilityDefinition> DEFINITIONS = build();
    private static final String VERSION = "automation-capabilities-v1:"
            + Integer.toHexString(DEFINITIONS.keySet().hashCode());
    private static final List<ModelToolDefinition> MODEL_TOOLS = DEFINITIONS.values().stream()
            .map(AutomationCapabilityDefinition::toModelToolDefinition)
            .toList();

    private AutomationCapabilityRegistry() {
    }

    public static String version() {
        return VERSION;
    }

    public static Map<String, AutomationCapabilityDefinition> definitions() {
        return DEFINITIONS;
    }

    public static List<ModelToolDefinition> modelTools() {
        return MODEL_TOOLS;
    }

    public static AutomationCapabilityPlan validateAndCompile(
            String capabilityId,
            JsonObject arguments,
            UUID executionId
    ) {
        AutomationCapabilityDefinition definition = DEFINITIONS.get(capabilityId);
        if (definition == null) {
            throw new AutomationCapabilityException("unknown_tool", "Unknown automation capability: " + capabilityId);
        }
        JsonObject safeArguments = arguments == null ? new JsonObject() : arguments.deepCopy();
        validateSchema(definition, safeArguments);
        AutomationCapabilityPlan compiled = definition.invocationCompiler().compile(safeArguments);
        if (compiled.action() == AutomationCapabilityPlan.Action.CANCEL_CURRENT) {
            return compiled;
        }
        AutomationRequest request = compiled.request();
        if (request == null) {
            throw new AutomationCapabilityException("invalid_plan", "Capability produced no execution plan.");
        }
        return new AutomationCapabilityPlan(
                compiled.capabilityId(),
                compiled.objective(),
                compiled.action(),
                new AutomationRequest(request.rawInput(), request.runCommand(), request.directTemplate(), executionId)
        );
    }

    private static Map<String, AutomationCapabilityDefinition> build() {
        Map<String, AutomationCapabilityDefinition> definitions = new LinkedHashMap<>();
        register(definitions, walkRelative());
        register(definitions, moveTo());
        register(definitions, jump());
        register(definitions, interactWithBlock());
        register(definitions, mineBlock());
        register(definitions, openContainer());
        register(definitions, takeFromContainer());
        register(definitions, storeInContainer());
        register(definitions, useItem());
        register(definitions, eatItem());
        register(definitions, attackEntity());
        register(definitions, killEntity());
        register(definitions, setTime());
        register(definitions, grantAllAdvancements());
        register(definitions, minecraftCommand());
        register(definitions, cancel());
        return Collections.unmodifiableMap(definitions);
    }

    private static void register(
            Map<String, AutomationCapabilityDefinition> definitions,
            AutomationCapabilityDefinition definition
    ) {
        if (definitions.putIfAbsent(definition.id(), definition) != null) {
            throw new IllegalStateException("duplicate automation capability: " + definition.id());
        }
    }

    private static AutomationCapabilityDefinition walkRelative() {
        JsonObject schema = objectSchema();
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.add("direction", enumString("forward", "backward", "left", "right"));
        properties.add("distance", number(0.1D, 4096.0D));
        properties.add("precise", bool());
        properties.add("sprint", bool());
        require(schema, "direction", "distance");
        return definition(
                "movement.walk_relative",
                "Walk a measured number of blocks relative to the player's orientation at planning time.",
                schema,
                List.of("direction", "distance"),
                List.of("precise", "sprint"),
                List.of("world_loaded", "player_available", "no_conflicting_automation"),
                Set.of("moves_player", "may_change_player_orientation"),
                false,
                Duration.ofMinutes(3),
                arguments -> {
                    String direction = arguments.get("direction").getAsString().toLowerCase(Locale.ROOT);
                    double distance = arguments.get("distance").getAsDouble();
                    boolean precise = booleanValue(arguments, "precise", true);
                    boolean sprint = booleanValue(arguments, "sprint", true);
                    String invocation = "movement/navigation/move_relative.ktl"
                            + " direction.id=" + direction
                            + " count.value=" + decimal(distance)
                            + " unit.id=blocks"
                            + " movement.precise=" + precise
                            + " movement.allow_sprint=" + sprint;
                    return execute("movement.walk_relative", "Walk " + decimal(distance) + " blocks " + direction, invocation);
                }
        );
    }

    private static AutomationCapabilityDefinition moveTo() {
        JsonObject schema = objectSchema();
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.add("x", number(-30_000_000.0D, 30_000_000.0D));
        properties.add("y", number(-2048.0D, 2048.0D));
        properties.add("z", number(-30_000_000.0D, 30_000_000.0D));
        properties.add("precise", bool());
        properties.add("sprint", bool());
        require(schema, "x", "z");
        return definition(
                "movement.move_to",
                "Navigate to world coordinates using live progress, collision and stuck detection.",
                schema,
                List.of("x", "z"),
                List.of("y", "precise", "sprint"),
                List.of("world_loaded", "player_available", "no_conflicting_automation"),
                Set.of("moves_player", "may_change_player_orientation"),
                false,
                Duration.ofMinutes(5),
                arguments -> {
                    double x = arguments.get("x").getAsDouble();
                    double z = arguments.get("z").getAsDouble();
                    boolean precise = booleanValue(arguments, "precise", true);
                    boolean sprint = booleanValue(arguments, "sprint", true);
                    StringBuilder invocation = new StringBuilder("movement/navigation/move_to_position.ktl")
                            .append(" target.x=").append(decimal(x));
                    if (arguments.has("y")) {
                        invocation.append(" target.y=").append(decimal(arguments.get("y").getAsDouble()));
                    }
                    invocation.append(" target.z=").append(decimal(z))
                            .append(" movement.precise=").append(precise)
                            .append(" movement.allow_sprint=").append(sprint);
                    return execute("movement.move_to", "Move to " + decimal(x) + ", " + decimal(z), invocation.toString());
                }
        );
    }

    private static AutomationCapabilityDefinition jump() {
        JsonObject schema = objectSchema();
        return definition(
                "player.jump",
                "Perform one validated player jump.",
                schema,
                List.of(),
                List.of(),
                List.of("world_loaded", "player_available", "no_conflicting_automation"),
                Set.of("moves_player"),
                false,
                Duration.ofSeconds(10),
                arguments -> execute("player.jump", "Jump once", "movement/core/movement_jump_once.ktl")
        );
    }

    private static AutomationCapabilityDefinition interactWithBlock() {
        JsonObject schema = targetedResourceSchema("block");
        return definition(
                "block.interact",
                "Navigate to and use a block through the same interaction path available to the player.",
                schema,
                List.of("block"),
                List.of("selector", "radius"),
                List.of("world_loaded", "player_available", "block_reachable"),
                Set.of("moves_player", "uses_block"),
                false,
                Duration.ofMinutes(2),
                arguments -> execute(
                        "block.interact",
                        "Interact with " + arguments.get("block").getAsString(),
                        targetedInvocation("movement/interact/move_to_use_block.ktl", "block", arguments)
                )
        );
    }

    private static AutomationCapabilityDefinition mineBlock() {
        JsonObject schema = targetedResourceSchema("block");
        schema.getAsJsonObject("properties").add("count", number(1, 4096));
        return definition(
                "block.mine",
                "Find and mine a measured count of blocks through player movement and block-breaking controls.",
                schema,
                List.of("block"),
                List.of("selector", "radius", "count"),
                List.of("world_loaded", "player_available", "block_reachable"),
                Set.of("moves_player", "breaks_block", "may_change_inventory"),
                false,
                Duration.ofMinutes(10),
                arguments -> execute(
                        "block.mine",
                        "Mine " + resourceId(arguments, "block"),
                        targetedInvocation("blocks/core/mine_block_until_count.ktl", "block", arguments)
                                + " count.value=" + wholeNumber(arguments, "count", 1)
                )
        );
    }

    private static AutomationCapabilityDefinition openContainer() {
        JsonObject schema = targetedResourceSchema("container");
        return definition(
                "container.open",
                "Navigate to and open a container block.",
                schema,
                List.of("container"),
                List.of("selector", "radius"),
                List.of("world_loaded", "player_available", "container_reachable"),
                Set.of("moves_player", "opens_screen", "uses_block"),
                false,
                Duration.ofMinutes(2),
                arguments -> execute(
                        "container.open",
                        "Open " + arguments.get("container").getAsString(),
                        targetedInvocation("movement/interact/move_to_open_container.ktl", "container", arguments)
                )
        );
    }

    private static AutomationCapabilityDefinition takeFromContainer() {
        JsonObject schema = targetedResourceSchema("container");
        schema.getAsJsonObject("properties").add("item", string(3, 128));
        schema.getAsJsonObject("properties").add("count", number(1, 2304));
        require(schema, "item");
        return definition(
                "container.take_item",
                "Navigate to a container and take a measured item count using the player's inventory screen.",
                schema,
                List.of("container", "item"),
                List.of("selector", "radius", "count"),
                List.of("world_loaded", "player_available", "container_reachable"),
                Set.of("moves_player", "opens_screen", "changes_inventory", "changes_container"),
                false,
                Duration.ofMinutes(3),
                arguments -> {
                    String invocation = targetedInvocation("container/core/take_item_from_container.ktl", "container", arguments)
                            + " item.id=" + resourceId(arguments, "item")
                            + " count.value=" + wholeNumber(arguments, "count", 1);
                    return execute("container.take_item", "Take item from container", invocation);
                }
        );
    }

    private static AutomationCapabilityDefinition storeInContainer() {
        JsonObject schema = targetedResourceSchema("container");
        schema.getAsJsonObject("properties").add("item", string(3, 128));
        schema.getAsJsonObject("properties").add("count", number(1, 2304));
        require(schema, "item");
        return definition(
                "container.store_item",
                "Navigate to a container and store a measured item count using the player's inventory screen.",
                schema,
                List.of("container", "item"),
                List.of("selector", "radius", "count"),
                List.of("world_loaded", "player_available", "container_reachable", "item_available"),
                Set.of("moves_player", "opens_screen", "changes_inventory", "changes_container"),
                false,
                Duration.ofMinutes(3),
                arguments -> {
                    String invocation = targetedInvocation("container/core/store_item_in_container.ktl", "container", arguments)
                            + " item.id=" + resourceId(arguments, "item")
                            + " count.value=" + wholeNumber(arguments, "count", 1);
                    return execute("container.store_item", "Store item in container", invocation);
                }
        );
    }

    private static AutomationCapabilityDefinition useItem() {
        JsonObject schema = objectSchema();
        schema.getAsJsonObject("properties").add("item", string(3, 128));
        schema.getAsJsonObject("properties").add("hand", enumString("main", "off"));
        return definition(
                "inventory.use_item",
                "Select an optional item and use it through the current player's normal item-use path.",
                schema,
                List.of(),
                List.of("item", "hand"),
                List.of("world_loaded", "player_available"),
                Set.of("changes_inventory", "uses_item"),
                false,
                Duration.ofSeconds(30),
                arguments -> {
                    StringBuilder invocation = new StringBuilder("survival/core/use_selected_item.ktl");
                    if (arguments.has("item")) {
                        invocation.append(" item.id=").append(resourceId(arguments, "item"));
                    }
                    if (arguments.has("hand")) {
                        invocation.append(" hand.id=").append(arguments.get("hand").getAsString());
                    }
                    return execute("inventory.use_item", "Use selected item", invocation.toString());
                }
        );
    }

    private static AutomationCapabilityDefinition eatItem() {
        JsonObject schema = objectSchema();
        schema.getAsJsonObject("properties").add("item", string(3, 128));
        schema.getAsJsonObject("properties").add("count", number(1, 64));
        require(schema, "item");
        return definition(
                "inventory.eat_item",
                "Eat a measured item count through the player's inventory and use controls.",
                schema,
                List.of("item"),
                List.of("count"),
                List.of("world_loaded", "player_available", "item_available"),
                Set.of("changes_inventory", "changes_hunger", "uses_item"),
                false,
                Duration.ofMinutes(2),
                arguments -> execute(
                        "inventory.eat_item",
                        "Eat " + resourceId(arguments, "item"),
                        "survival/core/eat_item_until_count.ktl item.id=" + resourceId(arguments, "item")
                                + " count.value=" + wholeNumber(arguments, "count", 1)
                )
        );
    }

    private static AutomationCapabilityDefinition attackEntity() {
        JsonObject schema = targetedResourceSchema("entity");
        return definition(
                "entity.attack",
                "Navigate to and attack one live entity target using the player's normal combat path.",
                schema,
                List.of("entity"),
                List.of("selector", "radius"),
                List.of("world_loaded", "player_available", "target_available"),
                Set.of("moves_player", "attacks_entity"),
                false,
                Duration.ofMinutes(2),
                arguments -> execute(
                        "entity.attack",
                        "Attack " + resourceId(arguments, "entity"),
                        targetedInvocation("movement/interact/move_to_attack_entity.ktl", "entity", arguments)
                                + " target.kind=entity"
                )
        );
    }

    private static AutomationCapabilityDefinition killEntity() {
        JsonObject schema = targetedResourceSchema("entity");
        schema.getAsJsonObject("properties").add("count", number(1, 128));
        return definition(
                "entity.kill",
                "Find and defeat a measured count of live entity targets using player movement and combat.",
                schema,
                List.of("entity"),
                List.of("selector", "radius", "count"),
                List.of("world_loaded", "player_available", "target_available"),
                Set.of("moves_player", "attacks_entity", "may_kill_entity"),
                false,
                Duration.ofMinutes(10),
                arguments -> execute(
                        "entity.kill",
                        "Defeat " + resourceId(arguments, "entity"),
                        targetedInvocation("combat/core/kill_entity_until_count.ktl", "entity", arguments)
                                + " target.kind=entity count.value=" + wholeNumber(arguments, "count", 1)
                )
        );
    }

    private static AutomationCapabilityDefinition cancel() {
        JsonObject schema = objectSchema();
        return new AutomationCapabilityDefinition(
                "automation.cancel",
                "Cancel the currently running Koil automation task and release owned input.",
                schema,
                List.of(),
                List.of(),
                List.of("automation_running"),
                Set.of("stops_automation", "releases_automation_input"),
                true,
                Duration.ofSeconds(5),
                true,
                AutomationMultiplayerPolicy.ALLOWED_WITH_PLAYER_PERMISSIONS,
                false,
                Set.of("completed", "not_running", "failed"),
                arguments -> new AutomationCapabilityPlan(
                        "automation.cancel",
                        "Cancel current automation",
                        AutomationCapabilityPlan.Action.CANCEL_CURRENT,
                        null
                )
        );
    }

    private static AutomationCapabilityDefinition setTime() {
        JsonObject schema = objectSchema();
        schema.getAsJsonObject("properties").add("time", enumString("day", "noon", "night", "midnight"));
        require(schema, "time");
        return new AutomationCapabilityDefinition(
                "world.set_time",
                "Set the Minecraft world time through the current player's normal command permissions.",
                schema,
                List.of("time"),
                List.of(),
                List.of("world_loaded", "player_available", "player_command_permission"),
                Set.of("changes_world_time"),
                true,
                Duration.ofSeconds(10),
                false,
                AutomationMultiplayerPolicy.ALLOWED_WITH_PLAYER_PERMISSIONS,
                true,
                Set.of("submitted", "rejected", "failed"),
                arguments -> {
                    String time = arguments.get("time").getAsString().toLowerCase(Locale.ROOT);
                    return new AutomationCapabilityPlan(
                            "world.set_time",
                            "Set world time to " + time,
                            AutomationCapabilityPlan.Action.SUBMIT_COMMAND,
                            new AutomationRequest("/time set " + time, true, false)
                    );
                }
        );
    }

    private static AutomationCapabilityDefinition grantAllAdvancements() {
        return new AutomationCapabilityDefinition(
                "player.grant_advancements",
                "Grant every advancement to the current player through Minecraft's normal command path and the current player's permissions.",
                objectSchema(),
                List.of(),
                List.of(),
                List.of("world_loaded", "player_available", "player_command_permission"),
                Set.of("changes_player_advancements"),
                false,
                Duration.ofSeconds(30),
                false,
                AutomationMultiplayerPolicy.ALLOWED_WITH_PLAYER_PERMISSIONS,
                true,
                Set.of("submitted", "rejected", "failed"),
                arguments -> new AutomationCapabilityPlan(
                        "player.grant_advancements",
                        "Grant all advancements to the current player",
                        AutomationCapabilityPlan.Action.SUBMIT_COMMAND,
                        new AutomationRequest("/advancement grant @s everything", true, false)
                )
        );
    }

    private static AutomationCapabilityDefinition minecraftCommand() {
        JsonObject schema = objectSchema();
        schema.getAsJsonObject("properties").add("command", string(1, 2048));
        require(schema, "command");
        return new AutomationCapabilityDefinition(
                "minecraft.command",
                "Submit one Minecraft slash command through the current player's normal command path after explicit player confirmation. Submission does not prove server success.",
                schema,
                List.of("command"),
                List.of(),
                List.of("world_loaded", "player_available", "player_command_permission", "explicit_player_confirmation"),
                Set.of("command_defined_side_effects", "may_change_world", "may_change_player"),
                false,
                Duration.ofSeconds(30),
                false,
                AutomationMultiplayerPolicy.ALLOWED_WITH_PLAYER_PERMISSIONS,
                true,
                Set.of("submitted", "rejected", "failed"),
                arguments -> {
                    String command = normalizeMinecraftCommand(arguments.get("command").getAsString());
                    return new AutomationCapabilityPlan(
                            "minecraft.command",
                            "Submit /" + command,
                            AutomationCapabilityPlan.Action.SUBMIT_COMMAND,
                            new AutomationRequest("/" + command, true, false)
                    );
                }
        );
    }

    private static AutomationCapabilityDefinition definition(
            String id,
            String description,
            JsonObject schema,
            List<String> required,
            List<String> optional,
            List<String> preconditions,
            Set<String> sideEffects,
            boolean reversible,
            Duration timeout,
            AutomationCapabilityDefinition.InvocationCompiler compiler
    ) {
        return new AutomationCapabilityDefinition(
                id,
                description,
                schema,
                required,
                optional,
                preconditions,
                sideEffects,
                reversible,
                timeout,
                true,
                AutomationMultiplayerPolicy.ALLOWED_WITH_PLAYER_PERMISSIONS,
                false,
                Set.of("completed", "blocked", "cancelled", "failed", "timed_out"),
                compiler
        );
    }

    private static AutomationCapabilityPlan execute(String id, String objective, String invocation) {
        return new AutomationCapabilityPlan(
                id,
                objective,
                AutomationCapabilityPlan.Action.EXECUTE_PLAN,
                new AutomationRequest(invocation, true, true)
        );
    }

    private static void validateSchema(AutomationCapabilityDefinition definition, JsonObject arguments) {
        JsonObject schema = definition.inputSchema();
        JsonObject properties = schema.getAsJsonObject("properties");
        for (String key : arguments.keySet()) {
            if (properties == null || !properties.has(key)) {
                throw new AutomationCapabilityException(
                        "invalid_arguments",
                        definition.id() + " does not accept argument '" + key + "'."
                );
            }
        }
        for (String key : definition.requiredParameters()) {
            if (!arguments.has(key) || arguments.get(key).isJsonNull()) {
                throw new AutomationCapabilityException(
                        "missing_argument",
                        definition.id() + " requires argument '" + key + "'."
                );
            }
        }
        if (properties == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : arguments.entrySet()) {
            JsonObject property = properties.getAsJsonObject(entry.getKey());
            JsonElement value = entry.getValue();
            String type = property.get("type").getAsString();
            boolean correctType = switch (type) {
                case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
                case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
                case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
                default -> false;
            };
            if (!correctType) {
                throw new AutomationCapabilityException(
                        "invalid_argument_type",
                        "'" + entry.getKey() + "' must be a " + type + "."
                );
            }
            if ("number".equals(type)) {
                double number = value.getAsDouble();
                if (!Double.isFinite(number)
                        || (property.has("minimum") && number < property.get("minimum").getAsDouble())
                        || (property.has("maximum") && number > property.get("maximum").getAsDouble())) {
                    throw new AutomationCapabilityException(
                            "argument_out_of_range",
                            "'" + entry.getKey() + "' is outside the supported range."
                    );
                }
            }
            if ("string".equals(type)) {
                int length = value.getAsString().length();
                if ((property.has("minLength") && length < property.get("minLength").getAsInt())
                        || (property.has("maxLength") && length > property.get("maxLength").getAsInt())) {
                    throw new AutomationCapabilityException(
                            "argument_out_of_range",
                            "'" + entry.getKey() + "' has an unsupported length."
                    );
                }
            }
            if (property.has("enum")) {
                boolean matched = false;
                for (JsonElement allowed : property.getAsJsonArray("enum")) {
                    if (allowed.getAsString().equals(value.getAsString())) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    throw new AutomationCapabilityException(
                            "invalid_argument_value",
                            "'" + entry.getKey() + "' has an unsupported value."
                    );
                }
            }
        }
    }

    private static JsonObject objectSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        schema.add("required", new JsonArray());
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private static JsonObject targetedResourceSchema(String key) {
        JsonObject schema = objectSchema();
        schema.getAsJsonObject("properties").add(key, string(3, 128));
        schema.getAsJsonObject("properties").add("selector", enumString("nearest", "visible", "any"));
        schema.getAsJsonObject("properties").add("radius", number(1, 128));
        require(schema, key);
        return schema;
    }

    private static String targetedInvocation(String task, String resourceKey, JsonObject arguments) {
        StringBuilder invocation = new StringBuilder(task)
                .append(" target.id=").append(resourceId(arguments, resourceKey));
        if (arguments.has("selector")) {
            invocation.append(" target.selector=").append(arguments.get("selector").getAsString());
        }
        if (arguments.has("radius")) {
            invocation.append(" radius=").append(decimal(arguments.get("radius").getAsDouble()));
        }
        return invocation.toString();
    }

    private static String resourceId(JsonObject arguments, String key) {
        String value = arguments.get(key).getAsString().toLowerCase(Locale.ROOT);
        if (!RESOURCE_ID.matcher(value).matches()) {
            throw new AutomationCapabilityException(
                    "invalid_resource_id",
                    "'" + key + "' must be a namespaced Minecraft identifier such as minecraft:stone."
            );
        }
        return value;
    }

    private static long wholeNumber(JsonObject arguments, String key, long fallback) {
        if (!arguments.has(key)) {
            return fallback;
        }
        double value = arguments.get(key).getAsDouble();
        if (value != Math.rint(value)) {
            throw new AutomationCapabilityException("invalid_argument_value", "'" + key + "' must be a whole number.");
        }
        return Math.round(value);
    }

    private static void require(JsonObject schema, String... names) {
        JsonArray required = schema.getAsJsonArray("required");
        for (String name : names) {
            required.add(name);
        }
    }

    private static JsonObject enumString(String... values) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        JsonArray allowed = new JsonArray();
        for (String value : values) {
            allowed.add(value);
        }
        schema.add("enum", allowed);
        return schema;
    }

    private static JsonObject number(double minimum, double maximum) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "number");
        schema.addProperty("minimum", minimum);
        schema.addProperty("maximum", maximum);
        return schema;
    }

    private static JsonObject string(int minimumLength, int maximumLength) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("minLength", Math.max(0, minimumLength));
        schema.addProperty("maxLength", Math.max(minimumLength, maximumLength));
        return schema;
    }

    private static JsonObject bool() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "boolean");
        return schema;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        return object.has(key) ? object.get(key).getAsBoolean() : fallback;
    }

    private static String normalizeMinecraftCommand(String raw) {
        String source = raw == null ? "" : raw;
        if (source.length() > 2048) {
            throw new AutomationCapabilityException("invalid_command", "Minecraft command exceeds 2048 characters.");
        }
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character < 0x20 || character == 0x7F) {
                throw new AutomationCapabilityException("invalid_command", "Minecraft command contains a control character.");
            }
        }
        String command = source.strip();
        while (command.startsWith("/")) {
            command = command.substring(1).stripLeading();
        }
        if (command.isBlank()) {
            throw new AutomationCapabilityException("invalid_command", "Minecraft command is empty.");
        }
        return command;
    }

    private static String decimal(double value) {
        if (value == Math.rint(value)) {
            return Long.toString(Math.round(value));
        }
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
