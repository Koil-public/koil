package com.spirit.koil.api.model.retrieval;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Metadata restrictions applied before dense ranking. */
public record KnowledgeFilter(
        Set<KnowledgeType> types,
        Set<String> scopes,
        Set<String> sessions,
        Map<String, String> metadataEquals,
        long notBeforeMillis
) {
    public KnowledgeFilter {
        types = types == null ? Set.of() : Set.copyOf(types);
        scopes = normalizedSet(scopes);
        sessions = normalizedSet(sessions);
        metadataEquals = normalizedMap(metadataEquals);
        if (notBeforeMillis < 0L) throw new IllegalArgumentException("notBeforeMillis must not be negative");
    }

    public static KnowledgeFilter any() {
        return new KnowledgeFilter(Set.of(), Set.of(), Set.of(), Map.of(), 0L);
    }

    private static Set<String> normalizedSet(Set<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values != null) for (String value : values) {
            if (value != null && !value.isBlank()) normalized.add(value.strip());
        }
        return Set.copyOf(normalized);
    }

    private static Map<String, String> normalizedMap(Map<String, String> values) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (values != null) values.forEach((key, value) -> {
            if (key != null && !key.isBlank()) normalized.put(key.strip(), value == null ? "" : value.strip());
        });
        return Map.copyOf(normalized);
    }
}
