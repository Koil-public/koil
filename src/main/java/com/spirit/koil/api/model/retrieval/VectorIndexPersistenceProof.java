package com.spirit.koil.api.model.retrieval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Fallback lifecycle proof: SQLite vectors restore a usable index when native TurboVec cannot load. */
public final class VectorIndexPersistenceProof {
    private VectorIndexPersistenceProof() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("koil-vector-index-proof-");
        EmbeddingIdentity identity = new EmbeddingIdentity("proof", "vectors", "r1", 8, true);
        KnowledgeEntry entry = new KnowledgeEntry(77L, KnowledgeType.AUTOMATION_FAILURE, "minecraft", "Container target mismatch",
                "proof", "", 1L, 1.0D, 1.0D, KnowledgeTrust.HISTORICAL_CONTEXT, Map.of());
        try (KnowledgeMetadataStore store = new SqliteKnowledgeMetadataStore(directory.resolve("knowledge.db"), identity)) {
            store.upsert(entry, vector());
            try (VectorIndexManager manager = VectorIndexManager.open(store, directory.resolve("vectors.tvim"), 4)) {
                require(manager.index().search(vector(), new VectorSearchRequest(1, Set.of(77L), 0)).get(0).id() == 77L,
                        "metadata vectors must initialize the selected index");
                require(manager.health().backend().equals("flat-java") || manager.health().backend().equals("turbovec"),
                        "manager must expose the selected backend truthfully");
            }
            try (VectorIndexManager manager = VectorIndexManager.open(store, directory.resolve("vectors.tvim"), 4)) {
                require(manager.index().search(vector(), new VectorSearchRequest(1, Set.of(77L), 0)).get(0).id() == 77L,
                        "restart must restore vectors from authoritative SQLite metadata");
            }
            KnowledgeIndexMaintenance.RebuildResult rebuild = KnowledgeIndexMaintenance.rebuild(store, directory.resolve("vectors.tvim"), 4);
            if (TurboVecNativeBridge.availability().available()) {
                require(rebuild.rebuilt() && rebuild.entries() == 1L,
                        "a one-entry store must rebuild as valid uncalibrated TurboQuant");
            } else {
                require(!rebuild.detail().isBlank(), "native rebuild unavailability must be explicit and non-fatal");
            }
        }
        System.out.println("VectorIndexPersistenceProof: PASS");
    }

    private static float[] vector() {
        float[] value = new float[8];
        value[0] = 1.0F;
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
