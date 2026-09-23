package com.spirit.koil.api.model.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ensures a full identifier query is narrowed by the symbolic index rather than scanning the corpus. */
public final class ExactKnowledgeIndexProof {
    private ExactKnowledgeIndexProof() {
    }

    public static void main(String[] args) {
        List<KnowledgeEntry> entries = new ArrayList<>();
        entries.add(entry(1L, "minecraft:diamond_shovel"));
        for (long id = 2L; id <= 50_000L; id++) entries.add(entry(id, "documentation-record-" + id));
        ExactKnowledgeIndex index = ExactKnowledgeIndex.from(entries);
        long started = System.nanoTime();
        List<ExactKnowledgeIndex.Match> matches = index.search("minecraft:diamond_shovel", Set.of(), 1);
        long elapsed = System.nanoTime() - started;
        require(matches.size() == 1 && matches.get(0).id() == 1L, "exact identifier must preserve its stable ID");
        require(elapsed < 150_000_000L, "full identifier lookup must be indexed, not corpus-linear: " + elapsed + "ns");
        System.out.println("ExactKnowledgeIndexProof: PASS");
    }

    private static KnowledgeEntry entry(long id, String text) {
        return new KnowledgeEntry(id, KnowledgeType.KOIL_DOCUMENTATION, "proof", text, "proof", "", id,
                0.5D, 0.5D, KnowledgeTrust.DOCUMENTATION, Map.of());
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
