package com.spirit.koil.api.development.command;

import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.text.Text;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures bounded chat evidence during one bridge submission window.
 *
 * <p>Observed rows are supporting evidence only. They are not claimed as
 * authoritative command/result correlation and are never suppressed.</p>
 */
public final class DevelopmentCommandFeedbackCollector {
    private static final int MAX_FEEDBACK_ROWS = 16;
    private static final int MAX_FEEDBACK_LENGTH = 512;
    private static String activeRequestId = "";
    private static final List<Feedback> feedback = new ArrayList<>();

    private DevelopmentCommandFeedbackCollector() {
    }

    static synchronized void begin(String requestId) {
        activeRequestId = requestId == null ? "" : requestId;
        feedback.clear();
    }

    public static synchronized void observe(Text message, MessageIndicator indicator) {
        if (activeRequestId.isBlank() || message == null || feedback.size() >= MAX_FEEDBACK_ROWS) {
            return;
        }
        String value = message.getString().replace('\r', ' ').replace('\n', ' ').trim();
        if (value.isBlank()) {
            return;
        }
        if (value.length() > MAX_FEEDBACK_LENGTH) {
            value = value.substring(0, MAX_FEEDBACK_LENGTH);
        }
        String source = indicator == null || indicator.loggedName() == null
                ? "unclassified_chat_row"
                : indicator.loggedName();
        feedback.add(new Feedback("observed_after_send", value, source, Instant.now().toString()));
    }

    static synchronized List<Feedback> finish(String requestId) {
        if (requestId == null || !requestId.equals(activeRequestId)) {
            return List.of();
        }
        List<Feedback> result = List.copyOf(feedback);
        activeRequestId = "";
        feedback.clear();
        return result;
    }

    static synchronized void clear() {
        activeRequestId = "";
        feedback.clear();
    }

    public record Feedback(String type, String text, String source, String observedAt) {
    }
}
