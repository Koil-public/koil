package com.spirit.koil.api.automation.feedback;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.spirit.koil.api.automation.cli.AutomationCliRow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class AutomationFailureRegistry {
    private static final Path ROOT = Path.of("koil/automation/failure_types");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile boolean bootstrapped;

    private AutomationFailureRegistry() {
    }

    public static List<AutomationFailureType> failureTypesFor(String nodeType) {
        ensureDefaultRegistry();
        String normalized = normalizeNodeType(nodeType);
        List<AutomationFailureType> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (FailureTypeFile file : files()) {
            if (file.failure_types == null) {
                continue;
            }
            for (AutomationFailureType type : file.failure_types) {
                if (applies(type, normalized) && seen.add(type.id())) {
                    result.add(type);
                }
            }
        }
        result.sort(Comparator.comparing(AutomationFailureType::label, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public static String nodeTypeFor(AutomationCliRow row) {
        ensureDefaultRegistry();
        if (row == null) {
            return "ui";
        }
        String text = searchableText(row);
        String explicit = explicitType(text);
        if (!explicit.isBlank()) {
            return explicit;
        }
        for (FailureTypeFile file : files()) {
            String nodeType = normalizeNodeType(file.node_type);
            for (String alias : safeList(file.node_type_aliases)) {
                String normalizedAlias = alias == null ? "" : alias.trim().toLowerCase(Locale.ROOT);
                if (!normalizedAlias.isBlank() && text.contains(normalizedAlias)) {
                    return nodeType;
                }
            }
        }
        return "ui";
    }

    public static List<String> nodeTypes() {
        ensureDefaultRegistry();
        Set<String> types = new LinkedHashSet<>();
        for (FailureTypeFile file : files()) {
            if (file.node_type != null && !file.node_type.isBlank()) {
                types.add(normalizeNodeType(file.node_type));
            }
            if (file.failure_types != null) {
                for (AutomationFailureType type : file.failure_types) {
                    for (String candidate : type.applicable_node_types()) {
                        String normalized = normalizeNodeType(candidate);
                        if (!normalized.equals("*")) {
                            types.add(normalized);
                        }
                    }
                }
            }
        }
        if (types.isEmpty()) {
            types.addAll(List.of("movement", "inventory", "combat", "block", "ui"));
        }
        return List.copyOf(types);
    }

    public static void ensureDefaultRegistry() {
        if (bootstrapped && Files.exists(ROOT)) {
            return;
        }
        synchronized (AutomationFailureRegistry.class) {
            if (bootstrapped && Files.exists(ROOT)) {
                return;
            }
            try {
                Files.createDirectories(ROOT);
                writeDefault("movement.json", movementJson());
                writeDefault("inventory.json", inventoryJson());
                writeDefault("combat.json", combatJson());
                writeDefault("block.json", blockJson());
                writeDefault("ui.json", uiJson());
                bootstrapped = true;
            } catch (IOException exception) {
                bootstrapped = true;
            }
        }
    }

    private static void writeDefault(String name, String json) throws IOException {
        Path path = ROOT.resolve(name);
        if (!Files.exists(path)) {
            Files.writeString(path, json, StandardCharsets.UTF_8);
        }
    }

    private static List<FailureTypeFile> files() {
        try {
            if (!Files.exists(ROOT)) {
                return List.of();
            }
            List<FailureTypeFile> loaded = new ArrayList<>();
            try (var stream = Files.walk(ROOT)) {
                for (Path path : stream.filter(path -> path.toString().endsWith(".json")).sorted(Comparator.comparing(Path::toString)).toList()) {
                    try {
                        FailureTypeFile file = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), FailureTypeFile.class);
                        if (file != null) {
                            if (file.node_type == null || file.node_type.isBlank()) {
                                file.node_type = fileNameNodeType(path);
                            }
                            loaded.add(file);
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            return loaded;
        } catch (IOException exception) {
            return List.of();
        }
    }

    private static boolean applies(AutomationFailureType type, String nodeType) {
        if (type == null || type.id().isBlank()) {
            return false;
        }
        if (type.applicable_node_types().isEmpty()) {
            return true;
        }
        for (String candidate : type.applicable_node_types()) {
            String normalized = normalizeNodeType(candidate);
            if ("*".equals(normalized) || normalized.equals(nodeType)) {
                return true;
            }
        }
        return false;
    }

    private static String searchableText(AutomationCliRow row) {
        return String.join(" ",
                safe(row.rowId()),
                safe(row.sectionId()),
                safe(row.rowType()),
                safe(row.statusMarker()),
                safe(row.label()),
                safe(row.value()),
                safe(row.search()),
                safe(row.requires()),
                safe(row.received()),
                safe(row.output()),
                safe(row.failure()),
                safe(row.recovery()),
                safe(row.source())
        ).toLowerCase(Locale.ROOT);
    }

    private static String explicitType(String text) {
        if (containsAny(text, "cap.movement", "cap.path", "cap.parkour", "move.", "movement", "path.", "parkour", "nav.", "walk_relative")) {
            return "movement";
        }
        if (containsAny(text, "cap.inventory", "cap.container", "inventory", "container", "slot", "hotbar", "consume", "item.id", "item not")) {
            return "inventory";
        }
        if (containsAny(text, "cap.combat", "cap.interaction.attack", "attack", "combat", "target death", "kill", "entity_target")) {
            return "combat";
        }
        if (containsAny(text, "break_block", "use_item_on_block", "cap.world.scan_blocks", "block", "mine", "place", "ray", "target.block")) {
            return "block";
        }
        if (containsAny(text, "cap.command", "raw.command", "sendchatcommand", "command")) {
            return "ui";
        }
        if (containsAny(text, "branch", "condition", "eval.", "evaluator")) {
            return "ui";
        }
        return "";
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String fileNameNodeType(Path path) {
        String fileName = path == null || path.getFileName() == null ? "ui" : path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return normalizeNodeType(dot < 0 ? fileName : fileName.substring(0, dot));
    }

    private static String normalizeNodeType(String value) {
        String normalized = value == null || value.isBlank() ? "ui" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return normalized.isBlank() ? "ui" : normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static String movementJson() {
        return """
                {
                  "schema_version": 1,
                  "node_type": "movement",
                  "node_type_aliases": ["cap.movement", "cap.path", "cap.parkour", "move.", "path.", "parkour", "walk_relative", "move_to_target", "navigation"],
                  "failure_types": [
                    {
                      "id": "movement.stuck",
                      "label": "stuck",
                      "applicable_node_types": ["movement"],
                      "suggested_fix_rules": ["increase_stuck_detection_context", "add_escape_retry", "verify_block_collision"],
                      "auto_fix_rule": "movement/recovery/stuck",
                      "patch_generator": "ktl.patch.movement.stuck",
                      "learning_hook": "oli.feedback.movement.stuck"
                    },
                    {
                      "id": "movement.wrong_direction",
                      "label": "wrong direction",
                      "applicable_node_types": ["movement"],
                      "suggested_fix_rules": ["verify_yaw_basis", "verify_direction_binding", "capture_player_facing_before_move"],
                      "auto_fix_rule": "movement/recovery/wrong_direction",
                      "patch_generator": "ktl.patch.movement.direction",
                      "learning_hook": "oli.feedback.movement.direction"
                    },
                    {
                      "id": "movement.overshoot",
                      "label": "overshoot",
                      "applicable_node_types": ["movement"],
                      "suggested_fix_rules": ["lower_stop_distance", "enable_precision_finish", "slow_near_target"],
                      "auto_fix_rule": "movement/recovery/overshoot",
                      "patch_generator": "ktl.patch.movement.overshoot",
                      "learning_hook": "oli.feedback.movement.overshoot"
                    },
                    {
                      "id": "movement.path_blocked",
                      "label": "path blocked",
                      "applicable_node_types": ["movement"],
                      "suggested_fix_rules": ["replan_local_segment", "increase_route_radius", "mark_blocked_cell"],
                      "auto_fix_rule": "movement/recovery/path_blocked",
                      "patch_generator": "ktl.patch.movement.path_blocked",
                      "learning_hook": "oli.feedback.movement.path_blocked"
                    },
                    {
                      "id": "movement.arrival_unverified",
                      "label": "arrival not verified",
                      "applicable_node_types": ["movement"],
                      "suggested_fix_rules": ["add_arrival_check", "compare_start_end_position", "adjust_stop_distance"],
                      "auto_fix_rule": "movement/recovery/arrival_unverified",
                      "patch_generator": "ktl.patch.movement.arrival",
                      "learning_hook": "oli.feedback.movement.arrival"
                    }
                  ]
                }
                """;
    }

    private static String inventoryJson() {
        return """
                {
                  "schema_version": 1,
                  "node_type": "inventory",
                  "node_type_aliases": ["cap.inventory", "cap.container", "inventory", "container", "slot", "hotbar", "item.id", "consume", "quick_move"],
                  "failure_types": [
                    {
                      "id": "inventory.item_not_found",
                      "label": "item not found",
                      "applicable_node_types": ["inventory"],
                      "suggested_fix_rules": ["verify_item_id", "expand_item_aliases", "scan_container_before_transfer"],
                      "auto_fix_rule": "inventory/recovery/item_not_found",
                      "patch_generator": "ktl.patch.inventory.item_not_found",
                      "learning_hook": "oli.feedback.inventory.item_not_found"
                    },
                    {
                      "id": "inventory.wrong_slot",
                      "label": "wrong slot",
                      "applicable_node_types": ["inventory"],
                      "suggested_fix_rules": ["validate_slot_index", "prefer_hotbar_selection_check", "confirm_screen_handler_slot"],
                      "auto_fix_rule": "inventory/recovery/wrong_slot",
                      "patch_generator": "ktl.patch.inventory.slot",
                      "learning_hook": "oli.feedback.inventory.slot"
                    },
                    {
                      "id": "inventory.not_selected",
                      "label": "not selected",
                      "applicable_node_types": ["inventory"],
                      "suggested_fix_rules": ["add_selected_item_confirmation", "retry_hotbar_select", "check_hand_state"],
                      "auto_fix_rule": "inventory/recovery/not_selected",
                      "patch_generator": "ktl.patch.inventory.select",
                      "learning_hook": "oli.feedback.inventory.select"
                    },
                    {
                      "id": "inventory.failed_transfer",
                      "label": "failed transfer",
                      "applicable_node_types": ["inventory"],
                      "suggested_fix_rules": ["compare_before_after_count", "wait_for_screen_sync", "retry_quick_move"],
                      "auto_fix_rule": "inventory/recovery/failed_transfer",
                      "patch_generator": "ktl.patch.inventory.transfer",
                      "learning_hook": "oli.feedback.inventory.transfer"
                    },
                    {
                      "id": "inventory.consume_failed",
                      "label": "consume failed",
                      "applicable_node_types": ["inventory"],
                      "suggested_fix_rules": ["verify_food_item", "increase_use_wait_ticks", "confirm_count_decreased"],
                      "auto_fix_rule": "inventory/recovery/consume_failed",
                      "patch_generator": "ktl.patch.inventory.consume",
                      "learning_hook": "oli.feedback.inventory.consume"
                    }
                  ]
                }
                """;
    }

    private static String combatJson() {
        return """
                {
                  "schema_version": 1,
                  "node_type": "combat",
                  "node_type_aliases": ["cap.combat", "cap.interaction.attack", "attack", "combat", "kill", "target_dead", "target death"],
                  "failure_types": [
                    {
                      "id": "combat.target_not_found",
                      "label": "target not found",
                      "applicable_node_types": ["combat"],
                      "suggested_fix_rules": ["expand_scan_radius", "verify_entity_alias", "refresh_cached_entity_ref"],
                      "auto_fix_rule": "combat/recovery/target_not_found",
                      "patch_generator": "ktl.patch.combat.target",
                      "learning_hook": "oli.feedback.combat.target"
                    },
                    {
                      "id": "combat.out_of_range",
                      "label": "out of range",
                      "applicable_node_types": ["combat"],
                      "suggested_fix_rules": ["move_closer_before_attack", "lower_attack_range_assumption", "face_target_before_attack"],
                      "auto_fix_rule": "combat/recovery/out_of_range",
                      "patch_generator": "ktl.patch.combat.range",
                      "learning_hook": "oli.feedback.combat.range"
                    },
                    {
                      "id": "combat.missed_attack",
                      "label": "missed attack",
                      "applicable_node_types": ["combat"],
                      "suggested_fix_rules": ["verify_crosshair_alignment", "add_attack_cooldown_wait", "retry_after_face_target"],
                      "auto_fix_rule": "combat/recovery/missed_attack",
                      "patch_generator": "ktl.patch.combat.missed",
                      "learning_hook": "oli.feedback.combat.missed"
                    },
                    {
                      "id": "combat.target_not_dead",
                      "label": "target not dead",
                      "applicable_node_types": ["combat"],
                      "suggested_fix_rules": ["confirm_entity_removed", "loop_until_dead", "refresh_target_between_hits"],
                      "auto_fix_rule": "combat/recovery/target_not_dead",
                      "patch_generator": "ktl.patch.combat.death_confirm",
                      "learning_hook": "oli.feedback.combat.death_confirm"
                    }
                  ]
                }
                """;
    }

    private static String blockJson() {
        return """
                {
                  "schema_version": 1,
                  "node_type": "block",
                  "node_type_aliases": ["break_block", "use_item_on_block", "cap.world.scan_blocks", "block", "mine", "place", "target.block", "face_block"],
                  "failure_types": [
                    {
                      "id": "block.not_found",
                      "label": "block not found",
                      "applicable_node_types": ["block"],
                      "suggested_fix_rules": ["expand_block_scan", "verify_block_alias", "include_vertical_scan"],
                      "auto_fix_rule": "block/recovery/not_found",
                      "patch_generator": "ktl.patch.block.not_found",
                      "learning_hook": "oli.feedback.block.not_found"
                    },
                    {
                      "id": "block.out_of_reach",
                      "label": "out of reach",
                      "applicable_node_types": ["block"],
                      "suggested_fix_rules": ["move_to_reach_before_interact", "face_block_center", "verify_reach_distance"],
                      "auto_fix_rule": "block/recovery/out_of_reach",
                      "patch_generator": "ktl.patch.block.reach",
                      "learning_hook": "oli.feedback.block.reach"
                    },
                    {
                      "id": "block.wrong_face",
                      "label": "wrong face",
                      "applicable_node_types": ["block"],
                      "suggested_fix_rules": ["select_interaction_face", "raytrace_visible_face", "retry_with_adjacent_face"],
                      "auto_fix_rule": "block/recovery/wrong_face",
                      "patch_generator": "ktl.patch.block.face",
                      "learning_hook": "oli.feedback.block.face"
                    },
                    {
                      "id": "block.break_failed",
                      "label": "break failed",
                      "applicable_node_types": ["block"],
                      "suggested_fix_rules": ["hold_break_until_state_change", "verify_tool_speed", "confirm_block_changed"],
                      "auto_fix_rule": "block/recovery/break_failed",
                      "patch_generator": "ktl.patch.block.break",
                      "learning_hook": "oli.feedback.block.break"
                    }
                  ]
                }
                """;
    }

    private static String uiJson() {
        return """
                {
                  "schema_version": 1,
                  "node_type": "ui",
                  "node_type_aliases": ["screen", "ui", "command", "raw.command", "condition", "eval.", "branch", "evaluator", "chat"],
                  "failure_types": [
                    {
                      "id": "ui.command_failed",
                      "label": "command failed",
                      "applicable_node_types": ["ui"],
                      "suggested_fix_rules": ["verify_command_string", "confirm_permission_level", "strip_duplicate_slash"],
                      "auto_fix_rule": "ui/recovery/command_failed",
                      "patch_generator": "ktl.patch.ui.command",
                      "learning_hook": "oli.feedback.ui.command"
                    },
                    {
                      "id": "ui.condition_wrong",
                      "label": "condition wrong",
                      "applicable_node_types": ["ui"],
                      "suggested_fix_rules": ["verify_evaluator_key", "capture_condition_inputs", "compare_threshold_binding"],
                      "auto_fix_rule": "ui/recovery/condition_wrong",
                      "patch_generator": "ktl.patch.ui.condition",
                      "learning_hook": "oli.feedback.ui.condition"
                    },
                    {
                      "id": "ui.screen_not_open",
                      "label": "screen not open",
                      "applicable_node_types": ["ui"],
                      "suggested_fix_rules": ["wait_for_screen", "confirm_open_target", "retry_interaction"],
                      "auto_fix_rule": "ui/recovery/screen_not_open",
                      "patch_generator": "ktl.patch.ui.screen",
                      "learning_hook": "oli.feedback.ui.screen"
                    },
                    {
                      "id": "ui.wrong_state",
                      "label": "wrong state",
                      "applicable_node_types": ["ui"],
                      "suggested_fix_rules": ["capture_runtime_state", "check_parent_child_state_merge", "verify_return_status"],
                      "auto_fix_rule": "ui/recovery/wrong_state",
                      "patch_generator": "ktl.patch.ui.state",
                      "learning_hook": "oli.feedback.ui.state"
                    }
                  ]
                }
                """;
    }

    private static final class FailureTypeFile {
        private int schema_version;
        private String node_type;
        private List<String> node_type_aliases;
        private List<AutomationFailureType> failure_types;
    }
}
