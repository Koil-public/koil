package com.spirit.koil.api.model.chat;

import com.spirit.koil.api.model.ModelRequestState;

/**
 * Shared one-word model state vocabulary used by every chat surface.
 */
public final class ModelRequestStatusPresentation {
    private ModelRequestStatusPresentation() {
    }

    public static View forState(ModelRequestState state) {
        ModelRequestState safe = state == null ? ModelRequestState.FAILED : state;
        return switch (safe) {
            case WAITING_FOR_RUNTIME -> new View("Starting", "waiting");
            case QUEUED -> new View("Queued", "waiting");
            case PREPARING_CONTEXT -> new View("Preparing", "thinking");
            case THINKING -> new View("Thinking", "thinking");
            case INSPECTING -> new View("Inspecting", "inspecting");
            case PLANNING -> new View("Planning", "planning");
            case VALIDATING_PLAN -> new View("Checking plan", "validating");
            case WAITING_FOR_PLAN_APPROVAL -> new View("Plan approval", "waiting");
            case PREFILLING -> new View("Thinking", "thinking");
            case GENERATING -> new View("Writing", "running");
            case SELECTING_TOOL -> new View("Selecting", "thinking");
            case WAITING_FOR_ACTION_APPROVAL -> new View("Approval", "waiting");
            case EXECUTING_TOOL -> new View("Acting", "using");
            case WAITING_FOR_TOOL_RESULT -> new View("Waiting", "waiting");
            case OBSERVING_RESULT -> new View("Observing", "inspecting");
            case EDITING -> new View("Editing", "using");
            case VALIDATING -> new View("Validating", "validating");
            case RETRYING -> new View("Retrying", "retrying");
            case REPLANNING -> new View("Replanning", "planning");
            case CHECKPOINTING -> new View("Saving", "waiting");
            case PAUSED -> new View("Paused", "waiting");
            case WAITING_FOR_DATA -> new View("Waiting for data", "waiting");
            case FINALIZING -> new View("Finishing", "thinking");
            case COMPLETED -> new View("Complete", "complete");
            case BLOCKED -> new View("Blocked", "blocked");
            case CANCELLING -> new View("Cancelling", "blocked");
            case CANCELLED -> new View("Cancelled", "blocked");
            case FAILED -> new View("Failed", "failed");
        };
    }

    public static View forActivity(ModelRequestState state, String detail, String toolId) {
        ModelRequestState safe = state == null ? ModelRequestState.FAILED : state;
        String normalizedDetail = normalize(detail);
        String normalizedTool = normalize(toolId);
        if (safe == ModelRequestState.PREPARING_CONTEXT) {
            if (normalizedDetail.contains("correct") || normalizedDetail.contains("repair")) {
                return new View("Repairing", "thinking");
            }
            if (normalizedDetail.contains("check") || normalizedDetail.contains("validat")) {
                return new View("Checking", "thinking");
            }
            return new View("Planning", "thinking");
        }
        if (safe == ModelRequestState.EXECUTING_TOOL) {
            if (normalizedTool.contains("automation plan")) {
                return new View("Planning", "thinking");
            }
            if (normalizedTool.contains("workspace read")) {
                return new View("Reading", "using");
            }
            if (normalizedTool.contains("workspace search")) {
                return new View("Searching", "using");
            }
            if (normalizedTool.contains("workspace write")
                    || normalizedTool.contains("workspace create")
                    || normalizedTool.contains("workspace delete")
                    || normalizedTool.contains("ktl apply")) {
                return new View("Editing", "using");
            }
            if (normalizedTool.contains("knowledge")) {
                return new View("Checking", "using");
            }
            if (normalizedTool.contains("command")) {
                return new View("Executing", "using");
            }
            if (normalizedTool.contains("movement")) {
                return new View("Moving", "moving");
            }
        }
        return forState(safe);
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

    public record View(String label, String semanticState) {
    }
}
