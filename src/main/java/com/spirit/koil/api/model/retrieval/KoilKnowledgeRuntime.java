package com.spirit.koil.api.model.retrieval;

import com.spirit.koil.api.model.LocalModelRuntimeLog;
import com.spirit.koil.api.model.catalog.EmbeddingModelSelectionStore;
import com.spirit.koil.api.model.catalog.LocalModelSelection;
import com.spirit.koil.api.model.provider.colibri.ColibriConfigurationStore;
import com.spirit.koil.api.model.provider.llamacpp.LlamaCppEmbeddingRuntime;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Shared local knowledge runtime used by conversation and future Automation/KTL/documentation sources. */
public final class KoilKnowledgeRuntime {
    private static final Path ROOT = Path.of("koil", "sys", "model", "knowledge");
    private static final Path LEGACY_MEMORY = Path.of("koil", "sys", "model", "associative-memory.json");
    private static volatile KoilRetrievalEngine engine;
    private static volatile InternetResearchKnowledgeAdapter research;
    private static volatile ContextArtifactRetrievalAdapter contextArtifacts;
    private static volatile java.util.concurrent.CompletableFuture<Void> population = java.util.concurrent.CompletableFuture.completedFuture(null);
    /** Number of records represented by the most recent successful built-in source synchronization. */
    private static volatile long builtInRecordCount;
    private static volatile EmbeddingIdentity activeEmbeddingIdentity;
    private static volatile boolean neuralEmbeddingTier;
    private static volatile java.util.concurrent.CompletableFuture<Integer> embeddingBackfill = java.util.concurrent.CompletableFuture.completedFuture(0);

    private KoilKnowledgeRuntime() {
    }

    public static Optional<KoilRetrievalEngine> shared() {
        KoilRetrievalEngine current = engine;
        if (current != null) return Optional.of(current);
        synchronized (KoilKnowledgeRuntime.class) {
            if (engine != null) return Optional.of(engine);
            EmbeddingProvider embeddings = null;
            KoilRetrievalEngine created = null;
            try {
                embeddings = embeddingProvider();
                EmbeddingIdentity identity = embeddings.identity();
                activeEmbeddingIdentity = identity;
                neuralEmbeddingTier = embeddings instanceof LlamaCppEmbeddingRuntime;
                SqliteKnowledgeMetadataStore store = new SqliteKnowledgeMetadataStore(ROOT.resolve("knowledge.db"), identity);
                VectorIndexManager indexes = VectorIndexManager.open(store, ROOT.resolve("vectors.tvim"), 4);
                created = new KoilRetrievalEngine(store, embeddings, indexes.index(), false);
                engine = created;
                research = new InternetResearchKnowledgeAdapter(created);
                contextArtifacts = new ContextArtifactRetrievalAdapter(created);
                KnowledgeMigrationService.MigrationReport migration = new KnowledgeMigrationService(engine, LEGACY_MEMORY).migrate();
                if (migration.created() > 0 || !migration.failures().isEmpty()) {
                    LocalModelRuntimeLog.write("knowledge_migration", "created=" + migration.created() + " skipped=" + migration.skipped()
                            + " archived=" + migration.archived() + (migration.failures().isEmpty() ? "" : " failures=" + migration.failures()));
                }
                population = synchronizeBuiltInKnowledge(created);
                startEmbeddingRuntime(embeddings, created);
                return Optional.of(engine);
            } catch (RuntimeException failure) {
                engine = null;
                if (created != null) created.close();
                else if (embeddings != null) embeddings.close();
                LocalModelRuntimeLog.write("knowledge_unavailable", concise(failure));
                return Optional.empty();
            }
        }
    }

    public static synchronized void close() {
        if (engine == null) return;
        engine.close();
        engine = null;
        research = null;
        contextArtifacts = null;
        population = java.util.concurrent.CompletableFuture.completedFuture(null);
        builtInRecordCount = 0L;
        activeEmbeddingIdentity = null;
        neuralEmbeddingTier = false;
        embeddingBackfill = java.util.concurrent.CompletableFuture.completedFuture(0);
    }

