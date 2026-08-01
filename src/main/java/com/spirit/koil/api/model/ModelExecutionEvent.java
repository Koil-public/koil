package com.spirit.koil.api.model;

import com.google.gson.JsonObject;

import java.util.UUID;

/** Provider-neutral, safe-to-render activity emitted by the shared model session. */
public record ModelExecutionEvent(
        UUID requestId,
        String sessionId,
        String eventId,
        Type type,
        ModelRequestState state,
        String summary,
        JsonObject data,
        long timestampMillis
) {
    public ModelExecutionEvent {
        requestId = requestId == null ? new UUID(0L, 0L) : requestId;
        sessionId = clean(sessionId);
        eventId = clean(eventId);
        type = type == null ? Type.THOUGHT_SUMMARY : type;
        state = state == null ? ModelRequestState.THINKING : state;
        summary = safeSummary(summary);
        data = data == null ? new JsonObject() : data.deepCopy();
        timestampMillis = timestampMillis <= 0L ? System.currentTimeMillis() : timestampMillis;
    }

    public enum Type {
        THOUGHT_SUMMARY,
        PLAN_CREATED,
        PLAN_VALIDATED,
        APPROVAL_REQUESTED,
        APPROVAL_ACCEPTED,
        APPROVAL_REJECTED,
        TOOL_SELECTED,
        TOOL_STARTED,
        TOOL_PROGRESS,
        TOOL_RESULT,
        FILE_READ,
        FILE_SEARCHED,
        FILE_CREATED,
        FILE_MODIFIED,
        FILE_DELETED,
        DIFF_PRODUCED,
        COMMAND_STARTED,
        COMMAND_OUTPUT,
        COMMAND_COMPLETED,
        VALIDATION_STARTED,
        VALIDATION_PASSED,
        VALIDATION_FAILED,
        RETRY,
        REPLAN,
        BLOCKED,
        CANCELLATION_REQUESTED,
        CANCELLED,
        FINAL_RESULT,
        CHECKPOINT
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeSummary(String value) {
        String clean = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s+", " ").strip();
        return clean.length() <= 420 ? clean : clean.substring(0, 419) + "…";
    }
}
