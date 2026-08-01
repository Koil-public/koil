package com.spirit.koil.api.model;

import com.google.gson.Gson;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ModelMessage(
        UUID id,
        ModelRole role,
        String content,
        String toolCallId,
        Instant createdAt,
        Map<String, String> metadata
) {
    private static final Gson GSON = new Gson();

    public ModelMessage {
        id = id == null ? UUID.randomUUID() : id;
        role = role == null ? ModelRole.USER : role;
        content = content == null ? "" : content;
        toolCallId = toolCallId == null ? "" : toolCallId;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ModelMessage user(String content) {
        return new ModelMessage(null, ModelRole.USER, content, "", null, Map.of());
    }

    public static ModelMessage assistant(String content) {
        return new ModelMessage(null, ModelRole.ASSISTANT, content, "", null, Map.of());
    }

    public static ModelMessage assistantToolCall(String content, ModelToolCall call) {
        if (call == null) {
            throw new IllegalArgumentException("tool call is required");
        }
        return new ModelMessage(
                null,
                ModelRole.ASSISTANT,
                content,
                call.id(),
                null,
                Map.of(
                        "tool_name", call.toolId(),
                        "tool_arguments", GSON.toJson(call.arguments())
                )
        );
    }

    public static ModelMessage toolResult(ModelToolResult result) {
        if (result == null) {
            throw new IllegalArgumentException("tool result is required");
        }
        com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
        payload.addProperty("status", result.status());
        payload.add("output", result.output().deepCopy());
        if (!result.failureCode().isBlank()) {
            payload.addProperty("failureCode", result.failureCode());
        }
        if (!result.detail().isBlank()) {
            payload.addProperty("detail", result.detail());
        }
        payload.addProperty("startedAtMillis", result.startedAtMillis());
        payload.addProperty("completedAtMillis", result.completedAtMillis());
        payload.addProperty("durationMillis", result.durationMillis());
        payload.addProperty("validationStatus", result.validationStatus());
        payload.addProperty("retryable", result.retryable());
        payload.addProperty("cancelled", result.cancelled());
        payload.addProperty("approvalStatus", result.approvalStatus());
        com.google.gson.JsonArray targets = new com.google.gson.JsonArray();
        result.changedTargets().forEach(targets::add);
        payload.add("changedTargets", targets);
        return new ModelMessage(
                null,
                ModelRole.TOOL,
                GSON.toJson(payload),
                result.callId(),
                null,
                Map.of("tool_name", result.toolId())
        );
    }
}
