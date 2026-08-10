package com.spirit.koil.api.automation.capability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for deterministic KTL executor primitives.
 *
 * These low-level primitives are intentionally not model tools. Model-facing
 * capabilities compile validated high-level intents into KTL plans that may
 * use these primitives internally.
 */
public final class AutomationPrimitiveRegistry {
    private static final Map<String, AutomationPrimitiveDefinition> DEFINITIONS = build();

    private AutomationPrimitiveRegistry() {
    }

    public static boolean contains(String id) {
        return id != null && DEFINITIONS.containsKey(id);
    }

    public static AutomationPrimitiveDefinition require(String id) {
        AutomationPrimitiveDefinition definition = DEFINITIONS.get(id);
        if (definition == null) {
            throw new IllegalArgumentException("unknown automation primitive: " + id);
        }
        return definition;
    }

    public static Set<String> ids() {
        return DEFINITIONS.keySet();
    }

    public static Map<String, AutomationPrimitiveDefinition> definitions() {
        return DEFINITIONS;
    }

    public static String version() {
        return "automation-primitives-v1:" + Integer.toHexString(DEFINITIONS.keySet().hashCode());
    }

    private static Map<String, AutomationPrimitiveDefinition> build() {
        Map<String, AutomationPrimitiveDefinition> definitions = new LinkedHashMap<>();
        for (String id : Set.of(
                "cap.combat.attack_target", "cap.combat.attack_until_dead", "cap.combat.confirm_target_death", "cap.command.execute_raw", "cap.container.find_item_in_open_screen",
                "cap.container.quick_move_selected_hotbar", "cap.container.quick_move_slot", "cap.input.mouse_delta", "cap.input.press_key",
                "cap.input.release_all", "cap.input.release_key", "cap.input.tap_key", "cap.build.resolve_exact_target", "cap.build.resolve_pattern_target", "cap.interaction.attack_target",
                "cap.interaction.break_block_target", "cap.interaction.close_screen", "cap.interaction.consume_selected_item", "cap.interaction.interact_entity_target",
                "cap.interaction.interact_target", "cap.interaction.open_target", "cap.interaction.stop_using_item", "cap.interaction.use_item",
                "cap.interaction.use_item_on_block_target", "cap.interaction.use_item_on_current_block", "cap.interaction.use_item_on_entity_target", "cap.interaction.use_main_hand_item",
                "cap.interaction.use_off_hand_item", "cap.interaction.use_selected_item", "cap.interaction.place_block_target", "cap.goal.execute_named", "cap.inventory.consume_item", "cap.inventory.count_item",
                "cap.inventory.drop_selected_item", "cap.inventory.equip_item", "cap.inventory.has_item", "cap.inventory.open_inventory_screen", "cap.inventory.require_count",
                "cap.inventory.select_hotbar_item", "cap.look.face_position", "cap.look.face_target", "cap.look.verify_target", "cap.look.face_target_horizontal",
                "cap.look.face_block_center", "cap.look.face_block_face", "cap.look.face_movement_direction", "cap.look.face_parkour_landing",
                "cap.look.set_pitch", "cap.look.set_yaw", "cap.look.turn_pitch", "cap.look.turn_relative", "cap.look.turn_yaw",
                "cap.movement.check_progress", "cap.movement.check_safety", "cap.movement.choose_recovery", "cap.movement.release_all",
                "cap.movement.run_recovery", "cap.movement.set_backward", "cap.movement.set_forward", "cap.movement.set_jump",
                "cap.movement.set_left_strafe", "cap.movement.set_right_strafe", "cap.movement.set_sneak", "cap.movement.set_sprint",
                "cap.movement.snapshot", "cap.movement.stop", "cap.movement.timed_jump", "cap.movement.walk_relative",
                "cap.parkour.analyze_jump", "cap.parkour.execute_jump", "cap.path.compute_relative_target", "cap.path.follow_target",
                "cap.path.move_relative_verified", "cap.path.move_to_target", "cap.path.plan_local", "cap.path.replan_local_segment", "cap.path.resolve_target", "cap.path.verify_relative_arrival", "cap.path.verify_target_arrival",
                "cap.player.crouch", "cap.player.dismount", "cap.player.jump", "cap.player.sprint", "cap.player.uncrouch",
                "cap.player.unsprint", "cap.report.error_line", "cap.report.say", "cap.report.status_line",
                "cap.transport.mount_target", "cap.transport.verify_mounted", "cap.transport.verify_dismounted",
                "cap.transport.resolve_boat_target", "cap.transport.prepare_boat", "cap.transport.deploy_boat", "cap.transport.verify_boat_mounted",
                "cap.transport.prepare_elytra", "cap.transport.fly_elytra", "cap.transport.verify_elytra_arrival",
                "cap.state.capture_player_position", "cap.state.copy_value", "cap.state.decrement_counter", "cap.state.increment_counter",
                "cap.state.read_stat", "cap.state.remove_memory", "cap.state.set_counter", "cap.state.write_memory",
                "cap.wait.ticks", "cap.world.inspect_surroundings", "cap.world.scan_blocks", "cap.world.scan_entities", "cap.world.scan_players", "cap.world.verify_block_target",
                "cap.world.scan_target", "cap.world.snapshot_target_count", "cap.world.target_in_range", "cap.world.validate_target"
        )) {
            String category = category(id);
            definitions.put(id, new AutomationPrimitiveDefinition(
                    id,
                    category,
                    humanize(id.substring(id.lastIndexOf('.') + 1)),
                    true,
                    true,
                    false
            ));
        }
        return Map.copyOf(definitions);
    }

    private static String category(String id) {
        String[] parts = id.split("\\.");
        return parts.length > 1 ? parts[1] : "automation";
    }

    private static String humanize(String value) {
        return value.replace('_', ' ');
    }
}
