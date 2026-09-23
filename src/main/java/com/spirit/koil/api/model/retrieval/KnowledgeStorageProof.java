package com.spirit.koil.api.model.retrieval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small deterministic proof for durable metadata and symbolic retrieval. */
public final class KnowledgeStorageProof {
    private KnowledgeStorageProof() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("koil-knowledge-proof-");
        Path database = directory.resolve("knowledge.db");
        EmbeddingIdentity identity = new EmbeddingIdentity("proof", "embedding", "r1", 8, true);
        KnowledgeEntry shovel = entry(41L, KnowledgeType.MINECRAFT_KNOWLEDGE, "minecraft:diamond_shovel", "minecraft");
        KnowledgeEntry failure = entry(42L, KnowledgeType.AUTOMATION_FAILURE,
                "Right-clicking a chest placed the held block instead of opening the container.", "minecraft");
        KnowledgeEntry className = entry(43L, KnowledgeType.TOOL, "AutomationToolCoordinator", "koil");
        KnowledgeEntry path = entry(44L, KnowledgeType.KOIL_DOCUMENTATION, "koil/sys/model/knowledge", "docs");

        try (KnowledgeMetadataStore store = new SqliteKnowledgeMetadataStore(database, identity)) {
            store.upsert(shovel, vector(1));
            store.upsert(failure, vector(2));
            store.upsert(className, vector(3));
            store.upsert(path, vector(4));
            KnowledgeEntry pending = entry(45L, KnowledgeType.CONVERSATION, "Historical exchange pending embedding", "model");
            store.upsert(pending, null);
            require(store.activeEntriesMissingEmbedding().equals(List.of(pending)),
                    "the active embedding identity must expose only rows that need backfill");
            ExactKnowledgeIndex exact = ExactKnowledgeIndex.from(store.activeEntries());
            require(exact.search("minecraft:diamond_shovel", Set.of(41L), 5).get(0).id() == 41L,
                    "full Minecraft identifier must dominate exact search");
            require(exact.search("AutomationToolCoordinator", Set.of(), 5).get(0).id() == 43L,
                    "full class name must dominate semantically related text");
            require(exact.search("koil/sys/model/knowledge", Set.of(), 5).get(0).id() == 44L,
                    "full file path must dominate symbolic search");
            require(store.filterIds(new KnowledgeFilter(Set.of(KnowledgeType.AUTOMATION_FAILURE), Set.of(), Set.of(), Map.of(), 0L))
                            .equals(List.of(42L)),
                    "type filters must exclude unrelated records");
            require(store.filterIds(new KnowledgeFilter(Set.of(), Set.of("minecraft"), Set.of(), Map.of(), 0L)).equals(List.of(41L, 42L)),
                    "scope filters must remain stable-ID ordered");
            require(store.markDeleted(42L), "first delete must remove metadata and vector");
            require(store.find(42L).isEmpty(), "deleted entry must not remain in metadata");
            require(store.activeEmbeddings().size() == 3, "deleted entry must not retain an embedding");
            require(store.activeEntriesMissingEmbedding().equals(List.of(pending)),
                    "deletion must not hide a separate pending embedding");
            require(store.validate().valid(), "metadata/vector relationships must validate");
        }
        try (KnowledgeMetadataStore reopened = new SqliteKnowledgeMetadataStore(database, identity)) {
            require(reopened.find(41L).isPresent(), "metadata must survive restart");
            require(ExactKnowledgeIndex.from(reopened.activeEntries())
                            .search("koil/sys/model/knowledge", Set.of(), 1).get(0).id() == 44L,
                    "exact index must rebuild from authoritative metadata");
        }
        System.out.println("KnowledgeStorageProof: PASS");
    }

    private static KnowledgeEntry entry(long id, KnowledgeType type, String text, String scope) {
        return new KnowledgeEntry(id, type, scope, text, "proof", "session-1", 1_000L + id,
                0.8D, 0.9D, KnowledgeTrust.HISTORICAL_CONTEXT, Map.of("source", "proof"));
    }

    private static float[] vector(int coordinate) {
        float[] vector = new float[8];
        vector[coordinate] = 1.0F;
        return vector;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
