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
        ModelActivityState activity,
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
        activity = activity == null ? activityFor(state, type, summary) : activity;
        summary = safeSummary(summary);
        data = data == null ? new JsonObject() : data.deepCopy();
        timestampMillis = timestampMillis <= 0L ? System.currentTimeMillis() : timestampMillis;
    }

    /** Compatibility constructor for producers migrating from request-state-only events. */
    public ModelExecutionEvent(
            UUID requestId,
            String sessionId,
            String eventId,
            Type type,
            ModelRequestState state,
            String summary,
            JsonObject data,
            long timestampMillis
    ) {
        this(requestId, sessionId, eventId, type, state, null, summary, data, timestampMillis);
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

    private static ModelActivityState activityFor(ModelRequestState state, Type type, String summary) {
        if (type == Type.FILE_SEARCHED) return ModelActivityState.SEARCHING;
        if (type == Type.FILE_READ) return ModelActivityState.READING;
        if (type == Type.FILE_CREATED || type == Type.FILE_MODIFIED || type == Type.FILE_DELETED) return ModelActivityState.EDITING;
        if (type == Type.DIFF_PRODUCED) return ModelActivityState.COMPARING;
        if (type == Type.VALIDATION_STARTED || type == Type.VALIDATION_PASSED || type == Type.VALIDATION_FAILED) return ModelActivityState.VALIDATING;
        if (type == Type.RETRY) return ModelActivityState.RETRYING;
        if (type == Type.REPLAN) return ModelActivityState.REPLANNING;
        if (type == Type.BLOCKED) return ModelActivityState.BLOCKED;
        if (type == Type.CANCELLATION_REQUESTED || type == Type.CANCELLED) return ModelActivityState.CANCELLED;
        if (type == Type.FINAL_RESULT) return ModelActivityState.COMPLETE;
        return switch (state == null ? ModelRequestState.THINKING : state) {
            case WAITING_FOR_RUNTIME, QUEUED -> ModelActivityState.STARTING;
            case PREPARING_CONTEXT -> ModelActivityState.PREPARING;
            case THINKING, PREFILLING -> ModelActivityState.THINKING;
            case INSPECTING -> ModelActivityState.INSPECTING;
            case PLANNING -> ModelActivityState.PLANNING;
            case VALIDATING_PLAN, VALIDATING -> ModelActivityState.VALIDATING;
            case WAITING_FOR_PLAN_APPROVAL, WAITING_FOR_ACTION_APPROVAL -> ModelActivityState.AWAITING_APPROVAL;
            case WAITING_FOR_DATA -> ModelActivityState.OBSERVING;
            case PAUSED -> ModelActivityState.IDLE;
            case GENERATING -> ModelActivityState.WRITING;
            case SELECTING_TOOL -> ModelActivityState.RESOLVING;
            case EXECUTING_TOOL -> ModelActivityState.fromLegacy(summary);
            case WAITING_FOR_TOOL_RESULT, OBSERVING_RESULT -> ModelActivityState.OBSERVING;
            case EDITING -> ModelActivityState.EDITING;
            case RETRYING -> ModelActivityState.RETRYING;
            case REPLANNING -> ModelActivityState.REPLANNING;
            case CHECKPOINTING -> ModelActivityState.WRITING;
            case FINALIZING -> ModelActivityState.FINALIZING;
            case COMPLETED -> ModelActivityState.COMPLETE;
            case BLOCKED -> ModelActivityState.BLOCKED;
            case CANCELLING, CANCELLED -> ModelActivityState.CANCELLED;
            case FAILED -> ModelActivityState.FAILED;
        };
    }
}
