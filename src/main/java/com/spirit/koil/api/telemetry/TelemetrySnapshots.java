package com.spirit.koil.api.telemetry;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable snapshots consumed by UI/debugging without exposing mutable runtime state. */
public final class TelemetrySnapshots {
    private TelemetrySnapshots() {}

    public record Capability(
            String subsystem,
            String capability,
            TelemetryCapabilityState state,
            String reasonCode,
            String detail,
            long timestampMillis
    ) {
        public Capability {
            subsystem = clean(subsystem);
            capability = clean(capability);
            state = state == null ? TelemetryCapabilityState.IDLE : state;
            reasonCode = clean(reasonCode);
            detail = clean(detail);
            timestampMillis = Math.max(0L, timestampMillis);
        }
    }

    public record Event(
            String eventId,
            UUID requestId,
            String spanId,
            long timestampMillis,
            TelemetryCapabilityState state,
            String type,
            String message,
            Map<String, String> fields,
            List<TelemetryText> typedText
    ) {
        public Event {
            eventId = clean(eventId);
            spanId = clean(spanId);
            timestampMillis = Math.max(0L, timestampMillis);
            state = state == null ? TelemetryCapabilityState.IDLE : state;
            type = clean(type);
            message = clean(message);
            fields = fields == null ? Map.of() : Map.copyOf(fields);
            typedText = typedText == null ? List.of() : List.copyOf(typedText);
        }
    }

    public record Span(
            String spanId,
            String parentSpanId,
            UUID requestId,
            TelemetrySpanKind kind,
            String name,
            TelemetryCapabilityState state,
            String reasonCode,
            long startedAtMillis,
            long endedAtMillis,
            Map<String, String> attributes,
            Map<String, Long> metrics,
            List<Event> events
    ) {
        public Span {
            spanId = clean(spanId);
            parentSpanId = clean(parentSpanId);
            kind = kind == null ? TelemetrySpanKind.OTHER : kind;
            name = clean(name);
            state = state == null ? TelemetryCapabilityState.IDLE : state;
            reasonCode = clean(reasonCode);
            startedAtMillis = Math.max(0L, startedAtMillis);
            endedAtMillis = Math.max(0L, endedAtMillis);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
            events = events == null ? List.of() : List.copyOf(events);
        }

        public long durationMillis(long now) {
            long end = endedAtMillis > 0L ? endedAtMillis : Math.max(startedAtMillis, now);
            return Math.max(0L, end - startedAtMillis);
        }

        public boolean active() {
            return endedAtMillis <= 0L && state == TelemetryCapabilityState.ACTIVE;
        }
    }

    public record Request(
            UUID requestId,
            String rootSpanId,
            String title,
            long createdAtMillis,
            long completedAtMillis,
            long revision,
            TelemetryCapabilityState state,
            String reasonCode,
            List<Span> spans,
            List<TelemetryProvenance> provenance,
            List<Capability> capabilities,
            Map<String, Long> metrics
    ) {
        public Request {
            rootSpanId = clean(rootSpanId);
            title = clean(title);
            createdAtMillis = Math.max(0L, createdAtMillis);
            completedAtMillis = Math.max(0L, completedAtMillis);
            revision = Math.max(0L, revision);
            state = state == null ? TelemetryCapabilityState.IDLE : state;
            reasonCode = clean(reasonCode);
            spans = spans == null ? List.of() : List.copyOf(spans);
            provenance = provenance == null ? List.of() : List.copyOf(provenance);
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').strip();
    }
}
