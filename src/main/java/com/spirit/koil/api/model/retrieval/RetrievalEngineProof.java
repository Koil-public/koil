package com.spirit.koil.api.model.retrieval;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Standalone contract proof; no Minecraft client or native runtime required. */
public final class RetrievalEngineProof {
    private RetrievalEngineProof() {
    }

    public static void main(String[] args) {
        rejectsInvalidStableIdsAndVectors();
        preservesImmutableContractValues();
        System.out.println("Retrieval engine proof passed.");
    }

    private static void rejectsInvalidStableIdsAndVectors() {
        requireThrows(() -> new KnowledgeEntry(0L, KnowledgeType.CONVERSATION, "general", "text", "test", "", 1L,
                0.5D, 0.5D, KnowledgeTrust.HISTORICAL_CONTEXT, Map.of()));
        requireThrows(() -> new VectorSearchRequest(0, Set.of(), 0));
        requireThrows(() -> new VectorSearchRequest(4, Set.of(1L), -1));
        requireThrows(() -> new VectorSearchResult(1L, Float.NaN));
    }

    private static void preservesImmutableContractValues() {
        KnowledgeEntry entry = new KnowledgeEntry(41L, KnowledgeType.MINECRAFT_KNOWLEDGE, "minecraft", "minecraft:diamond_shovel",
                "registry", "", 123L, 0.8D, 0.9D, KnowledgeTrust.DOCUMENTATION, Map.of("namespace", "minecraft"));
        require(entry.id() == 41L && entry.metadata().get("namespace").equals("minecraft"),
                "knowledge entry lost stable metadata");
        VectorSearchRequest request = new VectorSearchRequest(5, Set.of(41L), 2);
        require(request.allowedIds().equals(Set.of(41L)), "allowlist was not retained");
        VectorIndexHealth health = VectorIndexHealth.ready("flat-java", 1L, 8);
        require(health.ready() && health.entryCount() == 1L && health.dimensions() == 8,
                "index health contract was not stable");
        EmbeddingIdentity identity = new EmbeddingIdentity("llama_cpp", "qwen3-embedding-0.6b", "test", 1024, true);
        require(identity.cacheKey().contains("qwen3-embedding-0.6b"), "embedding identity cache key was unstable");
        VectorIndex index = new VectorIndex() {
            @Override public void add(long id, float[] embedding) { }
            @Override public boolean remove(long id) { return false; }
            @Override public List<VectorSearchResult> search(float[] query, VectorSearchRequest searchRequest) { return List.of(); }
            @Override public void sync() { }
            @Override public VectorIndexHealth health() { return health; }
            @Override public void close() { }
        };
        require(index.health().ready(), "vector index API cannot expose health");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void requireThrows(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }
}
