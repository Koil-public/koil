package com.spirit.koil.api.model.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.automation.capability.AutomationCapabilityException;
import com.spirit.koil.api.automation.capability.AutomationCapabilityRegistry;
import com.spirit.koil.api.automation.ktl.AutomationKtlSkillRegistry;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.ModelToolResult;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only structured planning boundary. It validates named capability steps
 * but never executes them and never treats assistant prose as an action.
 */
public final class AutomationPlanModelToolRegistry {
    public static final String TOOL_ID = "automation.plan";
    private static final int MAXIMUM_STEPS = 24;
    private static final ModelToolDefinition DEFINITION = new ModelToolDefinition(
            TOOL_ID,
            "Create and validate a bounded structured plan for a complex objective. Use canonical dotted Koil tool identifiers inside steps and concrete arguments. This tool does not execute any step or grant approval.",
            schema(),
            List.of("automation_mode_enabled"),
            Set.of(),
            true,
            Duration.ofSeconds(5),
            false,
            false,
            Set.of("completed", "failed")
    );

    private AutomationPlanModelToolRegistry() {
    }

    public static String version() {
        return "automation-plan-tool-v3";
    }

    public static List<ModelToolDefinition> modelTools() {
        return List.of(DEFINITION);
    }

    public static boolean supports(String toolId) {
        return TOOL_ID.equals(toolId);
    }

    public static CompletableFuture<ModelToolResult> execute(ModelToolCall call) {
        if (call == null || !supports(call.toolId())) {
            return CompletableFuture.completedFuture(failure(call, "unknown_tool", "Unknown planning tool."));
        }
        JsonObject arguments = call.arguments();
        String objective = string(arguments, "objective");
        if (objective.isBlank()) {
            return CompletableFuture.completedFuture(failure(call, "invalid_plan", "A plan objective is required."));
        }
        if (!arguments.has("steps") || !arguments.get("steps").isJsonArray()) {
            return CompletableFuture.completedFuture(failure(call, "invalid_plan", "Plan steps must be an array."));
        }
        JsonArray inputSteps = arguments.getAsJsonArray("steps");
        if (inputSteps.isEmpty() || inputSteps.size() > MAXIMUM_STEPS) {
            return CompletableFuture.completedFuture(failure(
                    call,
                    "invalid_plan",
                    "A plan requires between 1 and " + MAXIMUM_STEPS + " steps."
            ));
        }

        Set<String> knownTools = knownToolIds();
        JsonArray validatedSteps = new JsonArray();
        String planId = "kap-" + UUID.randomUUID().toString().substring(0, 12);
        Set<String> stepIds = new LinkedHashSet<>();
        for (int index = 0; index < inputSteps.size(); index++) {
            JsonElement element = inputSteps.get(index);
            if (!element.isJsonObject()) {
                return CompletableFuture.completedFuture(failure(
                        call, "invalid_plan", "Plan step " + (index + 1) + " must be an object."
                ));
            }
            JsonObject step = element.getAsJsonObject();
            String suppliedToolId = string(step, "toolId");
            String toolId = canonicalPlanToolId(suppliedToolId, knownTools);
            if (toolId.isBlank() || TOOL_ID.equals(toolId) || !knownTools.contains(toolId)) {
                return CompletableFuture.completedFuture(failure(
                        call,
                        "unknown_plan_tool",
                        "Plan step " + (index + 1) + " references an unavailable tool: " + suppliedToolId
                ));
            }
            JsonObject toolArguments = step.has("arguments") && step.get("arguments").isJsonObject()
                    ? step.getAsJsonObject("arguments")
                    : new JsonObject();
            if (AutomationCapabilityRegistry.definitions().containsKey(toolId)) {
                try {
                    AutomationCapabilityRegistry.validateAndCompile(toolId, toolArguments, UUID.randomUUID());
                } catch (AutomationCapabilityException failure) {
                    return CompletableFuture.completedFuture(failure(
                            call,
                            failure.code(),
                            "Plan step " + (index + 1) + " is invalid: " + failure.getMessage()
                    ));
                } catch (RuntimeException failure) {
                    return CompletableFuture.completedFuture(failure(
                            call,
                            "invalid_plan_arguments",
                            "Plan step " + (index + 1) + " is invalid: " + message(failure)
                    ));
                }
            } else if (AutomationKtlSkillModelToolRegistry.RUN_TOOL_ID.equals(toolId)) {
                try {
                    AutomationKtlSkillRegistry.prepare(
                            string(toolArguments, "skill"),
                            toolArguments.has("parameters") && toolArguments.get("parameters").isJsonObject()
                                    ? toolArguments.getAsJsonObject("parameters")
                                    : new JsonObject(),
                            UUID.randomUUID()
                    );
                } catch (AutomationCapabilityException failure) {
                    return CompletableFuture.completedFuture(failure(
                            call,
                            failure.code(),
                            "Plan step " + (index + 1) + " is invalid: " + failure.getMessage()
                    ));
                }
            }
            JsonObject validated = new JsonObject();
            String stepId = planId + "-step-" + (index + 1);
            stepIds.add(stepId);
            validated.addProperty("stepId", stepId);
            validated.addProperty("index", index + 1);
            validated.addProperty("toolId", toolId);
            validated.add("arguments", toolArguments.deepCopy());
            validated.addProperty("reason", string(step, "reason"));
            String validation = string(step, "validationRequirement");
            if (validation.isBlank()) {
                validation = AutomationCapabilityRegistry.definitions().containsKey(toolId)
                    ? "schema_validated"
                    : "tool_registered";
            }
            validated.addProperty("validation", validation);
            validated.addProperty("validationRequirement", validation);
            validated.addProperty("expectedObservation", string(step, "expectedObservation"));
            validated.addProperty("sideEffectClassification",
                    LocalModelToolCatalog.requiresFreshApproval(toolId) ? "side_effect" : "read_only");
            JsonArray dependencies = new JsonArray();
            if (index > 0) {
                dependencies.add(planId + "-step-" + index);
            }
            validated.add("dependencies", dependencies);
            validatedSteps.add(validated);
        }

        JsonObject output = new JsonObject();
        output.addProperty("planId", planId);
        output.addProperty("objective", objective);
        output.addProperty("stepCount", validatedSteps.size());
        output.addProperty("executable", true);
        output.addProperty("executed", false);
        output.add("steps", validatedSteps);
        return CompletableFuture.completedFuture(new ModelToolResult(
                call.id(),
                call.toolId(),
                "completed",
                output,
                "",
                "The structured plan is valid. No plan step was executed."
        ));
    }

