package com.spirit.client.gui.automation;

import com.google.gson.GsonBuilder;
import com.spirit.koil.api.telemetry.TelemetryCapabilityState;
import com.spirit.koil.api.telemetry.TelemetrySnapshots;
import com.spirit.koil.api.telemetry.TelemetryText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Typed presentation builder. It never infers semantic colors from arbitrary prose. */
final class AutomationWorkspaceTelemetryFormatter {
    private static final com.google.gson.Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AutomationWorkspaceTelemetryFormatter() {}

    static List<List<TelemetryText>> summary(TelemetrySnapshots.Span span, long now) {
        if (span == null) return List.of();
        List<List<TelemetryText>> out = new ArrayList<>();
        out.add(line(
                t(TelemetryText.Kind.KEY, "state"), t(TelemetryText.Kind.PLAIN, " = "),
                t(stateKind(span.state()), span.state().name().toLowerCase(java.util.Locale.ROOT)),
                t(TelemetryText.Kind.PLAIN, "  "), t(TelemetryText.Kind.KEY, "duration"),
                t(TelemetryText.Kind.PLAIN, " = "), t(TelemetryText.Kind.DURATION, span.durationMillis(now) + " ms")
        ));
        if (!span.reasonCode().isBlank()) {
            out.add(line(t(TelemetryText.Kind.KEY, "reason"), t(TelemetryText.Kind.PLAIN, " = "),
                    t(span.state().terminalProblem() ? TelemetryText.Kind.ERROR : TelemetryText.Kind.VALUE, span.reasonCode())));
        }
        return List.copyOf(out);
    }

    static List<List<TelemetryText>> details(TelemetrySnapshots.Span span, long now) {
        List<List<TelemetryText>> out = new ArrayList<>(summary(span, now));
        out.add(line(t(TelemetryText.Kind.KEY, "span"), t(TelemetryText.Kind.PLAIN, " = "),
                t(TelemetryText.Kind.ID, span.spanId())));
        if (!span.parentSpanId().isBlank()) {
            out.add(line(t(TelemetryText.Kind.KEY, "parent"), t(TelemetryText.Kind.PLAIN, " = "),
                    t(TelemetryText.Kind.ID, span.parentSpanId())));
        }
        span.attributes().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                out.add(line(t(TelemetryText.Kind.KEY, entry.getKey()), t(TelemetryText.Kind.PLAIN, " = "),
                        t(TelemetryText.Kind.VALUE, entry.getValue()))));
        span.metrics().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                out.add(line(t(TelemetryText.Kind.KEY, entry.getKey()), t(TelemetryText.Kind.PLAIN, " = "),
                        t(metricKind(entry.getKey()), Long.toString(entry.getValue())))));
        span.events().stream().sorted(Comparator.comparingLong(TelemetrySnapshots.Event::timestampMillis)).forEach(event -> {
            List<TelemetryText> eventLine = new ArrayList<>();
            eventLine.add(t(TelemetryText.Kind.MUTED, "+" + Math.max(0L, event.timestampMillis() - span.startedAtMillis()) + "ms "));
            eventLine.add(t(TelemetryText.Kind.KEY, event.type()));
            eventLine.add(t(TelemetryText.Kind.PLAIN, "  "));
            eventLine.add(t(stateKind(event.state()), event.message()));
            out.add(List.copyOf(eventLine));
        });
        return List.copyOf(out);
    }

    static List<List<TelemetryText>> raw(Object value) {
        String json = GSON.toJson(value);
        List<List<TelemetryText>> out = new ArrayList<>();
        for (String line : json.split("\\R", -1)) out.add(line(t(TelemetryText.Kind.PLAIN, line)));
        return List.copyOf(out);
    }

    private static TelemetryText.Kind stateKind(TelemetryCapabilityState state) {
        if (state == null) return TelemetryText.Kind.STATE;
        return switch (state) {
            case AVAILABLE, ACTIVE -> TelemetryText.Kind.SUCCESS;
            case DEGRADED, BLOCKED, CANCELLED -> TelemetryText.Kind.WARNING;
            case NOT_IMPLEMENTED, UNAVAILABLE, FAILED -> TelemetryText.Kind.ERROR;
            case IDLE -> TelemetryText.Kind.MUTED;
        };
    }

    private static TelemetryText.Kind metricKind(String key) {
        String value = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
        if (value.endsWith("_ms") || value.contains("duration") || value.contains("latency")) return TelemetryText.Kind.DURATION;
        return TelemetryText.Kind.COUNT;
    }

    private static TelemetryText t(TelemetryText.Kind kind, String text) {
        return new TelemetryText(kind, text);
    }

    private static List<TelemetryText> line(TelemetryText... text) {
        return List.of(text);
    }
}
