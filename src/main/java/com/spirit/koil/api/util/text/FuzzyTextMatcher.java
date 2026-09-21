package com.spirit.koil.api.util.text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Small deterministic fuzzy matcher for model-facing names and identifiers.
 *
 * <p>This is intentionally dependency-free and conservative. It treats spaces,
 * dashes, underscores, slashes and namespace separators as token boundaries,
 * tolerates light inflection and spelling mistakes, and rewards ordered token
 * coverage. Callers remain responsible for confidence/margin thresholds before
 * using a match for a consequential action.</p>
 */
public final class FuzzyTextMatcher {
    private FuzzyTextMatcher() {}

    /** 0..1000, where 1000 is an exact normalized match. */
    public static int score(String query, String candidate) {
        String q = normalize(query);
        String c = normalize(candidate);
        if (q.isBlank() || c.isBlank()) return 0;
        if (q.equals(c)) return 1000;

        String qCompact = compact(q);
        String cCompact = compact(c);
        if (qCompact.equals(cCompact)) return 980;
        if (c.startsWith(q)) return 900;
        if (c.contains(q)) return 850;
        if (cCompact.contains(qCompact) && qCompact.length() >= 3) return 825;

        List<String> qTokens = tokens(q);
        List<String> cTokens = tokens(c);
        if (qTokens.isEmpty() || cTokens.isEmpty()) return 0;

        int matched = 0;
        int tokenScore = 0;
        for (String qt : qTokens) {
            int best = 0;
            for (String ct : cTokens) {
                best = Math.max(best, tokenSimilarity(qt, ct));
            }
            if (best >= 640) matched++;
            tokenScore += best;
        }
        double coverage = (double) matched / qTokens.size();
        if (coverage <= 0.0D) return 0;

        int average = tokenScore / qTokens.size();
        int result = (int) Math.round(average * (0.55D + 0.45D * coverage));
        if (orderedSubsequence(qTokens, cTokens)) result += 55;
        if (matched == qTokens.size()) result += 55;
        // Prefer the smallest candidate that fully explains the query. This
        // prevents a broad value such as "enchanted golden apple" from tying
        // the more direct "golden apple" when the model searched "gold apple".
        if (matched == qTokens.size() && cTokens.size() > qTokens.size()) {
            result -= Math.min(180, (cTokens.size() - qTokens.size()) * 140);
        }
        return Math.max(0, Math.min(970, result));
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        return ascii
                .replace('&', ' ')
                .replaceAll("[:/\\\\._\\-]+", " ")
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    public static List<String> tokens(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String token : normalized.split(" ")) {
            if (token.isBlank()) continue;
            out.add(token);
            String singular = singular(token);
            if (!singular.equals(token)) out.add(singular);
        }
        return List.copyOf(out);
    }

    private static int tokenSimilarity(String left, String right) {
        if (left.equals(right)) return 1000;
        String a = singular(left);
        String b = singular(right);
        if (a.equals(b)) return 970;
        if (a.length() >= 3 && (a.contains(b) || b.contains(a))) return 850;
        int distance = levenshtein(a, b);
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1000;
        double similarity = 1.0D - (double) distance / max;
        if (max <= 3 && distance > 1) return 0;
        if (max <= 5 && distance > 2) return 0;
        return (int) Math.round(Math.max(0.0D, similarity) * 900.0D);
    }

    private static boolean orderedSubsequence(List<String> query, List<String> candidate) {
        int cursor = 0;
        for (String q : query) {
            boolean found = false;
            while (cursor < candidate.size()) {
                if (tokenSimilarity(q, candidate.get(cursor++)) >= 640) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static String compact(String value) {
        return value.replace(" ", "");
    }

    private static String singular(String token) {
        if (token == null || token.length() < 4) return token == null ? "" : token;
        if (token.endsWith("ies") && token.length() > 4) return token.substring(0, token.length() - 3) + "y";
        if (token.endsWith("sses") || token.endsWith("shes") || token.endsWith("ches") || token.endsWith("xes") || token.endsWith("zes")) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("s") && !token.endsWith("ss")) return token.substring(0, token.length() - 1);
        return token;
    }

    private static int levenshtein(String a, String b) {
        if (a.equals(b)) return 0;
        if (a.isEmpty()) return b.length();
        if (b.isEmpty()) return a.length();
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
