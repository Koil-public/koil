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
        // Conversation history is durable for the lifetime of this chat. Request-time
        // context is bounded by snapshotWithin(...); adding a new /ask turn must never
        // silently turn into a new chat by deleting earlier turns. Only an explicit
        // /model reset clears the transcript.
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
        if (this.messages.isEmpty()) return List.of();

        int start = this.messages.size();
        int selectedCharacters = 0;
        int selectedMessages = 0;
        for (int index = this.messages.size() - 1; index >= 0 && selectedMessages < messageLimit; index--) {
            ModelMessage message = this.messages.get(index);
            int length = message.content().length();
            if (selectedMessages > 0 && selectedCharacters + length > characterLimit) break;
            start = index;
            selectedCharacters += length;
            selectedMessages++;
        }

        // A provider history must start at a real user-turn boundary. If the bounded
        // window begins inside an assistant/tool exchange, include the nearest user
        // anchor even when doing so slightly exceeds the soft character/message budget.
        // This prevents strict chat templates from receiving orphan assistant/tool rows.
        int anchoredStart = start;
        while (anchoredStart > 0 && this.messages.get(anchoredStart).role() != ModelRole.USER) {
            anchoredStart--;
        }
        if (this.messages.get(anchoredStart).role() != ModelRole.USER) {
            for (int index = start; index < this.messages.size(); index++) {
                if (this.messages.get(index).role() == ModelRole.USER) {
                    anchoredStart = index;
                    break;
                }
            }
        }

        List<ModelMessage> selected = new ArrayList<>();
        for (int index = anchoredStart; index < this.messages.size(); index++) {
            selected.add(this.messages.get(index));
        }
        while (!selected.isEmpty() && selected.get(0).role() != ModelRole.USER) {
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

}
