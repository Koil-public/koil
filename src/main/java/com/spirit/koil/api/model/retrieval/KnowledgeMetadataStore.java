package com.spirit.koil.api.model.retrieval;

import java.util.List;
import java.util.Optional;

/** Authoritative durable metadata and compatible raw-vector store. */
public interface KnowledgeMetadataStore extends AutoCloseable {
    long allocateId();

    long upsert(KnowledgeEntry entry, float[] normalizedEmbedding);

    /** Updates only the active embedding for an existing entry when supported. */
    default long upsertEmbedding(KnowledgeEntry entry, float[] normalizedEmbedding) {
        return upsert(entry, normalizedEmbedding);
    }

    Optional<KnowledgeEntry> find(long id);

    List<KnowledgeEntry> activeEntries();

    /** Active rows belonging to the declared source ID, ordered by stable ID. */
    List<KnowledgeEntry> activeEntriesForSource(String sourceId);

    /** Active rows without a vector in this store's embedding space. */
    List<KnowledgeEntry> activeEntriesMissingEmbedding();

    List<Long> filterIds(KnowledgeFilter filter);

    boolean markDeleted(long id);

    List<StoredEmbedding> activeEmbeddings();

    IntegrityReport validate();

    EmbeddingIdentity embeddingIdentity();

    @Override
    void close();

    record StoredEmbedding(KnowledgeEntry entry, float[] embedding, String contentHash) {
        public StoredEmbedding {
            embedding = embedding == null ? new float[0] : embedding.clone();
            contentHash = contentHash == null ? "" : contentHash;
        }

        @Override
        public float[] embedding() {
            return this.embedding.clone();
        }
    }

    record IntegrityReport(boolean valid, long activeEntries, long embeddings,
                           long missingEmbeddings, long orphanEmbeddings, long incompatibleEmbeddings) {
    }
}
