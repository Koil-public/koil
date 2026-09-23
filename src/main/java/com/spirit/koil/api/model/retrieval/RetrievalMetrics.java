package com.spirit.koil.api.model.retrieval;

import java.util.concurrent.atomic.AtomicLong;

/** Bounded counters/timers for retrieval diagnostics; raw queries and retrieved text are never retained. */
public final class RetrievalMetrics {
    private final AtomicLong retrievals = new AtomicLong();
    private final AtomicLong queryCacheHits = new AtomicLong();
    private final AtomicLong embeddings = new AtomicLong();
    private final AtomicLong embeddingNanos = new AtomicLong();
    private final AtomicLong vectorSearches = new AtomicLong();
    private final AtomicLong vectorSearchNanos = new AtomicLong();
    private final AtomicLong exactSearches = new AtomicLong();
    private final AtomicLong exactSearchNanos = new AtomicLong();
    private final AtomicLong metadataFilters = new AtomicLong();
    private final AtomicLong metadataFilterNanos = new AtomicLong();
    private final AtomicLong fusionNanos = new AtomicLong();
    private final AtomicLong retrievalNanos = new AtomicLong();
    private final AtomicLong candidates = new AtomicLong();
    private final AtomicLong selected = new AtomicLong();
    private final AtomicLong contextTokens = new AtomicLong();

    void queryCacheHit() { this.queryCacheHits.incrementAndGet(); }
    void embedding(long nanos) { this.embeddings.incrementAndGet(); this.embeddingNanos.addAndGet(nanos); }
    void vectorSearch(long nanos) { this.vectorSearches.incrementAndGet(); this.vectorSearchNanos.addAndGet(nanos); }
    void exactSearch(long nanos) { this.exactSearches.incrementAndGet(); this.exactSearchNanos.addAndGet(nanos); }
    void metadataFilter(long nanos) { this.metadataFilters.incrementAndGet(); this.metadataFilterNanos.addAndGet(nanos); }
    void fusion(long nanos) { this.fusionNanos.addAndGet(nanos); }

    void retrieval(long nanos, int candidates, int selected, int contextTokens) {
        this.retrievals.incrementAndGet();
        this.retrievalNanos.addAndGet(nanos);
        this.candidates.addAndGet(candidates);
        this.selected.addAndGet(selected);
        this.contextTokens.addAndGet(contextTokens);
    }

    public Snapshot snapshot(VectorIndexHealth health) {
        return new Snapshot(this.retrievals.get(), this.queryCacheHits.get(), this.embeddings.get(), this.embeddingNanos.get(),
                this.vectorSearches.get(), this.vectorSearchNanos.get(), this.exactSearches.get(), this.exactSearchNanos.get(),
                this.metadataFilters.get(), this.metadataFilterNanos.get(), this.fusionNanos.get(), this.retrievalNanos.get(),
                this.candidates.get(), this.selected.get(), this.contextTokens.get(), health);
    }

    public record Snapshot(long retrievals, long queryCacheHits, long embeddings, long embeddingNanos,
                           long vectorSearches, long vectorSearchNanos, long exactSearches, long exactSearchNanos,
                           long metadataFilters, long metadataFilterNanos, long fusionNanos, long retrievalNanos,
                           long candidates, long selected, long contextTokens, VectorIndexHealth indexHealth) {
        public double queryCacheHitRate() {
            return this.retrievals == 0L ? 0.0D : (double) this.queryCacheHits / this.retrievals;
        }
    }
}
