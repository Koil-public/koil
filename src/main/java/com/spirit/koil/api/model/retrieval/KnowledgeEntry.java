package com.spirit.koil.api.model.retrieval;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Authoritative metadata record; vector slots are never content identity. */
public record KnowledgeEntry(
        long id,
        KnowledgeType type,
        String scope,
        String text,
        String source,
        String sessionId,
        long timestampMillis,
        double importance,
        double confidence,
        KnowledgeTrust trust,
        Map<String, String> metadata
) {
    public KnowledgeEntry {
        if (id <= 0L) throw new IllegalArgumentException("knowledge id must be positive");
        type = Objects.requireNonNull(type, "type");
        scope = required(scope, "scope");
        text = required(text, "text");
        source = required(source, "source");
        sessionId = sessionId == null ? "" : sessionId.strip();
        if (timestampMillis < 0L) throw new IllegalArgumentException("timestampMillis must not be negative");
        importance = unit(importance, "importance");
        confidence = unit(confidence, "confidence");
        trust = Objects.requireNonNull(trust, "trust");
        Map<String, String> normalized = new LinkedHashMap<>();
        if (metadata != null) {
            metadata.forEach((key, value) -> {
                String normalizedKey = required(key, "metadata key");
                String normalizedValue = value == null ? "" : value.strip();
                normalized.put(normalizedKey, normalizedValue);
            });
        }
        metadata = Map.copyOf(normalized);
    }

    private static String required(String value, String name) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    private static double unit(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0D || value > 1.0D) {
            throw new IllegalArgumentException(name + " must be finite in [0, 1]");
        }
        return value;
    }
}
