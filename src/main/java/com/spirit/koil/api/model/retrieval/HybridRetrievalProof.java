package com.spirit.koil.api.model.retrieval;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic semantic, exact, trust, and budget proof for the Java fallback path. */
public final class HybridRetrievalProof {
    private HybridRetrievalProof() {
    }

    public static void main(String[] args) {
        KnowledgeEntry chestFailure = entry(7L, KnowledgeType.AUTOMATION_FAILURE,
                "Right-clicking the chest placed the held block instead of opening the container.", KnowledgeTrust.HISTORICAL_CONTEXT);
        KnowledgeEntry toolClass = entry(8L, KnowledgeType.TOOL, "AutomationToolCoordinator", KnowledgeTrust.KOIL_DOCUMENTATION);
        KnowledgeEntry automationNote = entry(9L, KnowledgeType.AUTOMATION,
                "Automation coordinator routes a plan to execution.", KnowledgeTrust.HISTORICAL_CONTEXT);
        KnowledgeEntry oldState = entry(10L, KnowledgeType.CONVERSATION, "Chest is open.", KnowledgeTrust.HISTORICAL_CONTEXT);

        try (FlatJavaVectorIndex vectors = new FlatJavaVectorIndex(8)) {
            vectors.add(7L, vector(0));
            vectors.add(8L, vector(1));
            vectors.add(9L, vector(0));
            vectors.add(10L, vector(2));
            List<VectorSearchResult> semantic = vectors.search(vector(0), new VectorSearchRequest(5, Set.of(), 0));
            require(semantic.get(0).id() == 7L,
                    "container failure must rank semantically despite weak lexical overlap");
            require(vectors.search(vector(0), new VectorSearchRequest(5, Set.of(7L), 0)).size() == 1,
                    "allowlist must exclude unrelated dense candidates before ranking");
            for (long id = 100L; id <= 1_100L; id++) vectors.add(id, vector((int) (id % 8L)));
            require(vectors.health().entryCount() == 1_005L,
                    "one-at-a-time ingestion from 1 through 1000+ must not require a rebuild");

            ExactKnowledgeIndex exact = ExactKnowledgeIndex.from(List.of(chestFailure, toolClass, automationNote, oldState));
            List<RetrievalCandidate> fused = ReciprocalRankFusion.fuse(
                    Map.of(7L, chestFailure, 8L, toolClass, 9L, automationNote, 10L, oldState),
                    vectors.search(vector(0), new VectorSearchRequest(5, Set.of(7L, 8L, 9L, 10L), 0)),
                    exact.search("AutomationToolCoordinator", Set.of(), 5));
            require(fused.get(0).entry().id() == 8L,
                    "exact class name must outrank semantically similar automation text");
            RetrievalResult budgeted = RetrievalContextBuilder.build(fused, 42);
            require(budgeted.contextTokens() <= 42, "selected retrieval context must honor caller budget");
            RetrievalResult oldStateContext = RetrievalContextBuilder.build(List.of(new RetrievalCandidate(oldState, 0.0F, 0.0F, 1.0D, 4)), 64);
            require(oldStateContext.contextText().startsWith("[Retrieved historical reference"),
                    "historical memory must be labeled as reference rather than current state");
            require(!oldStateContext.contextText().contains("Current state"),
                    "historical memory must not be injected as authoritative current state");
        }
        System.out.println("HybridRetrievalProof: PASS");
    }

    private static KnowledgeEntry entry(long id, KnowledgeType type, String text, KnowledgeTrust trust) {
        return new KnowledgeEntry(id, type, "proof", text, "proof", "", id, 0.8D, 0.9D, trust, Map.of());
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
