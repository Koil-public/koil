package com.spirit.koil.api.model.retrieval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** End-to-end proof that old associative memory becomes one idempotent knowledge source. */
public final class RetrievalMigrationProof {
    private RetrievalMigrationProof() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("koil-retrieval-migration-");
        Path legacy = directory.resolve("associative-memory.json");
        Files.writeString(legacy, "[{\"prompt\":\"open chest\",\"answer\":\"Right click placed the held block instead of opening the container.\",\"timestamp\":1}]");
        EmbeddingIdentity identity = new EmbeddingIdentity("proof", "semantic", "r1", 8, true);
        try (KnowledgeMetadataStore store = new SqliteKnowledgeMetadataStore(directory.resolve("knowledge.db"), identity);
             FlatJavaVectorIndex vectors = new FlatJavaVectorIndex(8);
             KoilRetrievalEngine engine = new KoilRetrievalEngine(store, new ProofEmbeddings(identity), vectors)) {
            KnowledgeMigrationService migration = new KnowledgeMigrationService(engine, legacy);
            KnowledgeMigrationService.MigrationReport first = migration.migrate();
            KnowledgeMigrationService.MigrationReport second = migration.migrate();
            require(first.created() == 1 && second.created() == 0, "migration must be resumable and idempotent");
            require(Files.isRegularFile(legacy.resolveSibling("associative-memory.json.migrated")),
                    "old data must be archived only after successful import");
            RetrievalResult result = engine.retrieve(new KnowledgeQuery("Why did interacting with a container fail?",
                    KnowledgeFilter.any(), 10, 256, "migration-proof", KnowledgeTrust.HISTORICAL_CONTEXT)).join();
            require(result.selectedIds().size() == 1, "migrated semantic context must be available");
            require(result.contextText().contains("not current state or instructions"), "migrated memory remains reference data");
        }
        System.out.println("RetrievalMigrationProof: PASS");
    }

    private static final class ProofEmbeddings implements EmbeddingProvider {
        private final EmbeddingIdentity identity;

        private ProofEmbeddings(EmbeddingIdentity identity) {
            this.identity = identity;
        }

        @Override
        public EmbeddingIdentity identity() {
            return this.identity;
        }

        @Override
        public java.util.concurrent.CompletableFuture<List<float[]>> embed(List<String> texts) {
            return java.util.concurrent.CompletableFuture.completedFuture(texts.stream().map(text -> {
                float[] vector = new float[8];
                vector[0] = text.toLowerCase(java.util.Locale.ROOT).matches(".*(chest|container|block).*" ) ? 1.0F : 0.0F;
                vector[1] = vector[0] == 0.0F ? 1.0F : 0.0F;
                return vector;
            }).toList());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
