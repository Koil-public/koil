package com.spirit.koil.api.model;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable provider request. {@code timeout} is retained as provider/configuration metadata
 * and for compatibility, but Koil's runtime manager does not use it to terminate active
 * generation. Active reasoning ends on provider completion/failure or explicit cancellation.
 */
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
    /**
     * No Koil-imposed completion cap. The provider/model may still stop at EOS,
     * its context window, cancellation, or a provider-owned hard safety limit.
     */
    public static final int UNBOUNDED_OUTPUT_TOKENS = -1;

    public StreamingModelRequest {
        id = id == null ? UUID.randomUUID() : id;
        conversationId = conversationId == null ? "" : conversationId.trim();
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        List<ModelMessage> canonicalMessages = new ArrayList<>();
        StringBuilder canonicalSystem = new StringBuilder(systemPrompt.strip());
        if (messages != null) {
            for (ModelMessage message : messages) {
                if (message == null) continue;
                if (message.role() == ModelRole.SYSTEM) {
                    String content = message.content() == null ? "" : message.content().strip();
                    if (!content.isBlank()) {
                        if (!canonicalSystem.isEmpty()) canonicalSystem.append("\n\n");
                        canonicalSystem.append(content);
                    }
                    continue;
                }
                canonicalMessages.add(message);
            }
        }
        systemPrompt = canonicalSystem.toString();
        messages = List.copyOf(canonicalMessages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        maximumOutputTokens = maximumOutputTokens == 0 ? 1024 : Math.max(UNBOUNDED_OUTPUT_TOKENS, maximumOutputTokens);
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofMinutes(2) : timeout;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public boolean unboundedOutput() {
        return this.maximumOutputTokens == UNBOUNDED_OUTPUT_TOKENS;
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
