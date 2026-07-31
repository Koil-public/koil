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
                    steps.size() + 1,
                    toolId,
                    arguments,
                    string(step, "reason")
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
            int index,
            String toolId,
            JsonObject arguments,
            String reason
    ) {
        public Step {
            toolId = toolId == null ? "" : toolId.strip();
            arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
            reason = reason == null ? "" : reason.replaceAll("\\s+", " ").strip();
        }

        public ModelToolCall asToolCall(String planId) {
            return new ModelToolCall(
                    planId + "-step-" + index,
                    toolId,
                    arguments
            );
        }
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
