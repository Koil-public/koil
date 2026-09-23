package com.spirit.koil.api.model.retrieval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A complete revisioned view of one authoritative knowledge source. */
public record KnowledgeSourceSnapshot(String sourceId, String revision, List<KnowledgeEntry> entries) {
    public KnowledgeSourceSnapshot {
        sourceId = required(sourceId, "sourceId");
        revision = required(revision, "revision");
        entries = List.copyOf(entries == null ? List.of() : entries);
        Set<String> keys = new LinkedHashSet<>();
        for (KnowledgeEntry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (!sourceId.equals(entry.metadata().get("sourceId"))) {
                throw new IllegalArgumentException("knowledge source entry must use its snapshot sourceId");
            }
            String key = required(entry.metadata().get("sourceKey"), "sourceKey");
            if (!keys.add(key)) throw new IllegalArgumentException("duplicate knowledge sourceKey: " + key);
        }
    }

    private static String required(String value, String name) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
