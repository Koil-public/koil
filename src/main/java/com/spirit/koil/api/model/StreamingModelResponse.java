package com.spirit.koil.api.model;

import java.util.List;
import java.util.UUID;

public record StreamingModelResponse(
        UUID requestId,
        String text,
        String reasoningText,
        List<ModelToolCall> toolCalls,
        ModelUsage usage,
        String providerFinishReason
) {
    public StreamingModelResponse {
        requestId = requestId == null ? UUID.randomUUID() : requestId;
        text = text == null ? "" : text;
        reasoningText = reasoningText == null ? "" : reasoningText;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? ModelUsage.empty() : usage;
        providerFinishReason = providerFinishReason == null ? "" : providerFinishReason;
    }

    /** Backward-compatible constructor for providers/tests without a separate reasoning channel. */
    public StreamingModelResponse(
            UUID requestId,
            String text,
            List<ModelToolCall> toolCalls,
            ModelUsage usage,
            String providerFinishReason
    ) {
        this(requestId, text, "", toolCalls, usage, providerFinishReason);
    }

    public boolean reasoningOnly() {
        return this.text.isBlank() && !this.reasoningText.isBlank() && this.toolCalls.isEmpty();
    }
}
