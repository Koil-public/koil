package com.spirit.koil.api.model.planning;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;
import com.spirit.koil.api.model.chat.ModelGenerationHudState;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable request-session copy of a schema-validated automation plan.
 */
public record ValidatedAutomationPlan(
        String id,
        String objective,
        List<Step> steps
) {
    public ValidatedAutomationPlan {
        id = id == null ? "" : id.strip();
        objective = objective == null ? "" : objective.strip();
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (id.isBlank() || steps.isEmpty()) {
            throw new IllegalArgumentException("A validated plan requires an id and at least one step.");
        }
    }

    public static ValidatedAutomationPlan from(ModelToolResult result) {
        if (result == null || !"completed".equals(result.status())) {
            throw new IllegalArgumentException("Planning did not return a completed result.");
        }
        JsonObject output = result.output();
        String planId = string(output, "planId");
        String objective = string(output, "objective");
        JsonArray values = output.has("steps") && output.get("steps").isJsonArray()
                ? output.getAsJsonArray("steps")
                : new JsonArray();
        List<Step> steps = new ArrayList<>();
        for (JsonElement value : values) {
            if (!value.isJsonObject()) {
                continue;
            }
            JsonObject step = value.getAsJsonObject();
            String toolId = string(step, "toolId");
            if (toolId.isBlank()) {
                continue;
            }
            JsonObject arguments = step.has("arguments") && step.get("arguments").isJsonObject()
                    ? step.getAsJsonObject("arguments")
                    : new JsonObject();
            steps.add(new Step(
                    string(step, "stepId").isBlank()
                            ? planId + "-step-" + (steps.size() + 1)
                            : string(step, "stepId"),
                    steps.size() + 1,
                    toolId,
                    arguments,
                    string(step, "reason"),
                    strings(step, "dependencies"),
                    string(step, "expectedObservation"),
                    string(step, "validationRequirement"),
                    string(step, "sideEffectClassification")
            ));
        }
        return new ValidatedAutomationPlan(planId, objective, steps);
    }

    public List<ModelGenerationHudState.PlanStep> hudSteps() {
        return this.steps.stream()
                .map(step -> new ModelGenerationHudState.PlanStep(
                        step.index(),
                        step.toolId(),
                        step.reason(),
                        ModelGenerationHudState.PlanStepStatus.PENDING,
                        ""
                ))
                .toList();
    }

    public record Step(
            String id,
            int index,
            String toolId,
            JsonObject arguments,
            String reason,
            List<String> dependencies,
            String expectedObservation,
            String validationRequirement,
            String sideEffectClassification
    ) {
        public Step {
            id = id == null ? "" : id.strip();
            toolId = toolId == null ? "" : toolId.strip();
            arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
            reason = reason == null ? "" : reason.replaceAll("\\s+", " ").strip();
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            expectedObservation = expectedObservation == null ? "" : expectedObservation.replaceAll("\\s+", " ").strip();
            validationRequirement = validationRequirement == null ? "" : validationRequirement.strip();
            sideEffectClassification = sideEffectClassification == null ? "" : sideEffectClassification.strip();
        }

        public ModelToolCall asToolCall(String planId) {
            return new ModelToolCall(
                    id.isBlank() ? planId + "-step-" + index : id,
                    toolId,
                    arguments
            );
        }
    }

    private static List<String> strings(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray(key)) {
            try {
                String value = element.getAsString().strip();
                if (!value.isBlank()) {
                    values.add(value);
                }
            } catch (RuntimeException ignored) {
            }
        }
        return List.copyOf(values);
    }

    private static String string(JsonObject object, String key) {
        try {
            return object != null && object.has(key)
                    ? object.get(key).getAsString().strip()
                    : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
