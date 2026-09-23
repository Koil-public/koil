package com.spirit.koil.api.model.retrieval;

import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolDefinition;
import com.spirit.koil.api.model.tool.LocalModelToolCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Focused contract for bounded hybrid tool-capability selection. */
public final class SemanticToolCapabilityRetrievalProof {
    private SemanticToolCapabilityRetrievalProof() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("koil-semantic-tool-proof-");
        EmbeddingIdentity identity = new EmbeddingIdentity("proof", "capabilities", "r1", 8, true);
        try (KnowledgeMetadataStore store = new SqliteKnowledgeMetadataStore(directory.resolve("knowledge.db"), identity);
             FlatJavaVectorIndex vectors = new FlatJavaVectorIndex(8);
             KoilRetrievalEngine engine = new KoilRetrievalEngine(store, new CapabilityEmbeddings(identity), vectors)) {
            ModelToolDefinition database = tool("integration.database.schema", "Inspect PostgreSQL database schemas and tables.");
            ModelToolDefinition files = tool("workspace.read", "Read an authorized workspace file.");
            ModelToolDefinition excluded = tool("integration.secrets", "Retrieve database passwords and tokens.");
            engine.synchronizeSource(new KnowledgeSourceSnapshot("koil.tools", "proof-v1", List.of(
                    entry("tool:" + database.id(), database),
                    entry("tool:" + files.id(), files),
                    entry("tool:" + excluded.id(), excluded)
            ))).join();

            List<ModelToolDefinition> selected = SemanticToolCapabilityRetriever.retrieve(
                    engine,
                    "inspect a postgres schema",
                    List.of(database, files),
                    "semantic-tool-proof"
            ).join();
            require(selected.size() == 1 && database.id().equals(selected.get(0).id()),
                    "semantic capability retrieval must select the relevant allowed schema only");
            require(selected.stream().noneMatch(tool -> excluded.id().equals(tool.id())),
                    "retrieval must never make a non-allowed tool callable");

            List<ModelToolDefinition> noTools = SemanticToolCapabilityRetriever.retrieve(
                    engine, "inspect a postgres schema", List.of(), "semantic-tool-proof-empty").join();
            require(noTools.isEmpty(), "an empty allowed set must stay empty");
        }
        List<ModelToolDefinition> exactCatalogue = LocalModelToolCatalog
                .resolveInformationToolsForPrompt("use the mcp-catalogue tool to find a postgres server", "semantic-tool-exact")
                .join();
        require(exactCatalogue.size() == 1 && "mcp-catalogue".equals(exactCatalogue.get(0).id()),
                "exact model-facing tool ids must win before semantic capability retrieval");
        System.out.println("SemanticToolCapabilityRetrievalProof: PASS");
    }

    private static KnowledgeEntry entry(String sourceKey, ModelToolDefinition tool) {
        return new KnowledgeEntry(1L, KnowledgeType.TOOL, "model", tool.id() + "\n" + tool.description(),
                "tool.registry", tool.id(), 1L, 0.7D, 0.9D, KnowledgeTrust.HISTORICAL_CONTEXT,
                Map.of("sourceId", "koil.tools", "sourceKey", sourceKey, "toolId", tool.id()));
    }

    private static ModelToolDefinition tool(String id, String description) {
        return new ModelToolDefinition(id, description, new JsonObject(), List.of(), Set.of(), true,
                Duration.ofSeconds(5), true, false, Set.of("completed"));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class CapabilityEmbeddings implements EmbeddingProvider {
        private final EmbeddingIdentity identity;

        private CapabilityEmbeddings(EmbeddingIdentity identity) {
            this.identity = identity;
        }

        @Override
        public EmbeddingIdentity identity() {
            return this.identity;
        }

        @Override
        public CompletableFuture<List<float[]>> embed(List<String> texts) {
            return CompletableFuture.completedFuture(texts.stream().map(this::embedOne).toList());
        }

        private float[] embedOne(String text) {
            String normalized = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
            float[] vector = new float[8];
            vector[normalized.contains("postgres") || normalized.contains("database") || normalized.contains("schema") ? 0 : 1] = 1.0F;
            return vector;
        }
    }
}
