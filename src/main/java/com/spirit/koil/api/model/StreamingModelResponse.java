package com.spirit.koil.api.model;

import java.util.List;
import java.util.UUID;

public record StreamingModelResponse(
        UUID requestId,
        String text,
        List<ModelToolCall> toolCalls,
        ModelUsage usage,
        String providerFinishReason
) {
    public StreamingModelResponse {
        requestId = requestId == null ? UUID.randomUUID() : requestId;
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? ModelUsage.empty() : usage;
        providerFinishReason = providerFinishReason == null ? "" : providerFinishReason;
    }
}