    private static Set<String> knownToolIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>(AutomationCapabilityRegistry.definitions().keySet());
        MinecraftKnowledgeModelToolRegistry.modelTools().forEach(tool -> ids.add(tool.id()));
        ModelWorkspaceToolRegistry.modelTools().forEach(tool -> ids.add(tool.id()));
        AutomationKtlSkillModelToolRegistry.modelTools().forEach(tool -> ids.add(tool.id()));
        ProjectValidationModelToolRegistry.modelTools().forEach(tool -> ids.add(tool.id()));
        return Set.copyOf(ids);
    }

    /**
     * Providers may expose dotted Koil function names through a restricted
     * wire alphabet. Compact models sometimes copy that wire-safe spelling
     * into automation.plan's nested toolId. Resolve it only when it maps to
     * exactly one registered capability; ambiguous or invented names remain
     * invalid.
     */
    private static String canonicalPlanToolId(String supplied, Set<String> knownTools) {
        String clean = supplied == null ? "" : supplied.strip();
        if (clean.isBlank() || knownTools.contains(clean)) return clean;
        String match = "";
        for (String candidate : knownTools) {
            if (!wireSafe(candidate).equals(clean)) continue;
            if (!match.isBlank() && !match.equals(candidate)) return clean;
            match = candidate;
        }
        return match.isBlank() ? clean : match;
    }

    private static String wireSafe(String canonical) {
        StringBuilder safe = new StringBuilder(canonical == null ? 0 : canonical.length());
        if (canonical != null) {
            for (int index = 0; index < canonical.length(); index++) {
                char value = canonical.charAt(index);
                safe.append((value >= 'a' && value <= 'z')
                                || (value >= 'A' && value <= 'Z')
                                || (value >= '0' && value <= '9')
                                || value == '_'
                                || value == '-'
                        ? value
                        : '_');
            }
        }
        return safe.toString();
    }

    private static JsonObject schema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject();

        JsonObject objective = new JsonObject();
        objective.addProperty("type", "string");
        objective.addProperty("minLength", 1);
        objective.addProperty("maxLength", 1_024);
        properties.add("objective", objective);

        JsonObject steps = new JsonObject();
        steps.addProperty("type", "array");
        steps.addProperty("minItems", 1);
        steps.addProperty("maxItems", MAXIMUM_STEPS);
        JsonObject step = new JsonObject();
        step.addProperty("type", "object");
        step.addProperty("additionalProperties", false);
        JsonObject stepProperties = new JsonObject();
        JsonObject toolId = new JsonObject();
        toolId.addProperty("type", "string");
        toolId.addProperty("description", "Canonical dotted Koil tool id for this step, such as movement.walk_relative or player.jump.");
        toolId.addProperty("minLength", 1);
        toolId.addProperty("maxLength", 96);
        stepProperties.add("toolId", toolId);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("type", "object");
        stepProperties.add("arguments", arguments);
        JsonObject reason = new JsonObject();
        reason.addProperty("type", "string");
        reason.addProperty("maxLength", 240);
        stepProperties.add("reason", reason);
        JsonObject expectedObservation = new JsonObject();
        expectedObservation.addProperty("type", "string");
        expectedObservation.addProperty("maxLength", 320);
        stepProperties.add("expectedObservation", expectedObservation);
        JsonObject validationRequirement = new JsonObject();
        validationRequirement.addProperty("type", "string");
        validationRequirement.addProperty("maxLength", 96);
        stepProperties.add("validationRequirement", validationRequirement);
        step.add("properties", stepProperties);
        JsonArray stepRequired = new JsonArray();
        stepRequired.add("toolId");
        step.add("required", stepRequired);
        steps.add("items", step);
        properties.add("steps", steps);

        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("objective");
        required.add("steps");
        schema.add("required", required);
        return schema;
    }

    private static String string(JsonObject object, String key) {
        try {
            return object != null && object.has(key) ? object.get(key).getAsString().strip() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String message(Throwable failure) {
        return failure == null || failure.getMessage() == null
                ? "unknown validation failure"
                : failure.getMessage();
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
