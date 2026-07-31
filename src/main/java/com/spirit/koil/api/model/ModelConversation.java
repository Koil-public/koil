package com.spirit.koil.api.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ModelConversation {
    private final String id;
    private final int maximumMessages;
    private final int maximumCharacters;
    private final List<ModelMessage> messages = new ArrayList<>();
    private int characterCount;

    public ModelConversation(String id, int maximumMessages, int maximumCharacters) {
        this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        this.maximumMessages = Math.max(2, maximumMessages);
        this.maximumCharacters = Math.max(256, maximumCharacters);
    }

    public String id() {
        return this.id;
    }

    public synchronized void add(ModelMessage message) {
        if (message == null || message.content().isBlank() && message.toolCallId().isBlank()) {
            return;
        }
        this.messages.add(message);
        this.characterCount += message.content().length();
        trimToBounds();
    }

    public synchronized List<ModelMessage> snapshot() {
        return List.copyOf(this.messages);
    }

    /**
     * Builds a recent request window without discarding the larger in-session
     * history. This bounds prefill cost for compact models while retaining the
     * newest user/tool exchange.
     */
    public synchronized List<ModelMessage> snapshotWithin(int maximumMessages, int maximumCharacters) {
        int messageLimit = Math.max(2, maximumMessages);
        int characterLimit = Math.max(256, maximumCharacters);
        List<ModelMessage> selected = new ArrayList<>();
        int selectedCharacters = 0;
        for (int index = this.messages.size() - 1; index >= 0 && selected.size() < messageLimit; index--) {
            ModelMessage message = this.messages.get(index);
            int length = message.content().length();
            if (!selected.isEmpty() && selectedCharacters + length > characterLimit) {
                break;
            }
            selected.add(0, message);
            selectedCharacters += length;
        }
        while (!selected.isEmpty() && selected.get(0).role() == ModelRole.TOOL) {
            selected.remove(0);
        }
        return List.copyOf(selected);
    }

    public synchronized int characterCount() {
        return this.characterCount;
    }

    public synchronized void clear() {
        this.messages.clear();
        this.characterCount = 0;
    }

    private void trimToBounds() {
        while (this.messages.size() > this.maximumMessages || this.characterCount > this.maximumCharacters) {
            ModelMessage removed = this.messages.remove(0);
            this.characterCount -= removed.content().length();
        }
    }
}
