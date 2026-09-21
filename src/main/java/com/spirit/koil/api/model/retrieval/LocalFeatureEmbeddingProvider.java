package com.spirit.koil.api.model.retrieval;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Always-available, zero-model-cost dense retrieval tier.
 *
 * <p>This is intentionally not presented as a neural semantic embedding model. It projects normalized
 * token, token-bigram, and character-ngram features into a deterministic dense space so TurboVec can
 * accelerate fuzzy/lexical association even when no dedicated embedding GGUF is installed. A selected
 * neural embedding runtime supersedes this provider automatically.</p>
 */
public final class LocalFeatureEmbeddingProvider implements EmbeddingProvider {
    public static final int DIMENSIONS = 1536;
    private static final EmbeddingIdentity IDENTITY = new EmbeddingIdentity(
            "koil", "local-feature-embedding", "v4-corpus-anchor-dedup-derivational-char34", DIMENSIONS, true);

    @Override
    public EmbeddingIdentity identity() {
        return IDENTITY;
    }

    @Override
    public CompletableFuture<List<float[]>> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return CompletableFuture.completedFuture(List.of());
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) vectors.add(embedOne(text));
        return CompletableFuture.completedFuture(List.copyOf(vectors));
    }

    static float[] embedOne(String value) {
        String normalized = normalizeText(value);
        float[] vector = new float[DIMENSIONS];
        if (normalized.isEmpty()) {
            add(vector, "<empty>", 1.0F);
            normalize(vector);
            return vector;
        }

        String[] tokens = normalized.split(" ");
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        String raw = value == null ? "" : value;
        int lineBreak = raw.indexOf('\n');
        String head = normalizeText(lineBreak >= 0 ? raw.substring(0, lineBreak) : raw);
        addHeadFeatures(vector, head, seen);
        for (int index = 0; index < tokens.length; index++) {
            String token = tokens[index];
            if (token.isBlank()) continue;
            // Long documents frequently repeat domain words hundreds of times (for example "minecraft").
            // Cap unigram/affix contribution per document so repetition cannot dominate cosine similarity.
            if (seen.add("w:" + token)) add(vector, "w:" + token, 1.0F);
            String stem = lightStem(token);
            if (!stem.equals(token) && seen.add("s:" + stem)) add(vector, "s:" + stem, 0.55F);
            String derived = derivationalStem(stem);
            if (!derived.equals(stem) && seen.add("d:" + derived)) add(vector, "d:" + derived, 0.48F);
            addAffixesOnce(vector, token, seen);
            addTokenNgrams(vector, token, seen);
            if (index + 1 < tokens.length && !tokens[index + 1].isBlank()) {
                String feature = "b:" + token + ' ' + tokens[index + 1];
                if (seen.add(feature)) add(vector, feature, 1.20F);
            }
            if (index + 2 < tokens.length && !tokens[index + 2].isBlank()) {
                String feature = "g2:" + token + ' ' + tokens[index + 2];
                if (seen.add(feature)) add(vector, feature, 0.52F);
            }
        }

        String acronym = acronym(tokens);
        if (acronym.length() >= 2) add(vector, "a:" + acronym, 0.65F);

        String compact = normalized.replace(" ", "_");
        if (compact.length() <= 96) {
            String padded = "^" + compact + "$";
            java.util.Set<String> grams = new java.util.LinkedHashSet<>();
            for (int index = 0; index + 2 < padded.length(); index++) {
                String gram = "c3:" + padded.substring(index, index + 3);
                if (grams.add(gram)) add(vector, gram, 0.15F);
            }
            for (int index = 0; index + 3 < padded.length(); index++) {
                String gram = "c4:" + padded.substring(index, index + 4);
                if (grams.add(gram)) add(vector, gram, 0.11F);
            }
        }
        normalize(vector);
        return vector;
    }

    private static String normalizeText(String value) {
        String text = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC);
        text = text.replaceAll("([\\p{Ll}\\d])([\\p{Lu}])", "$1 $2").toLowerCase(Locale.ROOT);
        text = text.replace('_', ' ').replace('-', ' ');
        text = text.replaceAll("[^\\p{L}\\p{N}:/+.]+", " ");
        return text.replaceAll("\\s+", " ").strip();
    }

    private static void addAffixesOnce(float[] vector, String token, java.util.Set<String> seen) {
        if (token.length() < 4) return;
        int max = Math.min(7, token.length() - 1);
        for (int size = 3; size <= max; size++) {
            float weight = size <= 4 ? 0.09F : 0.055F;
            String prefix = "p:" + token.substring(0, size);
            String suffix = "x:" + token.substring(token.length() - size);
            if (seen.add(prefix)) add(vector, prefix, weight);
            if (seen.add(suffix)) add(vector, suffix, weight);
        }
    }

    private static void addHeadFeatures(float[] vector, String head, java.util.Set<String> seen) {
        if (head == null || head.isBlank()) return;
        String clipped = head.length() > 160 ? head.substring(0, 160) : head;
        String[] tokens = clipped.split(" ");
        for (String token : tokens) {
            if (token.isBlank()) continue;
            String unigram = "h:" + token;
            if (seen.add(unigram)) add(vector, unigram, 1.35F);
            String stem = derivationalStem(lightStem(token));
            String stemFeature = "hs:" + stem;
            if (!stem.isBlank() && seen.add(stemFeature)) add(vector, stemFeature, 0.70F);
            String padded = "^" + token + "$";
            for (int index = 0; index + 2 < padded.length(); index++) {
                String feature = "h3:" + padded.substring(index, index + 3);
                if (seen.add(feature)) add(vector, feature, 0.42F);
            }
        }
    }

    private static void addTokenNgrams(float[] vector, String token, java.util.Set<String> seen) {
        if (token.length() < 4) return;
        String padded = "^" + token + "$";
        for (int index = 0; index + 2 < padded.length(); index++) {
            String feature = "t3:" + padded.substring(index, index + 3);
            if (seen.add(feature)) add(vector, feature, 0.28F);
        }
        for (int index = 0; index + 3 < padded.length(); index++) {
            String feature = "t4:" + padded.substring(index, index + 4);
            if (seen.add(feature)) add(vector, feature, 0.20F);
        }
    }

    private static String acronym(String[] tokens) {
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            if (token == null || token.isBlank()) continue;
            char first = token.charAt(0);
            if (Character.isLetterOrDigit(first)) out.append(first);
            if (out.length() >= 12) break;
        }
        return out.toString();
    }

    private static String lightStem(String token) {
        if (token.length() > 5 && token.endsWith("ies")) return token.substring(0, token.length() - 3) + "y";
        if (token.length() > 5 && token.endsWith("ing")) return token.substring(0, token.length() - 3);
        if (token.length() > 4 && token.endsWith("ed")) return token.substring(0, token.length() - 2);
        if (token.length() > 4 && token.endsWith("es")) return token.substring(0, token.length() - 2);
        if (token.length() > 3 && token.endsWith("s")) return token.substring(0, token.length() - 1);
        return token;
    }

    private static String derivationalStem(String token) {
        if (token.length() > 7 && token.endsWith("ation")) return token.substring(0, token.length() - 5);
        if (token.length() > 7 && token.endsWith("ition")) return token.substring(0, token.length() - 5);
        if (token.length() > 6 && token.endsWith("ment")) return token.substring(0, token.length() - 4);
        if (token.length() > 6 && token.endsWith("ness")) return token.substring(0, token.length() - 4);
        if (token.length() > 6 && token.endsWith("able")) return token.substring(0, token.length() - 4);
        if (token.length() > 5 && token.endsWith("ive")) return token.substring(0, token.length() - 3);
        if (token.length() > 5 && token.endsWith("ity")) return token.substring(0, token.length() - 3);
        if (token.length() > 5 && token.endsWith("er")) return token.substring(0, token.length() - 2);
        if (token.length() > 5 && token.endsWith("or")) return token.substring(0, token.length() - 2);
        return token;
    }

    private static void add(float[] vector, String feature, float weight) {
        long hash = hash64(feature);
        int slot = (int) Long.remainderUnsigned(hash, vector.length);
        float sign = (hash & (1L << 63)) == 0L ? 1.0F : -1.0F;
        vector[slot] += weight * sign;
    }

    private static long hash64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        return hash;
    }

    private static void normalize(float[] vector) {
        double squared = 0.0D;
        for (float value : vector) squared += value * value;
        if (squared <= 0.0D) {
            vector[0] = 1.0F;
            return;
        }
        float scale = (float) (1.0D / Math.sqrt(squared));
        for (int index = 0; index < vector.length; index++) vector[index] *= scale;
    }
}
