package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.command.MinecraftCommandInspector;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Provider-neutral, read-only command-tree tools for model planning. */
public final class MinecraftCommandModelToolRegistry {
    public static final String INSPECT_TOOL_ID = "minecraft.command_inspect";
    private static final ModelToolDefinition INSPECT = new ModelToolDefinition(
            INSPECT_TOOL_ID,
            "Validate one Minecraft command against the active connection's command tree and return exact completion suggestions. This does not execute the command.",
            schema(),
            List.of("world_loaded", "connection_available", "command_tree_available"),
            Set.of(),
            true,
            Duration.ofSeconds(5),
            false,
            false,
            Set.of("completed", "failed")
    );

    private MinecraftCommandModelToolRegistry() {
    }

    public static String version() {
        return "minecraft-command-inspector-v1";
    }

    public static List<ModelToolDefinition> modelTools() {
        return List.of(INSPECT);
    }

    public static boolean supports(String toolId) {
        return INSPECT_TOOL_ID.equals(toolId);
    }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        if (call == null || !supports(call.toolId())) {
            return CompletableFuture.completedFuture(failure(call, "unknown_tool", "Unknown command inspection tool."));
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

    private static JsonObject schema() {
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
