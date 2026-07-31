package com.spirit.koil.api.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ModelConversationRegistry {
    public static final String GENERAL = "general";
    public static final String AUTOMATION = "automation";

    private final Map<String, ModelConversation> conversations = new ConcurrentHashMap<>();
    private final int maximumMessages;
    private final int maximumCharacters;

    public ModelConversationRegistry(int maximumMessages, int maximumCharacters) {
        this.maximumMessages = Math.max(2, maximumMessages);
        this.maximumCharacters = Math.max(256, maximumCharacters);
    }

    public ModelConversation conversation(String id) {
        String key = id == null || id.isBlank() ? GENERAL : id.trim();
        return this.conversations.computeIfAbsent(
                key,
                ignored -> new ModelConversation(key, this.maximumMessages, this.maximumCharacters)
        );
    }

    public void clear(String id) {
        ModelConversation conversation = this.conversations.get(id == null ? "" : id.trim());
        if (conversation != null) {
            conversation.clear();
        }
    }

    public void clearAll() {
        this.conversations.values().forEach(ModelConversation::clear);
        this.conversations.clear();
    }
}
