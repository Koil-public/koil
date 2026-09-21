package com.spirit.koil.api.model.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Explainable rank fusion; exact symbolic evidence receives a bounded tie-breaking boost. */
public final class ReciprocalRankFusion {
    private static final double RANK_OFFSET = 60.0D;

    private ReciprocalRankFusion() {
    }

    public static List<RetrievalCandidate> fuse(Map<Long, KnowledgeEntry> entries,
                                                List<VectorSearchResult> semantic,
                                                List<ExactKnowledgeIndex.Match> exact) {
        Map<Long, Scores> scores = new HashMap<>();
        if (semantic != null) for (int index = 0; index < semantic.size(); index++) {
            VectorSearchResult result = semantic.get(index);
            if (!entries.containsKey(result.id())) continue;
            Scores score = scores.computeIfAbsent(result.id(), ignored -> new Scores());
            score.semantic = result.score();
            // Rank remains the primary dense signal, while the cosine/dense magnitude provides a small
            // bounded confidence contribution. This prevents weak random ANN neighbors from looking equal
            // to genuinely close vectors merely because both happened to occupy rank 1.
            score.fused += reciprocal(index + 1) + semanticStrength(result.score());
        }
        if (exact != null) for (int index = 0; index < exact.size(); index++) {
            ExactKnowledgeIndex.Match result = exact.get(index);
            if (!entries.containsKey(result.id())) continue;
            Scores score = scores.computeIfAbsent(result.id(), ignored -> new Scores());
            score.exact = result.score();
            // Never add the raw symbolic score directly. Scores like 530/700/1000 are categorical
            // evidence, not probabilities, and the former /10000 boost overwhelmed the entire dense rank.
            score.fused += reciprocal(index + 1) + exactStrength(result);
        }
        List<RetrievalCandidate> candidates = new ArrayList<>();
        scores.forEach((id, score) -> {
            KnowledgeEntry entry = entries.get(id);
            candidates.add(new RetrievalCandidate(entry, score.semantic, score.exact,
                    score.fused + provenancePrior(entry), RetrievalContextBuilder.estimateTokens(entry.text())));
        });
        candidates.sort(Comparator.comparingDouble(RetrievalCandidate::fusedScore).reversed()
                .thenComparingLong(candidate -> candidate.entry().id()));
        return List.copyOf(candidates);
    }


    private static double semanticStrength(float score) {
        if (!Float.isFinite(score) || score <= 0.0F) return 0.0D;
        return Math.min(0.0100D, score * 0.0200D);
    }

    private static double exactStrength(ExactKnowledgeIndex.Match match) {
        if (match == null) return 0.0D;
        return switch (match.category()) {
            case FULL_IDENTIFIER -> 0.0120D;
            case EXACT_PHRASE -> 0.0090D;
            case EXACT_TOKEN -> 0.0065D;
            case FUZZY_TOKEN -> 0.0050D;
            case PREFIX_SUFFIX -> 0.0035D;
            case PATH -> 0.0045D;
            case NAMESPACE -> 0.0025D;
        };
    }

    private static double provenancePrior(KnowledgeEntry entry) {
        if (entry == null) return 0.0D;
        if (entry.type() == KnowledgeType.REPAIR_EPISODE
                && "verified_success".equals(entry.metadata().get("outcome"))) return 0.0050D;
        if (entry.type() == KnowledgeType.COUNTEREXAMPLE) return 0.0035D;
        if (entry.type() == KnowledgeType.WORKFLOW_RECIPE) return 0.0030D;
        if (entry.type() == KnowledgeType.ENVIRONMENT_FACT) return 0.0025D;
        if (entry.type() == KnowledgeType.EXTERNAL_EVIDENCE) return 0.0040D;
        if (entry.trust() == KnowledgeTrust.KOIL_DOCUMENTATION || entry.trust() == KnowledgeTrust.DOCUMENTATION) return 0.0030D;
        if (entry.trust() == KnowledgeTrust.WORKSPACE_CONTENT) return 0.0025D;
        if (entry.type() == KnowledgeType.CONVERSATION && "true".equals(entry.metadata().get("modelAuthored"))) return -0.0035D;
        return 0.0D;
    }

    private static double reciprocal(int rank) {
        return 1.0D / (RANK_OFFSET + rank);
    }

    private static final class Scores {
        private float semantic;
        private float exact;
        private double fused;
    }
}
