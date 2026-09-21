package com.spirit.koil.api.model.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Koil's single local hybrid retrieval path. Metadata is authoritative; dense search is an acceleration.
 * All database, embedding, and index work is serialized off the Minecraft client thread.
 */
public final class KoilRetrievalEngine implements AutoCloseable {
    private static final int QUERY_CACHE_LIMIT = 128;
    private static final int INGEST_EMBEDDING_BATCH_SIZE = 32;

    private final KnowledgeMetadataStore store;
    private final EmbeddingProvider embeddings;
    private final VectorIndex vectors;
    private final RetrievalMetrics metrics = new RetrievalMetrics();
    private final ExecutorService worker;
    private final Map<String, float[]> queryCache = new LinkedHashMap<>(QUERY_CACHE_LIMIT, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > QUERY_CACHE_LIMIT;
        }
    };
    private ExactKnowledgeIndex exact;
    private volatile RetrievalProvenance lastProvenance = emptyProvenance();
    private volatile String diagnostic = "";
    private volatile int embeddingBackfillTotal;
    private volatile int embeddingBackfillCompleted;
    private volatile boolean embeddingBackfillRunning;
    private boolean closed;

    public KoilRetrievalEngine(KnowledgeMetadataStore store, EmbeddingProvider embeddings, VectorIndex vectors) {
        this(store, embeddings, vectors, true);
    }

    /** `restoreVectors` is false when VectorIndexManager already restored/opened the selected backend. */
    public KoilRetrievalEngine(KnowledgeMetadataStore store, EmbeddingProvider embeddings, VectorIndex vectors, boolean restoreVectors) {
        this.store = Objects.requireNonNull(store, "store");
        this.embeddings = embeddings;
        this.vectors = Objects.requireNonNull(vectors, "vectors");
        this.exact = ExactKnowledgeIndex.from(store.activeEntries());
        if (restoreVectors) {
            for (KnowledgeMetadataStore.StoredEmbedding embedding : store.activeEmbeddings()) {
                try {
                    this.vectors.add(embedding.entry().id(), embedding.embedding());
                } catch (RuntimeException exception) {
                    this.diagnostic = "Unable to restore vector " + embedding.entry().id() + ": " + concise(exception);
                }
            }
        }
        this.worker = Executors.newSingleThreadExecutor(new RetrievalThreadFactory());
    }

    public CompletableFuture<Long> remember(KnowledgeEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return CompletableFuture.supplyAsync(() -> {
            ensureOpen();
            rememberNow(entry);
            return entry.id();
        }, this.worker);
    }

    /** Reconciles one complete source snapshot on the retrieval worker. */
    public CompletableFuture<SourceSyncResult> synchronizeSource(KnowledgeSourceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return CompletableFuture.supplyAsync(() -> synchronizeSourceNow(snapshot), this.worker);
    }

    public CompletableFuture<Long> allocateId() {
        return CompletableFuture.supplyAsync(() -> {
            ensureOpen();
            return this.store.allocateId();
        }, this.worker);
    }

    /** Resolves a stable source-owned entry after a source snapshot has been reconciled. */
    public CompletableFuture<Long> sourceEntryId(String sourceId, String sourceKey) {
        String source = normalized(sourceId);
        String key = normalized(sourceKey);
        if (source.isEmpty() || key.isEmpty()) return CompletableFuture.completedFuture(0L);
        return CompletableFuture.supplyAsync(() -> {
            ensureOpen();
            return this.store.activeEntriesForSource(source).stream()
                    .filter(entry -> key.equals(entry.metadata().get("sourceKey")))
                    .mapToLong(KnowledgeEntry::id)
                    .findFirst().orElse(0L);
        }, this.worker);
    }

    /**
     * Incrementally fills vectors for entries created before the active embedding identity was selected.
     * Metadata remains authoritative if embedding or index work fails.
     */
    public CompletableFuture<Integer> backfillMissingEmbeddings() {
        if (this.embeddings == null) return CompletableFuture.completedFuture(0);
        return CompletableFuture.supplyAsync(() -> {
            ensureOpen();
            int embedded = 0;
            List<KnowledgeEntry> missing = this.store.activeEntriesMissingEmbedding();
            this.embeddingBackfillTotal = missing.size();
            this.embeddingBackfillCompleted = 0;
            this.embeddingBackfillRunning = !missing.isEmpty();
            for (int start = 0; start < missing.size(); start += INGEST_EMBEDDING_BATCH_SIZE) {
                List<KnowledgeEntry> batch = missing.subList(start, Math.min(missing.size(), start + INGEST_EMBEDDING_BATCH_SIZE));
                List<float[]> vectors = embeddingsForContents(batch.stream().map(KnowledgeEntry::text).toList());
                if (vectors.stream().allMatch(java.util.Objects::isNull)) break;
                for (int index = 0; index < batch.size(); index++) {
                    float[] vector = vectors.get(index);
                    if (vector == null) continue;
                    KnowledgeEntry entry = batch.get(index);
                    this.store.upsertEmbedding(entry, vector);
                    try {
                        this.vectors.add(entry.id(), vector);
                    } catch (RuntimeException exception) {
                        this.diagnostic = "Knowledge vector backfill needs repair; exact retrieval remains available: " + concise(exception);
                    }
                    embedded++;
                    this.embeddingBackfillCompleted = embedded;
                }
            }
            if (embedded > 0) {
                try {
                    this.vectors.sync();
                } catch (RuntimeException exception) {
                    this.diagnostic = "Knowledge vector backfill sync needs repair; exact retrieval remains available: " + concise(exception);
                }
            }
            this.embeddingBackfillRunning = false;
            return embedded;
        }, this.worker).whenComplete((ignored, failure) -> this.embeddingBackfillRunning = false);
    }

    public EmbeddingBackfillProgress embeddingBackfillProgress() {
        return new EmbeddingBackfillProgress(this.embeddingBackfillTotal, this.embeddingBackfillCompleted, this.embeddingBackfillRunning);
    }

    public record EmbeddingBackfillProgress(int total, int completed, boolean running) {
        public int remaining() { return Math.max(0, total - completed); }
        public int percent() { return total <= 0 ? (running ? 0 : 100) : Math.max(0, Math.min(100, (int) Math.round(completed * 100.0D / total))); }
    }

    public CompletableFuture<Long> rememberConversation(String prompt, String answer, String sessionId) {
        return rememberConversation(prompt, answer, sessionId, "");
    }

    public CompletableFuture<Long> rememberConversation(String prompt, String answer, String sessionId, String stableExchangeKey) {
        String request = normalized(prompt);
        String response = normalized(answer);
        if (request.isEmpty() || response.isEmpty()) return CompletableFuture.completedFuture(0L);
        String key = normalized(stableExchangeKey);
        if (key.isEmpty()) key = normalized(sessionId) + '\u0000' + request + '\u0000' + response;
        String sourceId = "model.conversation:" + digest(key);
        KnowledgeEntry exchange = new KnowledgeEntry(1L, KnowledgeType.CONVERSATION, "model",
                "User request:\n" + request + "\n\nKoil final response:\n" + response,
                "model.conversation", normalized(sessionId), System.currentTimeMillis(), 0.5D, 0.5D,
                KnowledgeTrust.HISTORICAL_CONTEXT, Map.of("kind", "final_exchange", "sourceId", sourceId, "sourceKey", "final",
                        "origin", "model_conversation", "modelAuthored", "true"));
        return synchronizeSource(new KnowledgeSourceSnapshot(sourceId, digest(exchange.text()), List.of(exchange)))
                .thenCompose(ignored -> sourceEntryId(sourceId, "final"));
    }

    public CompletableFuture<Boolean> contains(KnowledgeFilter filter) {
        return CompletableFuture.supplyAsync(() -> !this.store.filterIds(filter).isEmpty(), this.worker);
    }

    public CompletableFuture<Boolean> delete(long id) {
        return CompletableFuture.supplyAsync(() -> {
            ensureOpen();
            return deleteNow(id);
        }, this.worker);
    }

    /** Removes TTL-governed source entries without creating a parallel retention store. */
    public CompletableFuture<Integer> expireEntries(long nowMillis) {
        if (nowMillis < 0L) throw new IllegalArgumentException("nowMillis must not be negative");
        return CompletableFuture.supplyAsync(() -> {
            ensureOpen();
            int deleted = 0;
            for (KnowledgeEntry entry : this.store.activeEntries()) {
                if (expiresAt(entry, nowMillis) && deleteNow(entry.id())) deleted++;
            }
            return deleted;
        }, this.worker);
    }

    public CompletableFuture<RetrievalResult> retrieve(KnowledgeQuery query) {
        Objects.requireNonNull(query, "query");
        return CompletableFuture.supplyAsync(() -> retrieveNow(query), this.worker);
    }

    public RetrievalProvenance lastProvenance() {
        return this.lastProvenance;
    }

    public String diagnostic() {
        return this.diagnostic;
    }

    /** Non-blocking backend snapshot for compact diagnostics; it never starts retrieval work. */
    public VectorIndexHealth vectorHealth() {
        return this.vectors.health();
    }

    public RetrievalMetrics.Snapshot metrics() {
        return this.metrics.snapshot(this.vectors.health());
    }

    private SourceSyncResult synchronizeSourceNow(KnowledgeSourceSnapshot snapshot) {
        ensureOpen();
        Map<String, KnowledgeEntry> existing = new LinkedHashMap<>();
        for (KnowledgeEntry entry : this.store.activeEntriesForSource(snapshot.sourceId())) {
            String key = entry.metadata().get("sourceKey");
            if (key != null && !key.isBlank()) existing.put(key, entry);
        }
        int unchanged = 0;
        int created = 0;
        int updated = 0;
        int deleted = 0;
        boolean vectorsChanged = false;
        List<KnowledgeEntry> changedEntries = new ArrayList<>();
        Set<String> incoming = new java.util.LinkedHashSet<>();
        for (KnowledgeEntry proposed : snapshot.entries()) {
            String key = proposed.metadata().get("sourceKey");
            incoming.add(key);
            KnowledgeEntry prior = existing.get(key);
            KnowledgeEntry entry = withIdAndRevision(proposed, prior == null ? this.store.allocateId() : prior.id(), snapshot.revision());
            if (prior != null && sameContent(prior, entry)) {
                unchanged++;
                continue;
            }
            changedEntries.add(entry);
            vectorsChanged = true;
            if (prior == null) created++;
            else updated++;
        }
        for (Map.Entry<String, KnowledgeEntry> entry : existing.entrySet()) {
            if (incoming.contains(entry.getKey())) continue;
            if (deleteNow(entry.getValue().id())) {
                deleted++;
                vectorsChanged = true;
            }
        }
        rememberBatchNow(changedEntries);
        if (vectorsChanged) {
            try {
                this.vectors.sync();
            } catch (RuntimeException exception) {
                this.diagnostic = "Knowledge source sync needs vector repair; exact retrieval remains available: " + concise(exception);
            }
        }
        return new SourceSyncResult(unchanged, created, updated, deleted);
    }

    private void rememberNow(KnowledgeEntry entry) {
        float[] embedding = embeddingForContent(entry.text()).orElse(null);
        this.store.upsert(entry, embedding);
        this.exact.add(entry);
        try {
            if (embedding == null) this.vectors.remove(entry.id());
            else this.vectors.add(entry.id(), embedding);
        } catch (RuntimeException exception) {
            this.diagnostic = "Knowledge vector update failed; exact retrieval remains available: " + concise(exception);
        }
    }

    /** Uses the provider's existing list contract so large source imports do not serialize one HTTP embedding per fact. */
    private void rememberBatchNow(List<KnowledgeEntry> entries) {
        for (int start = 0; start < entries.size(); start += INGEST_EMBEDDING_BATCH_SIZE) {
            List<KnowledgeEntry> batch = entries.subList(start, Math.min(entries.size(), start + INGEST_EMBEDDING_BATCH_SIZE));
            List<float[]> vectors = embeddingsForContents(batch.stream().map(KnowledgeEntry::text).toList());
            for (int index = 0; index < batch.size(); index++) {
                KnowledgeEntry entry = batch.get(index);
                float[] embedding = vectors.get(index);
                this.store.upsert(entry, embedding);
                this.exact.add(entry);
                try {
                    if (embedding == null) this.vectors.remove(entry.id());
                    else this.vectors.add(entry.id(), embedding);
                } catch (RuntimeException exception) {
                    this.diagnostic = "Knowledge vector update failed; exact retrieval remains available: " + concise(exception);
                }
            }
        }
    }

    private List<float[]> embeddingsForContents(List<String> texts) {
        List<float[]> unavailable = new ArrayList<>(texts.size());
        for (int index = 0; index < texts.size(); index++) unavailable.add(null);
        if (this.embeddings == null || texts.isEmpty()) return unavailable;
        try {
            long started = System.nanoTime();
            List<float[]> values = this.embeddings.embed(texts).join();
            this.metrics.embedding(System.nanoTime() - started);
            if (values == null || values.size() != texts.size()) throw new IllegalStateException("embedding provider returned an invalid result count");
            List<float[]> normalized = new ArrayList<>(values.size());
            for (float[] value : values) normalized.add(normalize(value, this.embeddings.identity()));
            return normalized;
        } catch (RuntimeException exception) {
            this.diagnostic = "Embedding unavailable; exact retrieval remains available: " + concise(exception);
            return unavailable;
        }
    }

    private boolean deleteNow(long id) {
        boolean deleted = this.store.markDeleted(id);
        if (deleted) {
            this.exact.remove(id);
            try {
                this.vectors.remove(id);
            } catch (RuntimeException exception) {
                this.diagnostic = "Knowledge vector deletion needs repair: " + concise(exception);
            }
        }
        return deleted;
    }

    private static KnowledgeEntry withIdAndRevision(KnowledgeEntry entry, long id, String revision) {
        Map<String, String> metadata = new LinkedHashMap<>(entry.metadata());
        metadata.put("sourceRevision", revision);
        return new KnowledgeEntry(id, entry.type(), entry.scope(), entry.text(), entry.source(), entry.sessionId(),
                entry.timestampMillis(), entry.importance(), entry.confidence(), entry.trust(), metadata);
    }

    private static boolean sameContent(KnowledgeEntry left, KnowledgeEntry right) {
        return left.type() == right.type()
                && left.scope().equals(right.scope())
                && left.text().equals(right.text())
                && left.source().equals(right.source())
                && left.sessionId().equals(right.sessionId())
                && Double.compare(left.importance(), right.importance()) == 0
                && Double.compare(left.confidence(), right.confidence()) == 0
                && left.trust() == right.trust()
                && sameMetadata(left.metadata(), right.metadata());
    }

    private static boolean sameMetadata(Map<String, String> left, Map<String, String> right) {
        Map<String, String> a = new LinkedHashMap<>(left);
        Map<String, String> b = new LinkedHashMap<>(right);
        a.remove("sourceRevision");
        b.remove("sourceRevision");
        return a.equals(b);
    }

    private static boolean expiresAt(KnowledgeEntry entry, long nowMillis) {
        try {
            String value = entry.metadata().get("expiresAtMillis");
            return value != null && !value.isBlank() && Long.parseLong(value) <= nowMillis;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private RetrievalResult retrieveNow(KnowledgeQuery query) {
        ensureOpen();
        long retrievalStarted = System.nanoTime();
        long metadataStarted = System.nanoTime();
        List<Long> allowed = this.store.filterIds(query.filter());
        this.metrics.metadataFilter(System.nanoTime() - metadataStarted);
        if (allowed.isEmpty()) {
            RetrievalResult empty = new RetrievalResult(List.of(), List.of(), "", 0);
            this.lastProvenance = provenance(query, List.of(), List.of(), List.of(), List.of(), false, empty);
            this.metrics.retrieval(System.nanoTime() - retrievalStarted, 0, 0, 0);
            return empty;
        }
        Set<Long> allowedIds = new java.util.LinkedHashSet<>();
        Map<Long, KnowledgeEntry> entries = new LinkedHashMap<>();
        for (long id : allowed) this.store.find(id).ifPresent(entry -> {
            if (expiresAt(entry, System.currentTimeMillis())) deleteNow(id);
            else {
                allowedIds.add(id);
                entries.put(id, entry);
            }
        });
        if (allowedIds.isEmpty()) {
            RetrievalResult empty = new RetrievalResult(List.of(), List.of(), "", 0);
            this.lastProvenance = provenance(query, List.of(), List.of(), List.of(), List.of(), false, empty);
            this.metrics.retrieval(System.nanoTime() - retrievalStarted, 0, 0, 0);
            return empty;
        }
        long exactStarted = System.nanoTime();
        List<ExactKnowledgeIndex.Match> exactMatches = this.exact.search(query.text(), allowedIds, query.candidateLimit());
        this.metrics.exactSearch(System.nanoTime() - exactStarted);
        List<VectorSearchResult> semantic = semanticSearch(query, allowedIds);
        if (this.embeddings instanceof LocalFeatureEmbeddingProvider && !exactMatches.isEmpty()) {
            semantic = corpusExpandedSemanticSearch(query, allowedIds, entries, exactMatches, semantic);
        }
        long fusionStarted = System.nanoTime();
        List<RetrievalCandidate> candidates = ReciprocalRankFusion.fuse(entries, semantic, exactMatches);
        candidates = EvidenceAwareRetrievalReranker.rerank(query.text(), candidates);
        candidates = relevanceGate(candidates);
        RetrievalResult result = RetrievalContextBuilder.build(candidates, query.contextTokenBudget());
        this.metrics.fusion(System.nanoTime() - fusionStarted);
        this.lastProvenance = provenance(query, candidates, exactMatches, semantic, result.selected(), !semantic.isEmpty(), result);
        this.metrics.retrieval(System.nanoTime() - retrievalStarted, candidates.size(), result.selected().size(), result.contextTokens());
        return result;
    }

    /**
     * ANN indexes always return nearest neighbours even when nothing is actually relevant. For the local
     * feature tier, suppress low-similarity neighbours unless they also have independent symbolic evidence.
     * This makes an unrelated query produce an honest empty result instead of confidently injecting noise.
     */
    private List<RetrievalCandidate> relevanceGate(List<RetrievalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (!(this.embeddings instanceof LocalFeatureEmbeddingProvider)) return candidates;

        float strongestDense = 0.0F;
        for (RetrievalCandidate candidate : candidates) strongestDense = Math.max(strongestDense, candidate.semanticScore());
        final float absoluteFloor = 0.180F;
        final float relativeFloor = strongestDense <= 0.0F ? Float.POSITIVE_INFINITY : Math.max(absoluteFloor, strongestDense - 0.095F);

        List<RetrievalCandidate> kept = new ArrayList<>();
        for (RetrievalCandidate candidate : candidates) {
            boolean symbolic = candidate.exactScore() >= 400.0F;
            boolean dense = candidate.semanticScore() >= relativeFloor;
            if (symbolic || dense) kept.add(candidate);
        }
        return List.copyOf(kept);
    }

    private List<VectorSearchResult> semanticSearch(KnowledgeQuery query, Set<Long> allowedIds) {
        if (this.embeddings == null || !this.vectors.health().ready()) return List.of();
        Optional<float[]> embedding = queryEmbedding(query.text());
        if (embedding.isEmpty()) return List.of();
        return semanticSearchVector(embedding.get(), query.candidateLimit(), allowedIds);
    }

    /**
     * Local-feature embeddings are intentionally lexical. This second pass makes them corpus-aware without
     * a hardcoded synonym table: strong exact anchors contribute a small amount of their own representation
     * to the query, then TurboVec searches again. Related terminology that co-occurs in trusted knowledge can
     * therefore surface even when it was not literally present in the user's wording.
     */
    private List<VectorSearchResult> corpusExpandedSemanticSearch(
            KnowledgeQuery query,
            Set<Long> allowedIds,
            Map<Long, KnowledgeEntry> entries,
            List<ExactKnowledgeIndex.Match> exactMatches,
            List<VectorSearchResult> firstPass
    ) {
        Optional<float[]> baseOptional = queryEmbedding(query.text());
        if (baseOptional.isEmpty()) return firstPass;
        float[] expanded = baseOptional.get().clone();
        double addedWeight = 0.0D;
        int anchors = 0;
        for (ExactKnowledgeIndex.Match match : exactMatches) {
            if (anchors >= 3 || match.score() < 500) break;
            KnowledgeEntry entry = entries.get(match.id());
            if (entry == null || entry.trust() == KnowledgeTrust.HISTORICAL_CONTEXT) continue;
            Optional<float[]> anchorOptional = embeddingForContent(entry.text());
            if (anchorOptional.isEmpty()) continue;
            float[] anchor = anchorOptional.get();
            float weight = match.score() >= 900 ? 0.18F : match.score() >= 700 ? 0.14F : 0.10F;
            for (int index = 0; index < expanded.length; index++) expanded[index] += anchor[index] * weight;
            addedWeight += weight;
            anchors++;
        }
        if (anchors == 0 || addedWeight <= 0.0D) return firstPass;
        expanded = normalize(expanded, this.embeddings.identity());
        List<VectorSearchResult> secondPass = semanticSearchVector(expanded, query.candidateLimit(), allowedIds);
        return mergeVectorResults(firstPass, secondPass, query.candidateLimit());
    }

    private List<VectorSearchResult> semanticSearchVector(float[] embedding, int limit, Set<Long> allowedIds) {
        try {
            long searchStarted = System.nanoTime();
            List<VectorSearchResult> results = this.vectors.search(embedding, new VectorSearchRequest(limit, allowedIds, 0));
            this.metrics.vectorSearch(System.nanoTime() - searchStarted);
            return results;
        } catch (RuntimeException exception) {
            this.diagnostic = "Semantic search failed; exact retrieval remains available: " + concise(exception);
            return List.of();
        }
    }

    private static List<VectorSearchResult> mergeVectorResults(List<VectorSearchResult> first,
                                                                List<VectorSearchResult> second,
                                                                int limit) {
        Map<Long, Float> best = new LinkedHashMap<>();
        if (first != null) for (VectorSearchResult result : first) best.merge(result.id(), result.score(), Math::max);
        if (second != null) for (VectorSearchResult result : second) best.merge(result.id(), result.score(), Math::max);
        return best.entrySet().stream()
                .map(entry -> new VectorSearchResult(entry.getKey(), entry.getValue()))
                .sorted((left, right) -> Float.compare(right.score(), left.score()))
                .limit(Math.max(1, limit))
                .toList();
    }

    private Optional<float[]> queryEmbedding(String text) {
        String cacheKey = normalized(text).toLowerCase(Locale.ROOT) + '\u0000' + this.embeddings.identity().cacheKey();
        synchronized (this.queryCache) {
            float[] cached = this.queryCache.get(cacheKey);
            if (cached != null) {
                this.metrics.queryCacheHit();
                return Optional.of(cached.clone());
            }
        }
        Optional<float[]> embedded = embeddingForContent(text);
        embedded.ifPresent(vector -> {
            synchronized (this.queryCache) {
                this.queryCache.put(cacheKey, vector.clone());
            }
        });
        return embedded;
    }

    private Optional<float[]> embeddingForContent(String text) {
        if (this.embeddings == null) return Optional.empty();
        try {
            long embeddingStarted = System.nanoTime();
            List<float[]> values = this.embeddings.embed(List.of(text)).join();
            this.metrics.embedding(System.nanoTime() - embeddingStarted);
            if (values == null || values.size() != 1) throw new IllegalStateException("embedding provider returned an invalid result count");
            return Optional.of(normalize(values.get(0), this.embeddings.identity()));
        } catch (RuntimeException exception) {
            this.diagnostic = "Embedding unavailable; exact retrieval remains available: " + concise(exception);
            return Optional.empty();
        }
    }

    private static float[] normalize(float[] vector, EmbeddingIdentity identity) {
        if (vector == null || vector.length != identity.dimensions()) {
            throw new IllegalArgumentException("embedding dimensions must match " + identity.dimensions());
        }
        double squared = 0.0D;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("embedding values must be finite");
            squared += value * value;
        }
        if (squared == 0.0D) throw new IllegalArgumentException("embedding must not be zero");
        if (!identity.normalized()) return vector.clone();
        float scale = (float) (1.0D / Math.sqrt(squared));
        float[] normalized = vector.clone();
        for (int index = 0; index < normalized.length; index++) normalized[index] *= scale;
        return normalized;
    }

    private static RetrievalProvenance provenance(KnowledgeQuery query, List<RetrievalCandidate> candidates,
                                                   List<?> exact, List<?> semantic, List<RetrievalCandidate> selected,
                                                   boolean semanticAvailable, RetrievalResult result) {
        List<Long> candidateIds = candidates.stream().map(candidate -> candidate.entry().id()).toList();
        List<Long> selectedIds = selected.stream().map(candidate -> candidate.entry().id()).toList();
        List<Long> rejectedIds = result.rejected().stream().map(candidate -> candidate.entry().id()).toList();
        return new RetrievalProvenance(query.requestId(), System.currentTimeMillis(), "hybrid", semanticAvailable,
                exact.size(), semantic.size(), candidateIds, selectedIds, rejectedIds, result.contextTokens());
    }

    private static RetrievalProvenance emptyProvenance() {
        return new RetrievalProvenance("", 0L, "unknown", false, 0, 0, List.of(), List.of(), List.of(), 0);
    }

    private static String normalized(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static String digest(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String concise(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private void ensureOpen() {
        if (this.closed) throw new IllegalStateException("retrieval engine is closed");
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        this.worker.shutdownNow();
        try {
            this.vectors.close();
        } finally {
            try {
                if (this.embeddings != null) this.embeddings.close();
            } finally {
                this.store.close();
            }
        }
    }

    private static final class RetrievalThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "Koil Retrieval");
            thread.setDaemon(true);
            return thread;
        }
    }
}