    /** Shared session-scoped research adapter; it remains under the existing retrieval engine. */
    public static Optional<InternetResearchKnowledgeAdapter> research() {
        if (shared().isEmpty()) return Optional.empty();
        return Optional.ofNullable(research);
    }

    /** Returns only an already active context adapter; context assembly must never start retrieval or embeddings. */
    public static Optional<ContextArtifactRetrievalAdapter> contextArtifactsIfActive() {
        return Optional.ofNullable(contextArtifacts);
    }

    /** Refreshes registered tool/skill descriptors only when retrieval is already active. */
    public static synchronized void refreshBuiltInKnowledgeIfActive() {
        KoilRetrievalEngine current = engine;
        if (current == null) return;
        population = new BuiltInKnowledgeSourceAdapter(current).synchronizeStaticSources()
                .thenApply(ignored -> (Void) null)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) LocalModelRuntimeLog.write("knowledge_source_refresh_failed", concise(failure));
                });
    }

    /**
     * Ensures the automatic built-in population pass has actually finished. This
     * is useful for diagnostics and prevents an initialized-but-empty index from
     * being reported as fully ready while source synchronization is still running.
     */
    public static java.util.concurrent.CompletableFuture<Void> ensurePopulated() {
        if (shared().isEmpty()) return java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("Koil knowledge runtime is unavailable."));
        return population;
    }

    /** Waits for source population and the active embedding-space backfill used by TurboVec. */
    public static java.util.concurrent.CompletableFuture<Integer> ensureSemanticReady() {
        if (shared().isEmpty()) return java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("Koil knowledge runtime is unavailable."));
        java.util.concurrent.CompletableFuture<Integer> active = embeddingBackfill;
        return population.thenCompose(ignored -> active == null
                ? java.util.concurrent.CompletableFuture.completedFuture(0) : active);
    }

    /** Number of records covered by the latest successful built-in source synchronization. */
    public static long builtInRecordCount() {
        return builtInRecordCount;
    }

    /** Does not initialize storage or native code just to answer a status command. */
    public static String status() {
        KoilRetrievalEngine current = engine;
        if (current == null) return "Knowledge: not initialized";
        try {
            VectorIndexHealth health = current.vectorHealth();
            String detail = health.detail().isBlank() ? "" : " | " + health.detail();
            java.util.concurrent.CompletableFuture<Void> activePopulation = population;
            String populationState = activePopulation != null && !activePopulation.isDone() ? " | Population: syncing"
                    : activePopulation != null && activePopulation.isCompletedExceptionally() ? " | Population: failed"
                    : " | Population: ready";
            java.util.concurrent.CompletableFuture<Integer> activeBackfill = embeddingBackfill;
            boolean warming = (activePopulation != null && !activePopulation.isDone())
                    || (activeBackfill != null && !activeBackfill.isDone());
            EmbeddingIdentity identity = activeEmbeddingIdentity;
            String embeddingTier = identity == null ? "unavailable"
                    : neuralEmbeddingTier ? "neural:" + identity.modelId()
                    : "local-feature:" + identity.modelId();
            KoilRetrievalEngine.EmbeddingBackfillProgress progress = current.embeddingBackfillProgress();
            String semanticState;
            if (neuralEmbeddingTier) {
                semanticState = health.entryCount() > 0L && health.ready() ? "active" : warming ? "warming" : "embedding-unavailable";
            } else {
                semanticState = health.entryCount() > 0L && health.ready()
                        ? "approximate (local dense feature retrieval)"
                        : warming ? "warming local dense feature retrieval" : "local dense unavailable";
            }
            String backfillState = progress.running() || progress.total() > 0
                    ? " | Backfill: " + progress.completed() + "/" + progress.total() + " (" + progress.percent() + "%)"
                    : "";
            return "Knowledge: " + health.state().name().toLowerCase(Locale.ROOT)
                    + " | Index: " + health.backend()
                    + " | Built-in records: " + builtInRecordCount
                    + " | Dense vectors: " + health.entryCount()
                    + " | Embedding tier: " + embeddingTier
                    + " | Semantic acceleration: " + semanticState + backfillState + populationState + detail;
        } catch (RuntimeException failure) {
            return "Knowledge: unavailable | " + concise(failure);
        }
    }

    private static String concise(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static EmbeddingProvider embeddingProvider() {
        LocalModelSelection selection = EmbeddingModelSelectionStore.load();
        if (selection != null && selection.complete()
                && "hf-qwen-qwen3-embedding-4b".equals(selection.catalogId())) {
            try {
                EmbeddingIdentity identity = new EmbeddingIdentity("llama_cpp", selection.modelId(),
                        "f4602530db1d980e16da9d7d3a70294cf5c190be", 2_560, true);
                return new LlamaCppEmbeddingRuntime(selection, ColibriConfigurationStore.loadOrCreate().apiKey(), identity);
            } catch (RuntimeException failure) {
                LocalModelRuntimeLog.write("embedding_neural_unavailable", concise(failure) + "; using local dense feature embeddings");
            }
        }
        return new LocalFeatureEmbeddingProvider();
    }

    private static void startEmbeddingRuntime(EmbeddingProvider provider, KoilRetrievalEngine created) {
        if (provider == null) return;
        java.util.concurrent.CompletableFuture<Void> ready;
        if (provider instanceof LlamaCppEmbeddingRuntime runtime) {
            ready = runtime.start().whenComplete((ignored, failure) -> {
                if (failure != null) LocalModelRuntimeLog.write("embedding_unavailable", concise(failure));
            });
        } else {
            ready = java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        embeddingBackfill = ready.thenCompose(ignored -> population)
                .thenCompose(ignored -> {
                    if (engine != created) return java.util.concurrent.CompletableFuture.completedFuture(0);
                    return created.backfillMissingEmbeddings();
                })
                .whenComplete((count, failure) -> {
                    if (failure != null) {
                        LocalModelRuntimeLog.write("knowledge_embedding_backfill_failed", concise(failure));
                    } else {
                        LocalModelRuntimeLog.write("knowledge_embedding_backfill", "entries=" + (count == null ? 0 : count)
                                + " identity=" + provider.identity().cacheKey());
                    }
                });
    }

    private static java.util.concurrent.CompletableFuture<Void> synchronizeBuiltInKnowledge(KoilRetrievalEngine created) {
        created.expireEntries(System.currentTimeMillis());
        java.util.concurrent.CompletableFuture<Void> builtIns = new BuiltInKnowledgeSourceAdapter(created)
                .synchronizeStaticSources().handle((results, failure) -> {
                    if (failure != null) {
                        LocalModelRuntimeLog.write("knowledge_source_unavailable", concise(failure));
                        throw new java.util.concurrent.CompletionException(failure);
                    }
                    long synchronizedRecords = results.stream()
                            .mapToLong(result -> (long) result.unchanged() + result.created() + result.updated())
                            .sum();
                    builtInRecordCount = synchronizedRecords;
                    if (synchronizedRecords <= 0L) {
                        throw new java.util.concurrent.CompletionException(
                                new IllegalStateException("Built-in knowledge synchronization completed without any records."));
                    }
                    LocalModelRuntimeLog.write("knowledge_source_sync", "sources=" + results.size()
                            + " records=" + synchronizedRecords
                            + " denseVectors=" + created.vectorHealth().entryCount());
                    return (Void) null;
                });
        // Minecraft facts are supplemental and client-thread-bound. Do not hold core knowledge
        // readiness hostage to the client snapshot. They continue populating asynchronously.
        new MinecraftKnowledgeSourceAdapter(created).synchronizeWhenReady().whenComplete((result, failure) -> {
            if (failure != null) {
                LocalModelRuntimeLog.write("minecraft_knowledge_source_unavailable", concise(failure));
                return;
            }
            LocalModelRuntimeLog.write("minecraft_knowledge_source_sync", "created=" + result.created()
                    + " updated=" + result.updated() + " entries=" + created.vectorHealth().entryCount());
        });
        return builtIns;
    }
}
