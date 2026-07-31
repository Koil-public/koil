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
            case PREFILLING -> new View("Thinking", "thinking");
            case GENERATING -> new View("Writing", "running");
            case EXECUTING_TOOL -> new View("Acting", "using");
            case WAITING_FOR_TOOL_RESULT -> new View("Waiting", "waiting");
            case FINALIZING -> new View("Finishing", "thinking");
            case COMPLETED -> new View("Complete", "complete");
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
