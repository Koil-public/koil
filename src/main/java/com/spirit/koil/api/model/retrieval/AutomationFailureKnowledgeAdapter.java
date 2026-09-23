package com.spirit.koil.api.model.retrieval;

import com.spirit.koil.api.automation.feedback.AutomationFeedbackNode;
import com.spirit.koil.api.automation.feedback.AutomationFailureType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stores only human-confirmed Automation failure reports as historical recovery reference. */
public final class AutomationFailureKnowledgeAdapter {
    private final KoilRetrievalEngine engine;

    public AutomationFailureKnowledgeAdapter(KoilRetrievalEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public CompletableFuture<Long> recordHumanConfirmedFailure(AutomationFeedbackNode node, AutomationFailureType failure) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(failure, "failure");
        if (failure.id().isBlank()) return CompletableFuture.completedFuture(0L);
        String sourceId = "automation.feedback:" + digest(key(node, failure));
        Map<String, String> metadata = new LinkedHashMap<>(metadata(node, failure));
        metadata.put("sourceId", sourceId);
        metadata.put("sourceKey", "confirmed_failure");
        KnowledgeEntry entry = new KnowledgeEntry(1L, KnowledgeType.AUTOMATION_FAILURE, "automation", text(node, failure),
                "automation.feedback", node.taskId(), System.currentTimeMillis(), 0.7D, 0.75D,
                KnowledgeTrust.HISTORICAL_CONTEXT, metadata);
        return this.engine.synchronizeSource(new KnowledgeSourceSnapshot(sourceId, digest(entry.text()), List.of(entry)))
                .thenCompose(ignored -> this.engine.sourceEntryId(sourceId, "confirmed_failure"));
    }

    private static String text(AutomationFeedbackNode node, AutomationFailureType failure) {
        String rules = String.join("; ", failure.suggested_fix_rules());
        return "Human-confirmed automation failure.\nObjective: " + value(node.label())
                + "\nTool/source: " + value(node.source())
                + "\nInputs: " + value(node.inputs())
                + "\nObserved: " + value(node.value())
                + "\nFailure type: " + failure.id()
                + (rules.isBlank() ? "" : "\nSuggested recovery for verification: " + rules);
    }

    private static Map<String, String> metadata(AutomationFeedbackNode node, AutomationFailureType failure) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("failureType", failure.id());
        metadata.put("nodeType", value(node.nodeType()));
        metadata.put("tool", value(node.source()));
        metadata.put("verification", "human_confirmed");
        return Map.copyOf(metadata);
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "unknown" : value.strip();
    }

    private static String key(AutomationFeedbackNode node, AutomationFailureType failure) {
        return value(node.taskId()) + '\u0000' + value(node.rowId()) + '\u0000' + value(node.nodeId())
                + '\u0000' + value(node.source()) + '\u0000' + failure.id();
    }

    private static String digest(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
