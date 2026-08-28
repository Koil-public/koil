package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.model.ModelActivityState;
import com.spirit.koil.api.model.ModelToolCall;

import java.util.Locale;

/**
 * Typed semantic projection for a registered model tool while it is running.
 * This is presentation metadata only; it never decides whether a tool may run.
 */
public final class ModelToolActivityPresentation {
    private ModelToolActivityPresentation() {
    }

    public static Activity activity(ModelToolCall call) {
        String toolId = call == null ? "" : call.toolId().strip().toLowerCase(Locale.ROOT);
        String summary = ModelToolCallPresentation.callSummary(call);
        return activity(toolId, summary);
    }

    public static Activity activity(String toolId, String detail) {
        String normalizedToolId = toolId == null ? "" : toolId.strip().toLowerCase(Locale.ROOT);
        String summary = detail == null ? "" : detail.strip();
        if (summary.isBlank()) summary = ModelToolCallPresentation.toolName(normalizedToolId);
        return new Activity(state(normalizedToolId), summary.isBlank() ? "Action" : summary);
    }

    private static ModelActivityState state(String toolId) {
        if (toolId.isBlank()) return ModelActivityState.EXECUTING;
        if (contains(toolId, "replan", "recover", "repair")) return ModelActivityState.REPLANNING;
        if (contains(toolId, "proof", "test")) return ModelActivityState.TESTING;
        if (contains(toolId, "validation", "validate")) return ModelActivityState.VALIDATING;
        if (toolId.equals("automation.plan") || toolId.endsWith(".plan")) return ModelActivityState.PLANNING;
        if (contains(toolId, "internet.search", ".search", "skill_catalog")) return ModelActivityState.SEARCHING;
        if (contains(toolId, "documentation", "internet.fetch", ".read")) return ModelActivityState.READING;
        if (contains(toolId, "knowledge", "command_syntax", "inspect", ".info", ".state", ".list", ".stat", ".roots")) {
            return ModelActivityState.INSPECTING;
        }
        if (contains(toolId, "workspace.write", "workspace.append", "workspace.create", "workspace.mkdir",
                "workspace.copy", "workspace.move", "workspace.delete", "workspace.edit")) {
            return ModelActivityState.EDITING;
        }
        if (contains(toolId, "elytra", "glid")) return ModelActivityState.GLIDING;
        if (contains(toolId, "boat", "mount", "dismount", "rid")) return ModelActivityState.RIDING;
        if (contains(toolId, "swim")) return ModelActivityState.SWIMMING;
        if (contains(toolId, "climb", "ladder", "vine")) return ModelActivityState.CLIMBING;
        if (contains(toolId, "parkour", "jump")) return ModelActivityState.PARKOUR;
        if (contains(toolId, "sprint")) return ModelActivityState.SPRINTING;
        if (contains(toolId, "movement", "move_to", "walk", "navigate", "travel")) return ModelActivityState.NAVIGATING;
        if (contains(toolId, "look", "orient", "camera")) return ModelActivityState.ORIENTING;
        if (contains(toolId, "eat")) return ModelActivityState.EATING;
        if (contains(toolId, "use_item")) return ModelActivityState.USING_ITEM;
        if (contains(toolId, "mine", "break_block")) return ModelActivityState.MINING;
        if (contains(toolId, "build", "place")) return ModelActivityState.BUILDING;
        if (contains(toolId, "attack", "kill", "combat")) return ModelActivityState.ATTACKING;
        if (contains(toolId, "interact", "container", "inventory")) return ModelActivityState.INTERACTING;
        return ModelActivityState.EXECUTING;
    }

    private static boolean contains(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    public record Activity(ModelActivityState state, String detail) {
        public Activity {
            state = state == null ? ModelActivityState.EXECUTING : state;
            detail = detail == null ? "" : detail;
        }
    }
}
