package com.spirit.koil.api.model.tool;

import com.spirit.koil.api.model.retrieval.EmbeddingIdentity;
import com.spirit.koil.api.model.retrieval.EmbeddingProvider;
import com.spirit.koil.api.model.retrieval.FlatJavaVectorIndex;
import com.spirit.koil.api.model.retrieval.KnowledgeMetadataStore;
import com.spirit.koil.api.model.retrieval.KnowledgeQuery;
import com.spirit.koil.api.model.retrieval.KoilRetrievalEngine;
import com.spirit.koil.api.model.retrieval.SqliteKnowledgeMetadataStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Proves catalogue records use an isolated, incrementally synchronizable retrieval source. */
public final class McpCatalogueRetrievalProof {
    private McpCatalogueRetrievalProof() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("koil-catalogue-retrieval-");
        EmbeddingIdentity identity = new EmbeddingIdentity("proof", "catalogue", "r1", 8, true);
        try (KnowledgeMetadataStore store = new SqliteKnowledgeMetadataStore(directory.resolve("knowledge.db"), identity);
             FlatJavaVectorIndex vectors = new FlatJavaVectorIndex(8);
             KoilRetrievalEngine engine = new KoilRetrievalEngine(store, new CatalogueEmbeddings(identity), vectors)) {
            McpCatalogueEntry postgres = new McpCatalogueEntry("postgres", "PostgreSQL", "Inspect PostgreSQL schemas and tables.",
                    "https://github.com/example/postgres", "Databases", "r1", McpCatalogueEntry.TrustState.DISCOVERED);
            McpCatalogueEntry weather = new McpCatalogueEntry("weather", "Weather", "Read weather forecasts.",
                    "https://github.com/example/weather", "Utilities", "r1", McpCatalogueEntry.TrustState.DISCOVERED);
            engine.synchronizeSource(McpCatalogueService.retrievalSnapshot(List.of(postgres, weather), "r1")).join();
            var result = engine.retrieve(new KnowledgeQuery("database schema inspection",
                    new com.spirit.koil.api.model.retrieval.KnowledgeFilter(java.util.Set.of(com.spirit.koil.api.model.retrieval.KnowledgeType.TOOL),
                            java.util.Set.of(), java.util.Set.of(), java.util.Map.of("sourceId", "mcp.catalogue"), 0L),
                    4, 128, "catalogue-proof", com.spirit.koil.api.model.retrieval.KnowledgeTrust.UNTRUSTED_RETRIEVED)).join();
            require(!result.selected().isEmpty() && "postgres".equals(result.selected().get(0).entry().metadata().get("catalogueId")),
                    "semantic catalogue retrieval must rank the relevant discovered entry");
            require(result.selected().stream().allMatch(candidate -> candidate.entry().trust()
                            == com.spirit.koil.api.model.retrieval.KnowledgeTrust.UNTRUSTED_RETRIEVED),
                    "catalogue entries must remain untrusted discovery data");
        }
        System.out.println("McpCatalogueRetrievalProof: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class CatalogueEmbeddings implements EmbeddingProvider {
        private final EmbeddingIdentity identity;

        private CatalogueEmbeddings(EmbeddingIdentity identity) { this.identity = identity; }
        @Override public EmbeddingIdentity identity() { return this.identity; }
        @Override public CompletableFuture<List<float[]>> embed(List<String> texts) {
            return CompletableFuture.completedFuture(texts.stream().map(text -> {
                String value = text.toLowerCase(java.util.Locale.ROOT);
                float[] vector = new float[8];
                vector[value.contains("database") || value.contains("postgres") || value.contains("schema") ? 0 : 1] = 1.0F;
                return vector;
            }).toList());
        }
    }
}
