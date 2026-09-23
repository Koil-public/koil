package com.spirit.koil.api.model.retrieval;

import com.spirit.koil.api.context.ContextArtifact;
import com.spirit.koil.api.context.ContextRepresentationLevel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Behavioral proof that context artifacts use the shared scoped hybrid retrieval authority. */
public final class ContextArtifactRetrievalAdapterProof {
    private ContextArtifactRetrievalAdapterProof() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("koil-context-retrieval-proof-");
        EmbeddingIdentity identity = new EmbeddingIdentity("proof", "context", "r1", 8, true);
        try (KnowledgeMetadataStore store = new SqliteKnowledgeMetadataStore(directory.resolve("knowledge.db"), identity);
             FlatJavaVectorIndex vectors = new FlatJavaVectorIndex(8);
             KoilRetrievalEngine engine = new KoilRetrievalEngine(store, new ProofEmbeddings(identity), vectors)) {
            ContextArtifactRetrievalAdapter adapter = new ContextArtifactRetrievalAdapter(engine);
            ContextArtifact first = artifact("ctx-proof-one", "request-one", "engine diagnostic unrelated\npostgres schema migration is the requested evidence");
            ContextArtifact second = artifact("ctx-proof-two", "request-two", "postgres schema from another request must remain isolated");
            adapter.record(first).join();
            adapter.record(second).join();

            List<String> selected = adapter.select(first, "postgres schema", 128).join();
            require(selected.stream().anyMatch(value -> value.contains("requested evidence")),
                    "selected context retrieval must return the matching artifact segment");
            require(selected.stream().noneMatch(value -> value.contains("another request")),
                    "selected context retrieval must not cross artifact/session scope");
            require(adapter.search("request-one", "postgres schema", 4).join().equals(List.of(first.id())),
                    "cross-artifact context search must rank only matching references from its request scope");

            adapter.release(first).join();
            require(adapter.select(first, "postgres schema", 128).join().isEmpty(),
                    "released context artifacts must disappear from the shared retrieval authority");
        }
        System.out.println("ContextArtifactRetrievalAdapterProof: PASS");
    }

    private static ContextArtifact artifact(String id, String scope, String content) {
        Instant now = Instant.now();
        return new ContextArtifact(id, scope, "hash-" + id, "proof", id, content,
                ContextRepresentationLevel.L0_EXACT, now, now.plusSeconds(300));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
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
        public CompletableFuture<List<float[]>> embed(List<String> texts) {
            return CompletableFuture.completedFuture(texts.stream().map(text -> text.toLowerCase(java.util.Locale.ROOT).contains("postgres")
                    ? new float[] {1F, 0F, 0F, 0F, 0F, 0F, 0F, 0F}
                    : new float[] {0F, 1F, 0F, 0F, 0F, 0F, 0F, 0F}).toList());
        }
    }
}
