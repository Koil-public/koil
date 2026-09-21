package com.spirit.koil.api.model.retrieval;

import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors verified products of reasoning into Koil's shared retrieval authority.
 *
 * <p>The authoritative fast-route memo store remains separate because it carries
 * strict freshness/argument-reuse semantics. This adapter makes the verified
 * conclusion semantically retrievable alongside docs, Minecraft knowledge,
 * conversations, tool experience, research, workspace files, and other knowledge.
 * Raw chain-of-thought is never stored.</p>
 */
public final class SemanticReasoningKnowledgeAdapter {
    private SemanticReasoningKnowledgeAdapter() {
    }

    public static void recordVerified(String stableKey, String cue, String semanticSignature,
                                      String toolId, JsonObject arguments, String evidenceSummary,
                                      String freshness, int successes, int failures,
                                      long verifiedAtMillis) {
        KoilRetrievalEngine engine = KoilKnowledgeRuntime.shared().orElse(null);
        if (engine == null) return;
        String key = clean(stableKey);
        String intent = clean(cue);
        String tool = clean(toolId);
        if (key.isEmpty() || intent.isEmpty() || tool.isEmpty()) return;

        String sourceId = sourceId(key);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("sourceId", sourceId);
        metadata.put("sourceKey", "verified_conclusion");
        metadata.put("kind", "verified_reasoning_conclusion");
        metadata.put("tool", tool);
        metadata.put("semanticSignature", clean(semanticSignature));
        metadata.put("arguments", arguments == null ? "{}" : arguments.toString());
        metadata.put("freshness", clean(freshness));
        metadata.put("successes", Integer.toString(Math.max(0, successes)));
        metadata.put("failures", Integer.toString(Math.max(0, failures)));

        StringBuilder text = new StringBuilder("Verified reusable reasoning conclusion.")
                .append("\nIntent: ").append(intent)
                .append("\nTool: ").append(tool);
        String signature = clean(semanticSignature);
        if (!signature.isEmpty()) text.append("\nIntent pattern: ").append(signature);
        if (arguments != null && !arguments.entrySet().isEmpty()) {
            text.append("\nCanonical arguments: ").append(arguments);
        }
        String evidence = clean(evidenceSummary);
        if (!evidence.isEmpty()) text.append("\nVerified result: ").append(evidence);

        double confidence = confidence(successes, failures);
        KnowledgeEntry entry = new KnowledgeEntry(
                1L,
                KnowledgeType.REASONING_MEMO,
                "reasoning-memo",
                text.toString(),
                "reasoning.memo",
                "",
                Math.max(0L, verifiedAtMillis),
                0.78D,
                confidence,
                KnowledgeTrust.HISTORICAL_CONTEXT,
                Map.copyOf(metadata)
        );
        engine.synchronizeSource(new KnowledgeSourceSnapshot(sourceId, digest(entry.text() + metadata), List.of(entry)))
                .exceptionally(failure -> null);
    }

    public static void remove(String stableKey) {
        KoilRetrievalEngine engine = KoilKnowledgeRuntime.shared().orElse(null);
        String key = clean(stableKey);
        if (engine == null || key.isEmpty()) return;
        engine.synchronizeSource(new KnowledgeSourceSnapshot(sourceId(key), "removed", List.of()))
                .exceptionally(failure -> null);
    }

    private static double confidence(int successes, int failures) {
        int success = Math.max(0, successes);
        int failure = Math.max(0, failures);
        double evidence = success <= 0 ? 0.55D : Math.min(0.97D, 0.64D + Math.log1p(success) * 0.08D);
        double penalty = Math.min(0.35D, failure * 0.07D);
        return Math.max(0.25D, Math.min(0.98D, evidence - penalty));
    }

    private static String sourceId(String key) {
        return "reasoning.memo:" + digest(key);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String digest(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
