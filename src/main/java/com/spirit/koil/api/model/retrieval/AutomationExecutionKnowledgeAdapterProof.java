package com.spirit.koil.api.model.retrieval;

import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Proves one verified failure-to-recovery trajectory remains retrievable as historical strategy evidence. */
public final class AutomationExecutionKnowledgeAdapterProof {
    private AutomationExecutionKnowledgeAdapterProof() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("koil-execution-experience-");
        EmbeddingIdentity identity = new EmbeddingIdentity("proof", "execution", "r1", 8, true);
        try (KnowledgeMetadataStore store = new SqliteKnowledgeMetadataStore(directory.resolve("knowledge.db"), identity);
             FlatJavaVectorIndex vectors = new FlatJavaVectorIndex(8);
             KoilRetrievalEngine engine = new KoilRetrievalEngine(store, new ProofEmbeddings(identity), vectors)) {
            AutomationExecutionKnowledgeAdapter adapter = new AutomationExecutionKnowledgeAdapter(engine);
            ModelToolCall call = new ModelToolCall("scrape-recovery", "internet.scrape", new JsonObject());
            ModelToolResult result = new ModelToolResult("scrape-recovery", "internet.scrape", "completed",
                    new JsonObject(), "", "structured records recovered");
            long id = adapter.record("extract model names from a rendered catalogue", "kms-proof", 2L, call, result,
                    true, "internet.fetch=failed(failure=insufficient_content) -> internet.scrape=completed").join();

            AutomationExecutionKnowledgeAdapter.ExperienceSnapshot snapshot = adapter
                    .relevantExperience("rendered catalogue fields missing from normal fetch", "kms-proof-next", 320).join();
            require(snapshot.experiences().size() == 1, "verified recovery experience must be retrievable");
            AutomationExecutionExperience experience = snapshot.experiences().get(0);
            require(experience.knowledgeId() == id && experience.verified() && experience.objectiveCompleted(),
                    "retrieved experience must preserve verified completion state");
            require("internet.fetch|insufficient_content".equals(experience.recoverySignature())
                            && "internet.scrape".equals(experience.recoverySequence()),
                    "retrieved experience must preserve the bounded recovery strategy");
            require(snapshot.contextText().contains("internet.scrape"),
                    "recovery context must retain the selected recovery tool");
            require(store.find(id).orElseThrow().trust() == KnowledgeTrust.HISTORICAL_CONTEXT,
                    "stored recovery must not become current tool or page truth");
        }
        System.out.println("AutomationExecutionKnowledgeAdapterProof: PASS");
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
        public java.util.concurrent.CompletableFuture<List<float[]>> embed(List<String> texts) {
            return java.util.concurrent.CompletableFuture.completedFuture(texts.stream().map(ignored -> {
                float[] vector = new float[8];
                vector[0] = 1.0F;
                return vector;
            }).toList());
        }
    }
}
