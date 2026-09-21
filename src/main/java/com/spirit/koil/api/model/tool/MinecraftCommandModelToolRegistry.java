package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.command.MinecraftCommandInspector;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.ToolExecutionPolicy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Provider-neutral, read-only command-tree tools for model planning. */
public final class MinecraftCommandModelToolRegistry {
    public static final String INSPECT_TOOL_ID = "minecraft.command_inspect";
    public static final String HELP_TOOL_ID = "minecraft.command_help";

    private static final ModelToolDefinition INSPECT = new ModelToolDefinition(
            INSPECT_TOOL_ID,
            "Validate one drafted Minecraft command against the live Brigadier tree and return exact completions. Read-only; works with vanilla and server-advertised modded/datapack commands.",
            commandSchema(),
            List.of("world_loaded", "connection_available", "command_tree_available"),
            Set.of(),
            true,
            Duration.ofSeconds(5),
            false,
            false,
            Set.of("completed", "failed"),
            ToolExecutionPolicy.readOnly(
                    ToolExecutionPolicy.FreshnessMode.CONNECTION,
                    ToolExecutionPolicy.CostClass.CHEAP
            )
    );

    private static final ModelToolDefinition HELP = new ModelToolDefinition(
            HELP_TOOL_ID,
            "Discover how to use a Minecraft command from the live Brigadier tree. Search by command/root name or short intent; returns matching vanilla/modded roots, bounded syntax paths, and completions. Read-only. Use when the command name or syntax is uncertain.",
            querySchema(),
            List.of("world_loaded", "connection_available", "command_tree_available"),
            Set.of(),
            true,
            Duration.ofSeconds(5),
            false,
            false,
            Set.of("completed", "failed"),
            ToolExecutionPolicy.readOnly(
                    ToolExecutionPolicy.FreshnessMode.CONNECTION,
                    ToolExecutionPolicy.CostClass.CHEAP
            )
    );

    private MinecraftCommandModelToolRegistry() {
    }

    public static String version() {
        return "minecraft-command-inspector-v2";
    }

    public static List<ModelToolDefinition> modelTools() {
        return List.of(INSPECT, HELP);
    }

    public static boolean supports(String toolId) {
        return INSPECT_TOOL_ID.equals(toolId) || HELP_TOOL_ID.equals(toolId);
    }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        if (call == null || !supports(call.toolId())) {
            return CompletableFuture.completedFuture(failure(call, "unknown_tool", "Unknown command inspection tool."));
        }
        if (HELP_TOOL_ID.equals(call.toolId())) {
            String query;
            try {
                query = call.arguments().has("query") ? call.arguments().get("query").getAsString() : "";
            } catch (RuntimeException failure) {
                return CompletableFuture.completedFuture(failure(call, "invalid_arguments", "The query argument must be text."));
            }
            if (query.isBlank()) {
                return CompletableFuture.completedFuture(failure(call, "invalid_arguments", "A command name or short intent query is required."));
            }
            return MinecraftCommandInspector.discover(query).thenApply(discovery -> {
                JsonObject output = new JsonObject();
                output.addProperty("query", discovery.query());
                JsonArray roots = new JsonArray();
                discovery.matchingRoots().forEach(roots::add);
                output.add("matchingRoots", roots);
                JsonArray syntax = new JsonArray();
                discovery.syntax().forEach(syntax::add);
                output.add("syntax", syntax);
                JsonArray completions = new JsonArray();
                discovery.completions().forEach(completions::add);
                output.add("completions", completions);
                output.addProperty("note", discovery.note());
                return new ModelToolResult(
                        call.id(), call.toolId(), "completed", output, "",
                        discovery.matchingRoots().isEmpty()
                                ? "No close live command root matched; refine the query."
                                : "Use one returned live syntax path as the basis for minecraft.command, validating the final draft first when arguments are uncertain."
                );
            });
        }

        String command;
        try {
            command = call.arguments().has("command")
                    ? call.arguments().get("command").getAsString()
                    : "";
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(failure(call, "invalid_arguments", "The command argument must be text."));
        }
        return MinecraftCommandInspector.inspect(command).thenApply(inspection -> {
            JsonObject output = new JsonObject();
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
            return new ModelToolResult(
                    call.id(),
                    call.toolId(),
                    "completed",
                    output,
                    "",
                    inspection.executable()
                            ? "The command is executable according to the active command tree."
                            : "The command is not executable as written; use the returned problem and suggestions to repair it."
            );
        });
    }

    private static JsonObject commandSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject();
        JsonObject command = new JsonObject();
        command.addProperty("type", "string");
        command.addProperty("minLength", 1);
        command.addProperty("maxLength", 2_048);
        properties.add("command", command);
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("command");
        schema.add("required", required);
        return schema;
    }

    private static JsonObject querySchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject();
        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("minLength", 1);
        query.addProperty("maxLength", 2_048);
        properties.add("query", query);
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("query");
        schema.add("required", required);
        return schema;
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
