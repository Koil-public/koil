package com.spirit.koil.api.model.retrieval;

import com.spirit.koil.api.model.deepthought.DeepThoughtSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Stores only the final public conclusion of a completed Deep Thought investigation. */
public final class DeepThoughtKnowledgeAdapter {
    private final KoilRetrievalEngine engine;

    public DeepThoughtKnowledgeAdapter(KoilRetrievalEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public CompletableFuture<Long> recordCompleted(DeepThoughtSession session) {
        if (session == null || session.lifecycle != DeepThoughtSession.Lifecycle.COMPLETED) {
            return CompletableFuture.completedFuture(0L);
        }
        String conclusion = clean(session.finalConclusion);
        if (conclusion.isEmpty()) return CompletableFuture.completedFuture(0L);
        String sourceId = "deep-thought:" + session.deepThoughtSessionId;
        KnowledgeEntry entry = new KnowledgeEntry(1L, KnowledgeType.USER_KNOWLEDGE, "deep-thought",
                text(session, conclusion), "deep-thought.finding", session.deepThoughtSessionId,
                Math.max(session.updatedAtMillis, session.createdAtMillis), 0.6D, confidence(session),
                KnowledgeTrust.HISTORICAL_CONTEXT, Map.of(
                        "sourceId", sourceId,
                        "sourceKey", "final",
                        "deepThoughtSessionId", session.deepThoughtSessionId,
                        "kind", "completed_finding",
                        "confidence", clean(session.confidence)
                ));
        return this.engine.synchronizeSource(new KnowledgeSourceSnapshot(sourceId, revision(entry.text()), java.util.List.of(entry)))
                .thenCompose(ignored -> this.engine.sourceEntryId(sourceId, "final"));
    }

    private static String text(DeepThoughtSession session, String conclusion) {
        String objective = clean(session.normalizedObjective);
        if (objective.isEmpty()) objective = clean(session.originalQuestion);
        return "Completed Deep Thought finding. Historical research context only; verify current authoritative state."
                + "\nObjective: " + (objective.isEmpty() ? "unspecified" : objective)
                + "\nConclusion: " + conclusion
                + "\nConfidence: " + (clean(session.confidence).isEmpty() ? "unresolved" : clean(session.confidence))
                + "\nRecorded limitations: " + session.limitations.size();
    }

    private static double confidence(DeepThoughtSession session) {
        return Math.max(0.25D, Math.min(0.9D, session.evidenceCoveragePercent / 100.0D));
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String revision(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
