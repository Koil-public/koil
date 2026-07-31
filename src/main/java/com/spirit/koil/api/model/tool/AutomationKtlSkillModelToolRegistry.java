package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.capability.AutomationCapabilityException;
import com.spirit.koil.api.automation.ktl.AutomationKtlSkillRegistry;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Small model-facing adapter over the compiled KTL skill registry.
 */
public final class AutomationKtlSkillModelToolRegistry {
    public static final String CATALOG_TOOL_ID = "automation.skill_catalog";
    public static final String RUN_TOOL_ID = "automation.skill_run";
    private static final List<ModelToolDefinition> TOOLS = List.of(
            new ModelToolDefinition(
                    CATALOG_TOOL_ID,
                    "Search or inspect registered KTL task skills. Search before running an unfamiliar skill. Results describe composed KTL tasks and delegate tasks; they never expose Java run primitives as callable model tools.",
                    catalogSchema(),
                    List.of("automation_mode_enabled", "ktl_registry_loaded"),
                    Set.of(),
                    true,
                    Duration.ofSeconds(5),
                    false,
                    false,
                    Set.of("completed", "failed")
            ),
            new ModelToolDefinition(
                    RUN_TOOL_ID,
                    "Run one exact registered KTL task skill with validated parameters. Use only a skill id returned by automation.skill_catalog. The KTL task may compose delegates and Java primitives internally; the model cannot call those primitives directly.",
                    runSchema(),
                    List.of("automation_mode_enabled", "world_loaded", "player_available", "ktl_registry_loaded"),
                    Set.of("executes_registered_ktl", "may_control_player_or_game_ui"),
                    false,
                    Duration.ofMinutes(10),
                    true,
                    true,
                    Set.of("completed", "blocked", "cancelled", "failed", "timed_out")
            )
    );

    private AutomationKtlSkillModelToolRegistry() {
    }

    public static String version() {
        return "automation-ktl-skill-tools-v1";
    }

    public static List<ModelToolDefinition> modelTools() {
        return TOOLS;
    }

    public static boolean supportsCatalog(String toolId) {
        return CATALOG_TOOL_ID.equals(toolId);
    }

    public static boolean supportsRun(String toolId) {
        return RUN_TOOL_ID.equals(toolId);
    }

    public static CompletableFuture<ModelToolResult> executeCatalog(ModelToolCall call) {
        try {
            String operation = string(call.arguments(), "operation");
            if ("search".equals(operation)) {
                String query = string(call.arguments(), "query");
                int limit = integer(call.arguments(), "limit", 12);
                JsonArray skills = new JsonArray();
                AutomationKtlSkillRegistry.search(query, limit)
                        .forEach(descriptor -> skills.add(summary(descriptor)));
                JsonObject output = new JsonObject();
                output.addProperty("query", query);
                output.addProperty("resultCount", skills.size());
                output.add("skills", skills);
                return completed(call, output, "Registered KTL skills were searched.");
            }
            if ("inspect".equals(operation)) {
                AutomationKtlSkillRegistry.SkillDescriptor descriptor =
                        AutomationKtlSkillRegistry.inspect(string(call.arguments(), "skill"));
                JsonObject output = summary(descriptor);
                JsonArray delegates = new JsonArray();
                descriptor.delegates().forEach(delegates::add);
                output.add("delegates", delegates);
                return completed(call, output, "The registered KTL skill contract was inspected.");
            }
            return CompletableFuture.completedFuture(failure(
                    call,
                    "invalid_skill_catalog_operation",
                    "operation must be search or inspect."
            ));
        } catch (AutomationCapabilityException exception) {
            return CompletableFuture.completedFuture(failure(call, exception.code(), exception.getMessage()));
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(failure(call, "skill_catalog_failed", message(exception)));
        }
    }

    private static JsonObject summary(AutomationKtlSkillRegistry.SkillDescriptor descriptor) {
        JsonObject output = new JsonObject();
        output.addProperty("skill", descriptor.id());
        output.addProperty("semanticOperation", descriptor.semanticOperation());
        output.addProperty("stepCount", descriptor.stepCount());
        output.addProperty("description", descriptor.description());
        output.addProperty("timeoutTicks", descriptor.timeoutTicks());
        output.addProperty("failurePolicy", descriptor.failurePolicy());
        output.addProperty("recoveryTask", descriptor.recoveryTask());
        addStrings(output, "requiredParameters", descriptor.requiredParameters());
        addStrings(output, "optionalParameters", descriptor.optionalParameters());
        addStrings(output, "tags", descriptor.tags());
        addStrings(output, "targetKinds", descriptor.targetKinds());
        addStrings(output, "resourceLocks", descriptor.resourceLocks());
        addStrings(output, "sideEffects", descriptor.sideEffects());
        return output;
    }

    private static void addStrings(JsonObject output, String key, List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        output.add(key, array);
    }

    private static JsonObject catalogSchema() {
        JsonObject schema = objectSchema();
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.add("operation", enumString("search", "inspect"));
        properties.add("query", string(0, 160));
        properties.add("skill", string(1, 180));
        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("minimum", 1);
        limit.addProperty("maximum", 24);
        properties.add("limit", limit);
        require(schema, "operation");
        return schema;
    }

    private static JsonObject runSchema() {
        JsonObject schema = objectSchema();
        JsonObject properties = schema.getAsJsonObject("properties");
        properties.add("skill", string(1, 180));
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.addProperty("maxProperties", 48);
        properties.add("parameters", parameters);
        require(schema, "skill");
        return schema;
    }

    private static JsonObject objectSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        schema.add("properties", new JsonObject());
        return schema;
    }

    private static JsonObject enumString(String... values) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        JsonArray choices = new JsonArray();
        for (String value : values) {
            choices.add(value);
        }
        schema.add("enum", choices);
        return schema;
    }

    private static JsonObject string(int minimum, int maximum) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "string");
        schema.addProperty("minLength", minimum);
        schema.addProperty("maxLength", maximum);
        return schema;
    }

    private static void require(JsonObject schema, String... keys) {
        JsonArray required = new JsonArray();
        for (String key : keys) {
            required.add(key);
        }
        schema.add("required", required);
    }

    private static String string(JsonObject object, String key) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsString().strip() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static CompletableFuture<ModelToolResult> completed(
            ModelToolCall call,
            JsonObject output,
            String detail
    ) {
        return CompletableFuture.completedFuture(new ModelToolResult(
                call.id(), call.toolId(), "completed", output, "", detail
        ));
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

    private static String message(Throwable failure) {
        return failure == null || failure.getMessage() == null
                ? "unknown KTL skill failure"
                : failure.getMessage();
    }
}
