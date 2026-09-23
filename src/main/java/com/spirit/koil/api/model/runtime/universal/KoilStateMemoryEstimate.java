package com.spirit.koil.api.model.runtime.universal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Architecture-neutral estimate of persistent attention K/V state for one runtime context.
 *
 * This is deliberately an estimate, not an allocation promise. It consumes retained model
 * metadata only and never invents missing geometry. Hybrid architectures benefit from per-layer
 * head-count arrays because layers with zero KV heads contribute no attention-cache bytes.
 */
public record KoilStateMemoryEstimate(
        long fullPrecisionBytes,
        long keyFp16Bytes,
        long valueFp16Bytes,
        long q8KeyBytes,
        long q8KeySavingsBytes,
        Confidence confidence,
        String evidence
) {
    private static final double Q8_0_TO_F16_RATIO = 34.0 / 64.0; // 32 q8 values + scale vs 32 f16 values.

    public enum Confidence { UNKNOWN, LOW, MEDIUM, HIGH }

    public KoilStateMemoryEstimate {
        fullPrecisionBytes = Math.max(0L, fullPrecisionBytes);
        keyFp16Bytes = Math.max(0L, keyFp16Bytes);
        valueFp16Bytes = Math.max(0L, valueFp16Bytes);
        q8KeyBytes = Math.max(0L, q8KeyBytes);
        q8KeySavingsBytes = Math.max(0L, q8KeySavingsBytes);
        confidence = confidence == null ? Confidence.UNKNOWN : confidence;
        evidence = evidence == null ? "" : evidence.strip();
    }

    public static KoilStateMemoryEstimate unknown(String evidence) {
        return new KoilStateMemoryEstimate(0L, 0L, 0L, 0L, 0L, Confidence.UNKNOWN, evidence);
    }

    public boolean known() {
        return confidence != Confidence.UNKNOWN && fullPrecisionBytes > 0L;
    }

    public static KoilStateMemoryEstimate fromPlanDecisions(Map<String, String> decisions, int contextTokens) {
        if (decisions == null || decisions.isEmpty()) return unknown("model state geometry unavailable");
        java.util.LinkedHashMap<String, String> metadata = new java.util.LinkedHashMap<>();
        decisions.forEach((key, value) -> {
            if (key != null && key.startsWith("modelStateMeta.")) {
                metadata.put(key.substring("modelStateMeta.".length()), value);
            }
        });
        return fromMetadata(metadata, contextTokens);
    }

    public static KoilStateMemoryEstimate fromMetadata(Map<String, String> metadata, int contextTokens) {
        if (metadata == null || metadata.isEmpty() || contextTokens <= 0) {
            return unknown("model state geometry unavailable");
        }

        int blocks = firstInt(metadata, ".block_count", ".layer_count");
        List<Integer> kvHeads = firstIntList(metadata, ".attention.head_count_kv");
        List<Integer> queryHeads = firstIntList(metadata, ".attention.head_count");
        int embedding = firstInt(metadata, ".embedding_length");
        int keyLength = firstInt(metadata, ".attention.key_length");
        int valueLength = firstInt(metadata, ".attention.value_length");

        if (blocks <= 0 && !kvHeads.isEmpty()) blocks = kvHeads.size();
        if (blocks <= 0) return unknown("block count unavailable");

        int queryHeadScalar = scalarOrFirstPositive(queryHeads);
        if (keyLength <= 0 && embedding > 0 && queryHeadScalar > 0) keyLength = embedding / queryHeadScalar;
        if (valueLength <= 0) valueLength = keyLength;
        if (keyLength <= 0 || valueLength <= 0) {
            return unknown("attention head width unavailable");
        }

        long kvHeadSum;
        Confidence confidence;
        String headEvidence;
        if (kvHeads.size() > 1) {
            int count = Math.min(blocks, kvHeads.size());
            kvHeadSum = 0L;
            for (int i = 0; i < count; i++) kvHeadSum += Math.max(0, kvHeads.get(i));
            confidence = count == blocks ? Confidence.HIGH : Confidence.MEDIUM;
            headEvidence = "per-layer KV-head metadata " + count + "/" + blocks + " blocks";
        } else {
            int scalar = scalarOrFirstPositive(kvHeads);
            if (scalar <= 0) return unknown("KV-head count unavailable");
            kvHeadSum = (long) scalar * blocks;
            confidence = Confidence.MEDIUM;
            headEvidence = "scalar KV-head metadata across " + blocks + " blocks";
        }

        if (kvHeadSum <= 0L) return unknown("no attention KV heads declared");
        long context = Math.max(1, contextTokens);
        long keyElements = saturatingMultiply(saturatingMultiply(context, kvHeadSum), keyLength);
        long valueElements = saturatingMultiply(saturatingMultiply(context, kvHeadSum), valueLength);
        long keyFp16 = saturatingMultiply(keyElements, 2L);
        long valueFp16 = saturatingMultiply(valueElements, 2L);
        long full = saturatingAdd(keyFp16, valueFp16);
        long q8Key = Math.max(0L, Math.round(keyFp16 * Q8_0_TO_F16_RATIO));
        long savings = Math.max(0L, keyFp16 - q8Key);
        String evidence = headEvidence + "; key_width=" + keyLength + "; value_width=" + valueLength
                + (embedding > 0 ? "; embedding=" + embedding : "");
        return new KoilStateMemoryEstimate(full, keyFp16, valueFp16, q8Key, savings, confidence, evidence);
    }

    private static int firstInt(Map<String, String> metadata, String... suffixes) {
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            for (String suffix : suffixes) {
                if (key.endsWith(suffix)) {
                    List<Integer> values = parseIntList(entry.getValue());
                    int scalar = scalarOrFirstPositive(values);
                    if (scalar > 0) return scalar;
                }
            }
        }
        return 0;
    }

    private static List<Integer> firstIntList(Map<String, String> metadata, String suffix) {
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            if (!key.endsWith(suffix)) continue;
            List<Integer> parsed = parseIntList(entry.getValue());
            if (!parsed.isEmpty()) return parsed;
        }
        return List.of();
    }

    private static List<Integer> parseIntList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String cleaned = raw.strip();
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        String[] parts = cleaned.split(",");
        ArrayList<Integer> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            try {
                result.add(Math.max(0, Integer.parseInt(part.strip())));
            } catch (NumberFormatException ignored) {
                return List.of();
            }
        }
        return List.copyOf(result);
    }

    private static int scalarOrFirstPositive(List<Integer> values) {
        for (int value : values) if (value > 0) return value;
        return 0;
    }

    private static long saturatingMultiply(long a, long b) {
        if (a <= 0L || b <= 0L) return 0L;
        if (a > Long.MAX_VALUE / b) return Long.MAX_VALUE;
        return a * b;
    }

    private static long saturatingAdd(long a, long b) {
        if (a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }
}
