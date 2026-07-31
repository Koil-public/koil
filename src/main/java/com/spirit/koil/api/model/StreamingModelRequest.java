package com.spirit.koil.api.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record StreamingModelRequest(
        UUID id,
        String conversationId,
        String systemPrompt,
        List<ModelMessage> messages,
        List<ModelToolDefinition> tools,
        int maximumOutputTokens,
        Duration timeout,
        Map<String, String> metadata
) {
    public StreamingModelRequest {
        id = id == null ? UUID.randomUUID() : id;
        conversationId = conversationId == null ? "" : conversationId.trim();
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        maximumOutputTokens = maximumOutputTokens <= 0 ? 1024 : maximumOutputTokens;
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofMinutes(2) : timeout;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public String latestUserText() {
        for (int index = this.messages.size() - 1; index >= 0; index--) {
            ModelMessage message = this.messages.get(index);
            if (message.role() == ModelRole.USER) {
                return message.content();
            }
        }
        return "";
    }
}
