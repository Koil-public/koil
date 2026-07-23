package com.spirit.koil.api.chat;

import com.spirit.koil.api.chat.RichChatMessage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class RichChatMessageStore {
    private static final int MAX_MESSAGES = 256;
    private static final Map<UUID, RichChatMessage> BY_ID = new LinkedHashMap<>();
    private static final Deque<UUID> ORDER = new ArrayDeque<>();

    private RichChatMessageStore() {
    }

    public static synchronized void remember(RichChatMessage message) {
        if (message == null || message.messageId() == null) {
            return;
        }

        BY_ID.put(message.messageId(), message);
        ORDER.remove(message.messageId());
        ORDER.addLast(message.messageId());
        while (ORDER.size() > MAX_MESSAGES) {
            UUID removed = ORDER.removeFirst();
            BY_ID.remove(removed);
        }
    }

    public static synchronized Optional<RichChatMessage> byId(UUID messageId) {
        if (messageId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_ID.get(messageId));
    }

    public static synchronized int size() {
        return BY_ID.size();
    }

    public static synchronized void clear() {
        BY_ID.clear();
        ORDER.clear();
    }
}
