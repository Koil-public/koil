package com.spirit.koil.api.model;

import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.AutomationModeController;
import com.spirit.koil.api.automation.AutomationRouter;
import com.spirit.koil.api.model.tool.ModelWorkspaceRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.HitResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates the standardized preconditions that can be checked safely and
 * synchronously before a tool call. Preconditions requiring domain-specific
 * semantic work remain explicit deferred checks and are still enforced by the
 * authoritative registry/executor.
 */
public final class ToolPreconditionEvaluator {
    private ToolPreconditionEvaluator() {
    }

    public static Assessment evaluate(ModelToolDefinition definition, ModelToolCall call) {
        if (definition == null) return new Assessment(List.of("unknown_tool"), List.of());
        List<String> blockers = new ArrayList<>();
        List<String> deferred = new ArrayList<>();
        MinecraftClient client = MinecraftClient.getInstance();

        for (String precondition : definition.preconditions()) {
            if (precondition == null || precondition.isBlank()) continue;
            switch (precondition) {
                case "automation_mode_enabled" -> {
                    if (!AutomationModeController.isAutomationMode()) blockers.add("precondition:automation_mode_enabled");
                }
                case "client_available" -> {
                    if (client == null) blockers.add("precondition:client_available");
                }
                case "world_loaded" -> {
                    if (client == null || client.world == null) blockers.add("precondition:world_loaded");
                }
                case "player_available" -> {
                    if (client == null || client.player == null) blockers.add("precondition:player_available");
                }
                case "connection_available" -> {
                    if (client == null || client.getNetworkHandler() == null) blockers.add("precondition:connection_available");
                }
                case "command_tree_available" -> {
                    if (client == null || client.getNetworkHandler() == null
                            || client.getNetworkHandler().getCommandDispatcher() == null) {
                        blockers.add("precondition:command_tree_available");
                    }
                }
                case "no_conflicting_automation" -> {
                    if (AutomationRouter.isTaskRunning()) blockers.add("precondition:no_conflicting_automation");
                }
                case "automation_running" -> {
                    if (!AutomationRouter.isTaskRunning()) blockers.add("precondition:automation_running");
                }
                case "project_workspace_available" -> {
                    if (!ModelWorkspaceRegistry.workspaces().containsKey("project")) {
                        blockers.add("precondition:project_workspace_available");
                    }
                }
                case "named_workspace_available" -> {
                    Map<String, ModelWorkspaceRegistry.Workspace> workspaces = ModelWorkspaceRegistry.workspaces();
                    String requested = string(call == null ? null : call.arguments(), "workspace");
                    String resolved = requested.isBlank()
                            ? ModelWorkspaceRegistry.preferredCodeWorkspaceId("", workspaces)
                            : requested;
                    if (resolved == null || resolved.isBlank() || !workspaces.containsKey(resolved)) {
                        blockers.add("precondition:named_workspace_available");
                    }
                }
                case "path_inside_named_workspace" -> validateWorkspacePaths(definition, call, blockers);
                case "target_available" -> {
                    if (client == null || client.crosshairTarget == null
                            || client.crosshairTarget.getType() == HitResult.Type.MISS) {
                        blockers.add("precondition:target_available");
                    }
                }
                case "public_network_available",
                        "player_command_permission",
                        "explicit_player_confirmation",
                        "ktl_registry_loaded",
                        "bundled_koil_knowledge_available",
                        "item_available",
                        "target_hit",
                        "target_rideable",
                        "boat_or_verified_recipe_available",
                        "usable_elytra_available",
                        "placement_item_available",
                        "registry_subject_to_advancement_references",
                        "artifact_reference_to_registry_subject",
                        "crafting_path" -> deferred.add(precondition);
                default -> deferred.add(precondition);
            }
        }
        return new Assessment(List.copyOf(blockers), List.copyOf(deferred));
    }

    private static void validateWorkspacePaths(
            ModelToolDefinition definition,
            ModelToolCall call,
            List<String> blockers
    ) {
        if (call == null) return;
        JsonObject arguments = call.arguments();
        String workspace = string(arguments, "workspace");
        String path = string(arguments, "path");
        boolean forWrite = !definition.sideEffects().isEmpty();
        try {
            ModelWorkspaceRegistry.inspect(workspace, path, forWrite);
        } catch (IOException invalid) {
            blockers.add("precondition:path_inside_named_workspace:" + compact(invalid.getMessage()));
        }
        String destinationPath = string(arguments, "destinationPath");
        if (!destinationPath.isBlank()) {
            String destinationWorkspace = string(arguments, "destinationWorkspace");
            if (destinationWorkspace.isBlank()) destinationWorkspace = workspace;
            try {
                ModelWorkspaceRegistry.inspect(destinationWorkspace, destinationPath, true);
            } catch (IOException invalid) {
                blockers.add("precondition:destination_inside_named_workspace:" + compact(invalid.getMessage()));
            }
        }
    }

    private static String string(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString().strip()
                    : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String compact(String value) {
        String clean = value == null ? "unavailable" : value.replaceAll("\\s+", " ").strip();
        return clean.length() <= 160 ? clean : clean.substring(0, 159) + "…";
    }

    public record Assessment(List<String> blockers, List<String> deferred) {
        public Assessment {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            deferred = deferred == null ? List.of() : List.copyOf(deferred);
        }

        public boolean blocked() {
            return !this.blockers.isEmpty();
        }

        public boolean fullyEvaluated() {
            return this.deferred.isEmpty();
        }
    }
}
