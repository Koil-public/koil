package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.model.ModelRequestState;
import com.spirit.koil.api.model.ModelActivityState;

/**
 * Shared one-word model state vocabulary used by every chat surface.
 */
public final class ModelRequestStatusPresentation {
    private ModelRequestStatusPresentation() {
    }

    public static View forState(ModelRequestState state) {
        ModelRequestState safe = state == null ? ModelRequestState.FAILED : state;
        return switch (safe) {
            case WAITING_FOR_RUNTIME -> new View("Starting", ModelActivityState.STARTING);
            case QUEUED -> new View("Queued", ModelActivityState.STARTING);
            case PREPARING_CONTEXT -> new View("Preparing", ModelActivityState.PREPARING);
            case THINKING, PREFILLING -> new View("Thinking", ModelActivityState.THINKING);
            case INSPECTING -> new View("Inspecting", ModelActivityState.INSPECTING);
            case PLANNING -> new View("Planning", ModelActivityState.PLANNING);
            case VALIDATING_PLAN -> new View("Checking plan", ModelActivityState.VALIDATING);
            case WAITING_FOR_PLAN_APPROVAL -> new View("Plan approval", ModelActivityState.AWAITING_APPROVAL);
            case GENERATING -> new View("Writing", ModelActivityState.WRITING);
            case SELECTING_TOOL -> new View("Resolving", ModelActivityState.RESOLVING);
            case WAITING_FOR_ACTION_APPROVAL -> new View("Approval", ModelActivityState.AWAITING_APPROVAL);
            case EXECUTING_TOOL -> new View("Executing", ModelActivityState.EXECUTING);
            case WAITING_FOR_TOOL_RESULT -> new View("Observing", ModelActivityState.OBSERVING);
            case OBSERVING_RESULT -> new View("Observing", ModelActivityState.OBSERVING);
            case EDITING -> new View("Editing", ModelActivityState.EDITING);
            case VALIDATING -> new View("Validating", ModelActivityState.VALIDATING);
            case RETRYING -> new View("Retrying", ModelActivityState.RETRYING);
            case REPLANNING -> new View("Replanning", ModelActivityState.REPLANNING);
            case CHECKPOINTING -> new View("Writing", ModelActivityState.WRITING);
            case WAITING_FOR_DATA -> new View("Waiting", ModelActivityState.OBSERVING);
            case PAUSED -> new View("Paused", ModelActivityState.IDLE);
            case FINALIZING -> new View("Finalizing", ModelActivityState.FINALIZING);
            case COMPLETED -> new View("Complete", ModelActivityState.COMPLETE);
            case BLOCKED -> new View("Blocked", ModelActivityState.BLOCKED);
            case CANCELLING, CANCELLED -> new View("Cancelled", ModelActivityState.CANCELLED);
            case FAILED -> new View("Failed", ModelActivityState.FAILED);
        };
    }

    public static View forActivity(ModelRequestState state, String detail, String toolId) {
        ModelRequestState safe = state == null ? ModelRequestState.FAILED : state;
        String normalizedDetail = normalize(detail);
        if (safe == ModelRequestState.PREPARING_CONTEXT) {
            if (normalizedDetail.contains("correct") || normalizedDetail.contains("repair")) {
                return new View("Repairing", ModelActivityState.REPAIRING);
            }
            if (normalizedDetail.contains("check") || normalizedDetail.contains("validat")) {
                return new View("Checking", ModelActivityState.VALIDATING);
            }
            return new View("Preparing", ModelActivityState.PREPARING);
        }
        if (safe == ModelRequestState.EXECUTING_TOOL) {
            ModelToolActivityPresentation.Activity tool =
                    ModelToolActivityPresentation.activity(toolId, normalizedDetail);
            return new View(label(tool.state()), tool.state());
        }
        return forState(safe);
    }

    private static String label(ModelActivityState state) {
        String value = state == null ? "executing" : state.id().replace('_', ' ');
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(java.util.Locale.ROOT)
                .replace('.', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    public record View(String label, ModelActivityState activityState) {
        public View {
            activityState = activityState == null ? ModelActivityState.IDLE : activityState;
        }

        /** Transitional wire/string adapter for existing consumers. */
        public String semanticState() {
            return activityState.id();
        }
    }
}
