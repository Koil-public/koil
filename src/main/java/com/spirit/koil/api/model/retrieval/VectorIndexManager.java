package com.spirit.koil.api.model.retrieval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Selects TurboVec only when its native index is healthy; SQLite-backed Flat Java is the safe fallback. */
public final class VectorIndexManager implements AutoCloseable {
    private final VectorIndex index;
    private final VectorIndexHealth preferredHealth;

    private VectorIndexManager(VectorIndex index, VectorIndexHealth preferredHealth) {
        this.index = index;
        this.preferredHealth = preferredHealth;
    }

    public static VectorIndexManager open(KnowledgeMetadataStore store, Path vectorPath, int bitWidth) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(vectorPath, "vectorPath");
        List<KnowledgeMetadataStore.StoredEmbedding> embeddings = store.activeEmbeddings();
        TurboVecVectorIndex turbo = TurboVecVectorIndex.open(vectorPath, store.embeddingIdentity().dimensions(), bitWidth);
        VectorIndexHealth turboHealth = turbo.health();
        if (turboHealth.ready() && turboHealth.entryCount() == embeddings.size()) {
            return new VectorIndexManager(turbo, turboHealth);
        }

        // SQLite is authoritative. A stale native file must never permanently pin retrieval to Flat Java.
        // Rebuild it whenever dimensions/bit-width/count drift from the active embedding identity.
        if (turboHealth.ready() || turboHealth.state() == VectorIndexHealth.State.REBUILD_REQUIRED) {
            turbo.close();
            try {
                Files.deleteIfExists(vectorPath.toAbsolutePath());
                TurboVecVectorIndex rebuilt = TurboVecVectorIndex.open(vectorPath, store.embeddingIdentity().dimensions(), bitWidth);
                if (rebuilt.health().ready()) {
                    for (KnowledgeMetadataStore.StoredEmbedding embedding : embeddings) {
                        rebuilt.add(embedding.entry().id(), embedding.embedding());
                    }
                    rebuilt.sync();
                    return new VectorIndexManager(rebuilt, rebuilt.health());
                }
                turboHealth = rebuilt.health();
                rebuilt.close();
            } catch (IOException | RuntimeException rebuildFailure) {
                turboHealth = new VectorIndexHealth(VectorIndexHealth.State.REBUILD_REQUIRED, "turbovec",
                        "TurboVec rebuild failed: " + concise(rebuildFailure), embeddings.size(), store.embeddingIdentity().dimensions());
            }
        } else {
            turbo.close();
        }

        FlatJavaVectorIndex flat = new FlatJavaVectorIndex(store.embeddingIdentity().dimensions());
        for (KnowledgeMetadataStore.StoredEmbedding embedding : embeddings) {
            flat.add(embedding.entry().id(), embedding.embedding());
        }
        String detail = turboHealth.ready()
                ? "TurboVec index count differs from authoritative metadata; rebuild required"
                : turboHealth.detail();
        return new VectorIndexManager(new FallbackVectorIndex(flat, detail), turboHealth);
    }

    public VectorIndex index() {
        return this.index;
    }

    /** The active backend health; Flat Java reports DEGRADED with the precise TurboVec reason. */
    public VectorIndexHealth health() {
        return this.index.health();
    }

    /** Native status retained for diagnostics and rebuild decisions even when fallback remains usable. */
    public VectorIndexHealth preferredHealth() {
        return this.preferredHealth;
    }

    private static String concise(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    @Override
    public void close() {
        this.index.close();
    }

    private static final class FallbackVectorIndex implements VectorIndex {
        private final FlatJavaVectorIndex delegate;
        private final String nativeDetail;

        private FallbackVectorIndex(FlatJavaVectorIndex delegate, String nativeDetail) {
            this.delegate = delegate;
            this.nativeDetail = nativeDetail == null || nativeDetail.isBlank()
                    ? "TurboVec unavailable" : "TurboVec unavailable: " + nativeDetail;
        }

        @Override
        public void add(long id, float[] embedding) {
            this.delegate.add(id, embedding);
        }

        @Override
        public boolean remove(long id) {
            return this.delegate.remove(id);
        }

        @Override
        public List<VectorSearchResult> search(float[] query, VectorSearchRequest request) {
            return this.delegate.search(query, request);
        }

        @Override
        public void sync() {
            this.delegate.sync();
        }

        @Override
        public VectorIndexHealth health() {
            VectorIndexHealth health = this.delegate.health();
            return health.state() == VectorIndexHealth.State.CLOSED ? health
                    : new VectorIndexHealth(VectorIndexHealth.State.DEGRADED, "flat-java", this.nativeDetail,
                    health.entryCount(), health.dimensions());
        }

        @Override
        public void close() {
            this.delegate.close();
        }
    }
}
