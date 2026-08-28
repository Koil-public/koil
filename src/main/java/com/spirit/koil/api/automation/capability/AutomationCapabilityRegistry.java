package com.spirit.koil.api.automation.capability;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.AutomationRequest;
import com.spirit.koil.api.automation.input.RawPlayerInputResolver;
import com.spirit.koil.api.model.ModelToolDefinition;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Model-facing automation boundary. Every schema, validation rule and plan
 * compiler comes from the same immutable definition.
 */
public final class AutomationCapabilityRegistry {
    private static final Pattern RESOURCE_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    private static final Pattern RESOURCE_PATH = Pattern.compile("^[a-z0-9_./-]+$");
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
        register(definitions, lookAtEntity());
        register(definitions, mountEntity());
        register(definitions, dismount());
        register(definitions, inspectSurroundings());
        register(definitions, tapRawInput());
        register(definitions, holdRawInput());
        register(definitions, releaseRawInput());
        register(definitions, releaseAllRawInput());
        register(definitions, rawMouseDelta());
        register(definitions, boatDeploy());
        register(definitions, elytraFlight());
        register(definitions, interactWithBlock());
        register(definitions, interactWithEntity());
        register(definitions, mineBlock());
        register(definitions, placeBlock());
        register(definitions, buildBlockPattern());
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
        addMovementOptionSchema(properties);
        require(schema, "direction", "distance");
        return definition(
                "movement.walk_relative",
                "Walk a measured number of blocks relative to the player's orientation at planning time.",
                schema,
                List.of("direction", "distance"),
                List.of("precise", "sprint", "allow_parkour", "allow_swim", "allow_break_blocks", "allow_place_blocks", "allow_combat_clear"),
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
                            + " movement.allow_sprint=" + sprint
                            + movementOptions(arguments);
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
        addMovementOptionSchema(properties);
        require(schema, "x", "z");
        return definition(
                "movement.move_to",
                "Navigate to world coordinates using live progress, collision and stuck detection.",
                schema,
                List.of("x", "z"),
                List.of("y", "precise", "sprint", "allow_parkour", "allow_swim", "allow_break_blocks", "allow_place_blocks", "allow_combat_clear"),
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
                            .append(" movement.allow_sprint=").append(sprint)
                            .append(movementOptions(arguments));
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

    private static JsonObject rawInputSchema(boolean ticks) {
        JsonObject schema = objectSchema();
        schema.getAsJsonObject("properties").add("key", string(1, 32));
        if (ticks) schema.getAsJsonObject("properties").add("ticks", number(1, 1200));
        require(schema, "key");
        return schema;
    }

    private static AutomationCapabilityDefinition tapRawInput() {
        return definition(
                "input.tap",
                "Tap one normal Minecraft player key or mouse action through KTL. Prefer a semantic gameplay capability when one exists.",
                rawInputSchema(true), List.of("key"), List.of("ticks"),
                List.of("world_loaded", "player_available", "no_conflicting_automation"),
                Set.of("synthetic_player_input"), false, Duration.ofSeconds(10),
                arguments -> {
                    String key = validatedRawInputKey(arguments);
                    return execute("input.tap", "Tap " + key,
                            "flow/core/tap_input.ktl input.key=" + key
                                    + " count.value=" + wholeNumber(arguments, "ticks", 2));
                }
        );
    }

    private static AutomationCapabilityDefinition holdRawInput() {
        return definition(
                "input.hold",
                "Hold one normal Minecraft player input for a bounded number of ticks, then always release it through KTL cleanup.",
                rawInputSchema(true), List.of("key"), List.of("ticks"),
                List.of("world_loaded", "player_available", "no_conflicting_automation"),
                Set.of("synthetic_player_input"), false, Duration.ofMinutes(1),
                arguments -> {
                    String key = validatedRawInputKey(arguments);
                    return execute("input.hold", "Hold " + key,
                            "flow/core/hold_input.ktl input.key=" + key
                                    + " count.value=" + wholeNumber(arguments, "ticks", 20));
                }
        );
    }

    private static AutomationCapabilityDefinition releaseRawInput() {
        return definition(
                "input.release",
                "Release one Koil-owned normal Minecraft player input through KTL.",
                rawInputSchema(false), List.of("key"), List.of(),
                List.of("player_available"), Set.of("releases_automation_input"), false, Duration.ofSeconds(5),
                arguments -> {
                    String key = validatedRawInputKey(arguments);
                    return execute("input.release", "Release " + key,
                            "flow/core/release_input.ktl input.key=" + key);
                }
        );
    }

    private static AutomationCapabilityDefinition releaseAllRawInput() {
        return definition(
                "input.release_all",
                "Release every player input currently owned by Koil automation.",
                objectSchema(), List.of(), List.of(), List.of("player_available"),
                Set.of("releases_automation_input"), false, Duration.ofSeconds(5),
                arguments -> execute("input.release_all", "Release all Automation input", "flow/core/release_all_input.ktl")
        );
    }

    private static AutomationCapabilityDefinition rawMouseDelta() {
        JsonObject schema = objectSchema();
        schema.getAsJsonObject("properties").add("yaw", number(-180.0D, 180.0D));
        schema.getAsJsonObject("properties").add("pitch", number(-90.0D, 90.0D));
        require(schema, "yaw", "pitch");
        return definition(
                "input.mouse_delta",
                "Apply a bounded raw mouse-look delta through KTL when no semantic look target is available.",
                schema, List.of("yaw", "pitch"), List.of(), List.of("world_loaded", "player_available"),
                Set.of("changes_player_orientation", "synthetic_player_input"), false, Duration.ofSeconds(10),
                arguments -> execute("input.mouse_delta", "Move mouse view",
                        "flow/core/mouse_delta.ktl yaw.delta=" + decimal(arguments.get("yaw").getAsDouble())
                                + " pitch.delta=" + decimal(arguments.get("pitch").getAsDouble()))
        );
    }

    private static AutomationCapabilityDefinition interactWithBlock() {
        JsonObject schema = targetedResourceSchema("block");
        schema.getAsJsonObject("properties").add("hand", enumString("main", "off"));
        schema.getAsJsonObject("properties").add("sneak", bool());
        return definition(
                "block.interact",
                "Navigate to and use a block through the player's normal interaction path, optionally while crouching for blocks whose alternate action requires sneak-use.",
                schema,
                List.of("block"),
                List.of("selector", "radius", "hand", "sneak"),
                List.of("world_loaded", "player_available", "block_reachable"),
                Set.of("moves_player", "uses_block"),
                false,
                Duration.ofMinutes(2),
                arguments -> execute(
                        "block.interact",
                        "Interact with " + arguments.get("block").getAsString(),
                        targetedInvocation("movement/interact/move_to_use_block_configured.ktl", "block", arguments)
                                + " hand.id=" + stringValue(arguments, "hand", "main")
                                + " interaction.sneak=" + booleanValue(arguments, "sneak", false)
                )
        );
    }

    private static AutomationCapabilityDefinition interactWithEntity() {
        JsonObject schema = targetedResourceSchema("entity");
        schema.getAsJsonObject("properties").add("hand", enumString("main", "off"));
        schema.getAsJsonObject("properties").add("sneak", bool());
        return definition(
                "entity.interact",
                "Navigate to and right-click a namespaced entity target with the selected hand, optionally while crouching.",
                schema,
                List.of("entity"),
                List.of("selector", "radius", "hand", "sneak"),
                List.of("world_loaded", "player_available", "target_available"),
                Set.of("moves_player", "uses_entity", "may_change_inventory"),
                false,
                Duration.ofMinutes(2),
                arguments -> execute(
                        "entity.interact",
                        "Interact with " + resourceId(arguments, "entity"),
                        targetedInvocation("interaction/core/interact_entity_configured.ktl", "entity", arguments)
                                + " target.kind=entity"
                                + " hand.id=" + stringValue(arguments, "hand", "main")
                                + " interaction.sneak=" + booleanValue(arguments, "sneak", false)
                )
        );
    }

    private static AutomationCapabilityDefinition lookAtEntity() {
        JsonObject schema = targetedResourceSchema("entity");
        schema.getAsJsonObject("properties").add("horizontal_only", bool());
        schema.getAsJsonObject("properties").add("turn_speed", enumString("slow", "natural", "fast"));
        schema.getAsJsonObject("properties").add("maximum_degrees_per_tick", number(0.5D, 30.0D));
        return definition(
                "entity.look_at",
                "Find a namespaced entity type, including modded entities, and turn the player's view toward the selected live target.",
                schema,
                List.of("entity"),
                List.of("selector", "radius", "horizontal_only", "turn_speed", "maximum_degrees_per_tick"),
                List.of("world_loaded", "player_available", "target_available"),
                Set.of("changes_player_orientation"),
                true,
                Duration.ofSeconds(30),
                arguments -> execute(
                        "entity.look_at",
                        "Look at " + resourceId(arguments, "entity"),
                        targetedInvocation("look/core/face_target.ktl", "entity", arguments)
                                + " target.kind=entity"
                                + " look.horizontal_only=" + booleanValue(arguments, "horizontal_only", false)
                                + " look.turn_speed=" + stringValue(arguments, "turn_speed", "natural")
                                + (arguments.has("maximum_degrees_per_tick")
                                ? " look.maximum_degrees_per_tick=" + decimal(arguments.get("maximum_degrees_per_tick").getAsDouble())
                                : "")
                )
        );
    }

    private static AutomationCapabilityDefinition mountEntity() {
        JsonObject schema = targetedResourceSchema("entity");
        return definition(
                "entity.mount",
                "Approach and mount one exact namespaced rideable entity, including modded rideable entities, then verify the player is riding that selected target.",
                schema,
                List.of("entity"),
                List.of("selector", "radius"),
                List.of("world_loaded", "player_available", "target_available", "target_rideable"),
                Set.of("moves_player", "uses_entity", "mounts_entity"),
                false,
                Duration.ofMinutes(2),
                arguments -> execute(
                        "entity.mount",
                        "Mount " + resourceId(arguments, "entity"),
                        targetedInvocation("movement/transport/mount_entity.ktl", "entity", arguments)
                                + " target.kind=entity"
                )
        );
    }

    private static AutomationCapabilityDefinition dismount() {
        return definition(
                "player.dismount",
                "Dismount the player's current vehicle or ridden entity and verify the player is no longer riding.",
                objectSchema(),
                List.of(),
                List.of(),
                List.of("world_loaded", "player_available"),
                Set.of("dismounts_entity"),
                false,
                Duration.ofSeconds(15),
                arguments -> execute("player.dismount", "Dismount", "movement/transport/dismount.ktl")
        );
    }

    private static AutomationCapabilityDefinition boatDeploy() {
        JsonObject schema = objectSchema();
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.add("boat", string(3, 128));
        properties.add("x", number(-30_000_000.0D, 30_000_000.0D));
        properties.add("y", number(-2048.0D, 2048.0D));
        properties.add("z", number(-30_000_000.0D, 30_000_000.0D));
        properties.add("craft_if_missing", bool());
        properties.add("recipe", string(3, 128));
        properties.add("placement", enumString("auto", "water", "ground"));
        properties.add("search_radius", number(2.0D, 8.0D));
        require(schema, "boat");
        return confirmedDefinition(
                "transport.boat_deploy",
                "Deploy and mount an exact registered boat without requiring coordinates. Omit x/y/z to inspect a bounded nearby area automatically; use placement=water when the user prefers water, placement=ground for land, or auto when unspecified. Exact coordinates remain optional and must be supplied together. Craft only when explicitly requested and a real 3x3 crafting-table screen, active recipe, and ingredients are verified.",
                schema,
                List.of("boat"),
                List.of("x", "y", "z", "craft_if_missing", "recipe", "placement", "search_radius"),
                List.of("world_loaded", "player_available", "reachable_boat_surface", "boat_or_verified_recipe_available"),
                Set.of("may_craft_item", "changes_inventory", "uses_item", "deploys_transport", "mounts_entity", "may_change_player_orientation"),
                false,
                Duration.ofMinutes(3),
                arguments -> {
                    String boat = resourceId(arguments, "boat");
                    boolean anyCoordinate = arguments.has("x") || arguments.has("y") || arguments.has("z");
                    if (anyCoordinate && !(arguments.has("x") && arguments.has("y") && arguments.has("z"))) {
                        throw new AutomationCapabilityException(
                                "missing_coordinate",
                                "transport.boat_deploy requires x, y, and z together only when exact placement is requested. Omit all three for automatic nearby placement."
                        );
                    }
                    StringBuilder invocation = new StringBuilder("movement/transport/boat_deploy_smart.ktl")
                            .append(" boat.item=").append(boat)
                            .append(" craft.if_missing=").append(booleanValue(arguments, "craft_if_missing", false))
                            .append(" placement.preference=").append(stringValue(arguments, "placement", "auto"))
                            .append(" search.radius=").append(decimal(arguments.has("search_radius")
                                    ? arguments.get("search_radius").getAsDouble()
                                    : 6.0D));
                    if (anyCoordinate) {
                        invocation.append(" target.x=").append(decimal(arguments.get("x").getAsDouble()))
                                .append(" target.y=").append(decimal(arguments.get("y").getAsDouble()))
                                .append(" target.z=").append(decimal(arguments.get("z").getAsDouble()));
                    }
                    if (arguments.has("recipe")) {
                        invocation.append(" recipe.id=").append(namespacedIdentifier(arguments, "recipe"));
                    }
                    return execute("transport.boat_deploy", "Resolve a nearby surface, deploy " + boat + ", and mount it", invocation.toString());
                }
        );
    }

    private static AutomationCapabilityDefinition inspectSurroundings() {
        JsonObject schema = objectSchema();
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.add("radius", number(2.0D, 8.0D));
        properties.add("focus", enumString("general", "boat", "water", "ground", "navigation"));
        return definition(
                "world.inspect_surroundings",
                "Read a bounded client-visible snapshot around the player. Reports the player position, nearby hostile/passive counts, and nearest reachable boat placement evidence for water and ground. Use this only when a plan needs environmental evidence; direct boat placement already performs the same bounded resolution internally.",
                schema,
                List.of(),
                List.of("radius", "focus"),
                List.of("world_loaded", "player_available"),
                Set.of(),
                true,
                Duration.ofSeconds(10),
                arguments -> execute(
                        "world.inspect_surroundings",
                        "Inspect bounded nearby terrain and entities",
                        "world/core/inspect_surroundings.ktl"
                                + " search.radius=" + decimal(arguments.has("radius") ? arguments.get("radius").getAsDouble() : 6.0D)
                                + " inspect.focus=" + stringValue(arguments, "focus", "general")
                )
        );
    }

    private static AutomationCapabilityDefinition elytraFlight() {
        JsonObject schema = objectSchema();
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.add("elytra", string(3, 128));
        properties.add("rocket", string(3, 128));
        properties.add("use_rocket", bool());
        properties.add("max_rockets", number(0, 64));
        properties.add("x", number(-30_000_000.0D, 30_000_000.0D));
        properties.add("y", number(-2048.0D, 2048.0D));
        properties.add("z", number(-30_000_000.0D, 30_000_000.0D));
        properties.add("arrival_radius", number(2.0D, 32.0D));
        properties.add("turn_speed", enumString("slow", "natural", "fast"));
        require(schema, "x", "y", "z");
        return confirmedDefinition(
                "transport.elytra_flight",
                "Equip a verified usable elytra, launch through the normal fall-flying action, optionally use up to the approved number of exact firework rockets when speed requires another boost, and steer with bounded camera easing until the requested target radius is reached.",
                schema,
                List.of("x", "y", "z"),
                List.of("elytra", "rocket", "use_rocket", "max_rockets", "arrival_radius", "turn_speed"),
                List.of("world_loaded", "player_available", "usable_elytra_available", "launch_clearance"),
                Set.of("changes_equipment", "moves_player", "glides_player", "may_consume_firework", "changes_player_orientation"),
                false,
                Duration.ofMinutes(3),
                arguments -> {
                    String elytra = arguments.has("elytra") ? resourceId(arguments, "elytra") : "minecraft:elytra";
                    String rocket = arguments.has("rocket") ? resourceId(arguments, "rocket") : "minecraft:firework_rocket";
                    boolean useRocket = booleanValue(arguments, "use_rocket", true);
                    int maxRockets = arguments.has("max_rockets")
                            ? Math.max(0, Math.min(64, arguments.get("max_rockets").getAsInt()))
                            : useRocket ? 4 : 0;
                    String invocation = "movement/transport/elytra_flight.ktl"
                            + " elytra.item=" + elytra
                            + " rocket.item=" + rocket
                            + " flight.use_rocket=" + useRocket
                            + " flight.max_rockets=" + maxRockets
                            + " target.x=" + decimal(arguments.get("x").getAsDouble())
                            + " target.y=" + decimal(arguments.get("y").getAsDouble())
                            + " target.z=" + decimal(arguments.get("z").getAsDouble())
                            + " arrival.radius=" + decimal(arguments.has("arrival_radius") ? arguments.get("arrival_radius").getAsDouble() : 6.0D)
                            + " look.turn_speed=" + stringValue(arguments, "turn_speed", "natural");
                    return execute("transport.elytra_flight", "Fly elytra to the requested target radius", invocation);
                }
        );
    }

    private static AutomationCapabilityDefinition mineBlock() {
        JsonObject schema = targetedResourceSchema("block");
        schema.getAsJsonObject("properties").add(
                "selector",
                enumString("nearest", "visible", "any", "below", "above", "looking_at")
        );
        schema.getAsJsonObject("properties").add("count", number(1, 4096));
        schema.getAsJsonObject("properties").add("quantity", enumString("exact", "all"));
        return definition(
                "block.mine",
                "Find and mine a measured collection of blocks through player controls. Use quantity=all to snapshot and process every matching block in the bounded radius; one completed member never completes that collection. Use selector below for the block directly under the player's feet, above for the block above the player, or looking_at for the crosshair block.",
                schema,
                List.of("block"),
                List.of("selector", "radius", "count", "quantity"),
                List.of("world_loaded", "player_available", "block_reachable"),
                Set.of("moves_player", "breaks_block", "may_change_inventory"),
                false,
                Duration.ofMinutes(10),
                arguments -> {
                    String selector = stringValue(arguments, "selector", "nearest");
                    long count = wholeNumber(arguments, "count", 1);
                    boolean all = "all".equals(stringValue(arguments, "quantity", "exact"));
                    boolean relativeSingle = count == 1L
                            && !all && Set.of("below", "above", "looking_at").contains(selector);
                    String task = all ? "blocks/core/mine_all_matching.ktl" : relativeSingle
                            ? "blocks/core/mine_relative_block.ktl"
                            : "blocks/core/mine_block_until_count.ktl";
                    return execute(
                            "block.mine",
                            "Mine " + resourceId(arguments, "block"),
                            targetedInvocation(task, "block", arguments) + " count.value=" + count
                                    + " quantity.mode=" + (all ? "all" : "exact")
                    );
                }
        );
    }

    private static AutomationCapabilityDefinition placeBlock() {
        JsonObject schema = objectSchema();
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.add("block", string(3, 128));
        properties.add("item", string(3, 128));
        properties.add("direction", enumString("forward", "backward", "left", "right", "north", "south", "east", "west"));
        properties.add("hand", enumString("main", "off"));
        properties.add("x", number(-30_000_000, 30_000_000));
        properties.add("y", number(-2048, 2048));
        properties.add("z", number(-30_000_000, 30_000_000));
        require(schema, "block");
        return definition(
                "block.place",
                "Place one namespaced block forward-adjacent to the player's footing or at an exact x/y/z target using inventory selection, crouched edge safety, normal block interaction, and resulting-world verification.",
                schema,
                List.of("block"),
                List.of("item", "direction", "hand", "x", "y", "z"),
                List.of("world_loaded", "player_available", "placement_item_available"),
                Set.of("moves_player", "places_block", "changes_inventory"),
                false,
                Duration.ofMinutes(2),
                arguments -> {
                    String block = resourceId(arguments, "block");
                    String item = arguments.has("item") ? resourceId(arguments, "item") : block;
                    boolean anyCoordinate = arguments.has("x") || arguments.has("y") || arguments.has("z");
                    if (anyCoordinate && !(arguments.has("x") && arguments.has("y") && arguments.has("z"))) {
                        throw new AutomationCapabilityException("missing_coordinate", "block.place requires x, y, and z together for exact placement.");
                    }
                    if (anyCoordinate) {
                        return execute(
                                "block.place",
                                "Place " + block + " at exact coordinates",
                                "blocks/core/place_block_at.ktl"
                                        + " block.id=" + block
                                        + " item.id=" + item
                                        + " target.x=" + wholeNumber(arguments, "x", 0)
                                        + " target.y=" + wholeNumber(arguments, "y", 0)
                                        + " target.z=" + wholeNumber(arguments, "z", 0)
                                        + " hand.id=" + stringValue(arguments, "hand", "main")
                        );
                    }
                    return execute(
                            "block.place",
                            "Place " + block,
                            "blocks/core/place_block_pattern.ktl"
                                    + " block.id=" + block
                                    + " item.id=" + item
                                    + " pattern.id=line count.value=1 pattern.length=1 pattern.width=1"
                                    + " direction.id=" + stringValue(arguments, "direction", "forward")
                                    + " hand.id=" + stringValue(arguments, "hand", "main")
                    );
                }
        );
    }

    private static AutomationCapabilityDefinition buildBlockPattern() {
        JsonObject schema = objectSchema();
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.add("block", string(3, 128));
        properties.add("item", string(3, 128));
        properties.add("shape", enumString("line", "perimeter", "platform"));
        properties.add("length", number(1, 64));
        properties.add("width", number(1, 64));
        properties.add("direction", enumString("forward", "backward", "left", "right", "north", "south", "east", "west"));
        properties.add("hand", enumString("main", "off"));
        require(schema, "block", "shape", "length");
        return definition(
                "block.build_pattern",
                "Build a verified line, rectangular perimeter/square, or filled platform from a namespaced block. Koil computes each placement, moves safely between adjacent positions, crouches at edges, and validates every placed block.",
                schema,
                List.of("block", "shape", "length"),
                List.of("item", "width", "direction", "hand"),
                List.of("world_loaded", "player_available", "enough_placement_items"),
                Set.of("moves_player", "places_multiple_blocks", "changes_inventory"),
                false,
                Duration.ofMinutes(20),
                arguments -> {
                    String block = resourceId(arguments, "block");
                    String item = arguments.has("item") ? resourceId(arguments, "item") : block;
                    String shape = arguments.get("shape").getAsString();
                    long length = wholeNumber(arguments, "length", 1);
                    long width = wholeNumber(arguments, "width", shape.equals("line") ? 1 : length);
                    long count = switch (shape) {
                        case "platform" -> Math.multiplyExact(length, width);
                        case "perimeter" -> length == 1 || width == 1
                                ? Math.max(length, width)
                                : Math.addExact(Math.multiplyExact(2L, length), Math.multiplyExact(2L, width)) - 4L;
                        default -> length;
                    };
                    if (count > 4096L) {
                        throw new AutomationCapabilityException("pattern_too_large", "The requested build pattern exceeds 4096 verified placements.");
                    }
                    return execute(
                            "block.build_pattern",
                            "Build " + shape + " with " + block,
                            "blocks/core/place_block_pattern.ktl"
                                    + " block.id=" + block
                                    + " item.id=" + item
                                    + " pattern.id=" + shape
                                    + " pattern.length=" + length
                                    + " pattern.width=" + width
                                    + " count.value=" + count
                                    + " direction.id=" + stringValue(arguments, "direction", "forward")
                                    + " hand.id=" + stringValue(arguments, "hand", "main")
                    );
                }
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
        schema.getAsJsonObject("properties").add("quantity", enumString("exact", "all"));
        return definition(
                "entity.kill",
                "Find and defeat a measured collection of live entity targets using player movement and combat. Use quantity=all to snapshot all matching live targets in the bounded radius; defeating one member never completes the collection.",
                schema,
                List.of("entity"),
                List.of("selector", "radius", "count", "quantity"),
                List.of("world_loaded", "player_available", "target_available"),
                Set.of("moves_player", "attacks_entity", "may_kill_entity"),
                false,
                Duration.ofMinutes(10),
                arguments -> {
                    boolean all = "all".equals(stringValue(arguments, "quantity", "exact"));
                    return execute(
                            "entity.kill",
                            "Defeat " + resourceId(arguments, "entity"),
                            targetedInvocation(all ? "combat/core/kill_all_matching.ktl" : "combat/core/kill_entity_until_count.ktl", "entity", arguments)
                                    + " target.kind=entity count.value=" + wholeNumber(arguments, "count", 1)
                                    + " quantity.mode=" + (all ? "all" : "exact")
                    );
                }
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
                resultStates(),
                compiler
        );
    }

    private static AutomationCapabilityDefinition confirmedDefinition(
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
                id, description, schema, required, optional, preconditions, sideEffects,
                reversible, timeout, true,
                AutomationMultiplayerPolicy.ALLOWED_WITH_PLAYER_PERMISSIONS,
                true,
                resultStates(),
                compiler
        );
    }

    private static Set<String> resultStates() {
        return Set.of("completed", "partial", "blocked", "failed", "cancelled",
                "interrupted", "no_target", "already_satisfied");
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
        if (!value.contains(":")) {
            value = resolveUnnamespacedResource(value, key);
        }
        if (!RESOURCE_ID.matcher(value).matches()) {
            throw new AutomationCapabilityException(
                    "invalid_resource_id",
                    "'" + key + "' must be a namespaced identifier (e.g. minecraft:stone, or dummmmmmy:target_dummy)"
            );
        }
        return value;
    }

    private static String resolveUnnamespacedResource(String value, String key) {
        if (!RESOURCE_PATH.matcher(value).matches()) {
            throw new AutomationCapabilityException(
                    "invalid_resource_id",
                    "'" + key + "' must be a resource name such as stone or a namespaced id such as minecraft:stone"
            );
        }
        Set<Identifier> ids = resourceIds(key);
        Identifier vanilla = Identifier.tryParse("minecraft:" + value);
        if (vanilla != null && ids.contains(vanilla)) {
            return vanilla.toString();
        }
        List<Identifier> matches = ids.stream()
                .filter(id -> id.getPath().equals(value))
                .sorted(Comparator.comparing(Identifier::toString))
                .limit(6)
                .toList();
        if (matches.size() == 1) {
            return matches.get(0).toString();
        }
        if (matches.size() > 1) {
            throw new AutomationCapabilityException(
                    "ambiguous_resource_id",
                    "'" + value + "' matches multiple " + key + " ids: "
                            + matches.stream().map(Identifier::toString).toList()
            );
        }
        // Keep the common Minecraft namespace as the deterministic fallback.
        // The registered executor will return a structured not-found result if
        // the active game registry truly lacks the resource.
        return "minecraft:" + value;
    }

    private static Set<Identifier> resourceIds(String key) {
        try {
            return switch (key) {
                case "block" -> Registries.BLOCK.getIds();
                case "item", "boat", "elytra", "rocket" -> Registries.ITEM.getIds();
                case "entity" -> Registries.ENTITY_TYPE.getIds();
                default -> Set.of();
            };
        } catch (LinkageError | IllegalStateException unavailable) {
            // Standalone proof processes do not bootstrap Minecraft's dynamic
            // registries. Runtime clients do, while the deterministic
            // minecraft: fallback below keeps validation testable headlessly.
            return Set.of();
        }
    }

    private static String namespacedIdentifier(JsonObject arguments, String key) {
        String value = arguments.get(key).getAsString().toLowerCase(Locale.ROOT);
        if (!value.contains(":")) value = "minecraft:" + value;
        if (!RESOURCE_ID.matcher(value).matches()) {
            throw new AutomationCapabilityException("invalid_resource_id",
                    "'" + key + "' must be a namespaced resource identifier.");
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

    private static void addMovementOptionSchema(JsonObject properties) {
        properties.add("allow_parkour", bool());
        properties.add("allow_swim", bool());
        properties.add("allow_break_blocks", bool());
        properties.add("allow_place_blocks", bool());
        properties.add("allow_combat_clear", bool());
    }

    private static String movementOptions(JsonObject arguments) {
        return " movement.policy=human_smart"
                + " movement.allow_parkour=" + booleanValue(arguments, "allow_parkour", true)
                + " movement.allow_swim=" + booleanValue(arguments, "allow_swim", true)
                + " movement.allow_break_blocks=" + booleanValue(arguments, "allow_break_blocks", false)
                + " movement.allow_place_blocks=" + booleanValue(arguments, "allow_place_blocks", false)
                + " movement.allow_combat_clear=" + booleanValue(arguments, "allow_combat_clear", false);
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
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

    private static String validatedRawInputKey(JsonObject arguments) {
        String key = RawPlayerInputResolver.normalize(stringValue(arguments, "key", ""));
        if (!RawPlayerInputResolver.syntacticallySupported(key)) {
            throw new AutomationCapabilityException(
                    "unsupported_input",
                    "'" + key + "' is not a supported keyboard, mouse, or semantic player input."
            );
        }
        return key;
    }

    private static String decimal(double value) {
        if (value == Math.rint(value)) {
            return Long.toString(Math.round(value));
        }
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
