package com.spirit.koil.api.model.retrieval;

import com.spirit.koil.api.context.ContextArtifact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Indexes retrievable context segments through Koil's existing hybrid engine; ContextStore remains canonical. */
public final class ContextArtifactRetrievalAdapter {
    private static final int MAXIMUM_CHUNK_CHARACTERS = 800;
    private final KoilRetrievalEngine engine;

    public ContextArtifactRetrievalAdapter(KoilRetrievalEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Async indexing never changes the canonical context artifact or blocks model assembly. */
    public CompletableFuture<SourceSyncResult> record(ContextArtifact artifact) {
        if (artifact == null || artifact.canonicalContent().isBlank()) {
            return CompletableFuture.completedFuture(new SourceSyncResult(0, 0, 0, 0));
        }
        return this.engine.synchronizeSource(snapshot(artifact));
    }

    /** Returns only relevant chunks for one artifact and its owning scope; exact retrieval remains the fallback. */
    public CompletableFuture<List<String>> select(ContextArtifact artifact, String query, int maximumCharacters) {
        if (artifact == null || query == null || query.isBlank() || maximumCharacters <= 0) {
            return CompletableFuture.completedFuture(List.of());
        }
        KnowledgeFilter filter = new KnowledgeFilter(
                java.util.Set.of(KnowledgeType.EPISODIC), java.util.Set.of("context-artifact"),
                java.util.Set.of(artifact.scopeId()), Map.of("contextRef", artifact.id()), 0L);
        return this.engine.retrieve(new KnowledgeQuery(query, filter, 8,
                        // RetrievalContextBuilder adds a provenance header before budget selection; output is bounded again below.
                        Math.max(1, Math.min(2_048, maximumCharacters / 2)), "context:" + artifact.id(),
                        KnowledgeTrust.HISTORICAL_CONTEXT))
                .thenApply(result -> bounded(result.selected().stream().map(candidate -> candidate.entry().text()).toList(), maximumCharacters))
                .exceptionally(ignored -> List.of());
    }

    /** Ranks bounded artifact references inside one request scope without exposing canonical bodies. */
    public CompletableFuture<List<String>> search(String scopeId, String query, int limit) {
        if (scopeId == null || scopeId.isBlank() || query == null || query.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }
        int maximum = Math.max(1, Math.min(8, limit));
        KnowledgeFilter filter = new KnowledgeFilter(
                java.util.Set.of(KnowledgeType.EPISODIC), java.util.Set.of("context-artifact"),
                java.util.Set.of(scopeId.strip()), Map.of(), 0L);
        return this.engine.retrieve(new KnowledgeQuery(query, filter, maximum, 256,
                        "context-search:" + scopeId.strip(), KnowledgeTrust.HISTORICAL_CONTEXT))
                .thenApply(result -> result.selected().stream().map(candidate -> candidate.entry().metadata().get("contextRef"))
                        .filter(Objects::nonNull).distinct().limit(maximum).toList())
                .exceptionally(ignored -> List.of());
    }

    /** Removes only Koil's derived index record; ContextStore lifecycle remains separate and authoritative. */
    public CompletableFuture<Void> release(ContextArtifact artifact) {
        if (artifact == null) return CompletableFuture.completedFuture(null);
        return this.engine.synchronizeSource(new KnowledgeSourceSnapshot(sourceId(artifact), "released", List.of())).thenApply(ignored -> null);
    }

    private static KnowledgeSourceSnapshot snapshot(ContextArtifact artifact) {
        List<KnowledgeEntry> entries = new ArrayList<>();
        List<String> chunks = chunks(artifact.canonicalContent());
        for (int index = 0; index < chunks.size(); index++) {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("sourceId", sourceId(artifact));
            metadata.put("sourceKey", artifact.contentHash() + ":" + index);
            metadata.put("contextRef", artifact.id());
            metadata.put("contentHash", artifact.contentHash());
            metadata.put("chunkPosition", Integer.toString(index));
            metadata.put("expiresAtMillis", Long.toString(artifact.expiresAt().toEpochMilli()));
            entries.add(new KnowledgeEntry(1L, KnowledgeType.EPISODIC, "context-artifact", chunks.get(index),
                    "context.artifact", artifact.scopeId(), artifact.createdAt().toEpochMilli(), 0.5D, 0.5D,
                    KnowledgeTrust.HISTORICAL_CONTEXT, metadata));
        }
        return new KnowledgeSourceSnapshot(sourceId(artifact), artifact.contentHash(), entries);
    }

    private static List<String> chunks(String content) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : content.split("\\R")) {
            if (!current.isEmpty() && current.length() + line.length() + 1 > MAXIMUM_CHUNK_CHARACTERS) {
                values.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) current.append('\n');
            if (line.length() <= MAXIMUM_CHUNK_CHARACTERS) current.append(line);
            else {
                if (!current.isEmpty()) {
                    values.add(current.toString());
                    current.setLength(0);
                }
                for (int start = 0; start < line.length(); start += MAXIMUM_CHUNK_CHARACTERS) {
                    values.add(line.substring(start, Math.min(line.length(), start + MAXIMUM_CHUNK_CHARACTERS)));
                }
            }
        }
        if (!current.isEmpty()) values.add(current.toString());
        return values;
    }

    private static List<String> bounded(List<String> values, int maximumCharacters) {
        List<String> selected = new ArrayList<>();
        int remaining = maximumCharacters;
        for (String value : values) {
            if (remaining <= 0) break;
            String bounded = value.length() <= remaining ? value : value.substring(0, Math.max(0, remaining - 1)) + "…";
            selected.add(bounded);
            remaining -= bounded.length();
        }
        return List.copyOf(selected);
    }

    private static String sourceId(ContextArtifact artifact) {
        return "context.artifact:" + digest(artifact.id());
    }

    private static String digest(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
