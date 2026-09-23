package com.spirit.koil.api.model.provider.llamacpp;

import com.spirit.koil.api.model.ModelExposedData;
import com.spirit.koil.api.model.StreamingModelObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Buffers the first small slice of visible/native reasoning output until it has passed a
 * corruption check. This prevents an obviously broken GPU split from painting gibberish into
 * the HUD before Koil has enough evidence to reject the stream.
 */
final class LlamaCppStreamingIntegrityGate {
    private static final int TRUST_SAMPLE_CHARS = 32;
    private static final int MAX_BUFFERED_CHARS = 2048;

    private final UUID requestId;
    private final StreamingModelObserver downstream;
    private final List<Event> buffered = new ArrayList<>();
    private final StringBuilder sample = new StringBuilder();
    private int bufferedChars;
    private boolean trusted;

    LlamaCppStreamingIntegrityGate(UUID requestId, StreamingModelObserver downstream) {
        this.requestId = requestId;
        this.downstream = downstream;
    }

    void text(String delta) {
        if (delta == null || delta.isEmpty()) return;
        if (trusted) {
            monitorTrusted(delta);
            downstream.onTextDelta(requestId, delta);
            return;
        }
        addSample(delta);
        buffered.add(Event.text(delta));
        bufferedChars += delta.length();
        evaluate(false);
    }

    void reasoning(String delta) {
        if (delta == null || delta.isEmpty()) return;
        if (trusted) {
            monitorTrusted(delta);
            downstream.onReasoningDelta(requestId, delta);
            return;
        }
        addSample(delta);
        buffered.add(Event.reasoning(delta));
        bufferedChars += delta.length();
        evaluate(false);
    }

    void exposed(ModelExposedData exposed) {
        if (exposed == null || !exposed.hasText()) {
            if (exposed != null && trusted) downstream.onExposedData(requestId, exposed);
            return;
        }
        if (trusted) {
            monitorTrusted(exposed.text());
            downstream.onExposedData(requestId, exposed);
            return;
        }
        addSample(exposed.text());
        buffered.add(Event.exposed(exposed));
        bufferedChars += exposed.text().length();
        evaluate(false);
    }

    void finish(boolean hasToolCalls) {
        if (trusted) return;
        if (sample.length() == 0 && hasToolCalls) {
            trustAndFlush();
            return;
        }
        evaluate(true);
        if (!trusted) trustAndFlush();
    }

    private void monitorTrusted(String value) {
        addSample(value);
        LlamaCppOutputIntegrity.Assessment assessment = LlamaCppOutputIntegrity.streamingSample(sample.toString());
        if (!assessment.healthy()) {
            throw new LlamaCppOutputIntegrity.IntegrityException(assessment.detail());
        }
    }

    private void addSample(String value) {
        if (sample.length() >= MAX_BUFFERED_CHARS) return;
        int remaining = MAX_BUFFERED_CHARS - sample.length();
        sample.append(value, 0, Math.min(remaining, value.length()));
    }

    private void evaluate(boolean finalCheck) {
        LlamaCppOutputIntegrity.Assessment assessment = finalCheck
                ? LlamaCppOutputIntegrity.finalOutput(sample.toString())
                : LlamaCppOutputIntegrity.streamingSample(sample.toString());
        if (!assessment.healthy()) {
            throw new LlamaCppOutputIntegrity.IntegrityException(assessment.detail());
        }
        if (finalCheck || sample.length() >= TRUST_SAMPLE_CHARS || bufferedChars >= MAX_BUFFERED_CHARS) {
            trustAndFlush();
        }
    }

    private void trustAndFlush() {
        if (trusted) return;
        trusted = true;
        for (Event event : buffered) {
            switch (event.type) {
                case TEXT -> downstream.onTextDelta(requestId, event.text);
                case REASONING -> downstream.onReasoningDelta(requestId, event.text);
                case EXPOSED -> downstream.onExposedData(requestId, event.exposed);
            }
        }
        buffered.clear();
    }

    private enum Type { TEXT, REASONING, EXPOSED }

    private static final class Event {
        private final Type type;
        private final String text;
        private final ModelExposedData exposed;

        private Event(Type type, String text, ModelExposedData exposed) {
            this.type = type;
            this.text = text == null ? "" : text;
            this.exposed = exposed;
        }

        private static Event text(String value) { return new Event(Type.TEXT, value, null); }
        private static Event reasoning(String value) { return new Event(Type.REASONING, value, null); }
        private static Event exposed(ModelExposedData value) { return new Event(Type.EXPOSED, "", value); }
    }
}
