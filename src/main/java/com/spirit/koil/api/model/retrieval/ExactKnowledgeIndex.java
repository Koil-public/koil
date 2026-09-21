package com.spirit.koil.api.model.retrieval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Compact inverted symbolic index for identifiers, class names, phrases, namespaces, and paths. */
public final class ExactKnowledgeIndex {
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "i", "me", "my", "you", "your", "we", "our", "it", "this", "that",
            "to", "of", "for", "from", "with", "without", "in", "on", "at", "by",
            "and", "or", "but", "if", "then", "than", "whether", "how", "what",
            "can", "could", "would", "should", "do", "does", "did", "please",
            "checking", "check", "find", "show", "tell"
    );
    private final Map<Long, KnowledgeEntry> entries = new LinkedHashMap<>();
    private final Map<Long, IndexedFields> indexed = new HashMap<>();
    private final Map<String, Set<Long>> full = new HashMap<>();
    private final Map<String, Set<Long>> tokens = new HashMap<>();
    private final Map<String, Set<Long>> namespaces = new HashMap<>();

    public static ExactKnowledgeIndex from(Collection<KnowledgeEntry> entries) {
        ExactKnowledgeIndex index = new ExactKnowledgeIndex();
        if (entries != null) entries.forEach(index::add);
        return index;
    }

    public void add(KnowledgeEntry entry) {
        if (entry == null) return;
        remove(entry.id());
        IndexedFields fields = IndexedFields.from(entry);
        this.entries.put(entry.id(), entry);
        this.indexed.put(entry.id(), fields);
        add(this.full, fields.full(), entry.id());
        add(this.tokens, fields.tokens(), entry.id());
        add(this.namespaces, fields.namespaces(), entry.id());
    }

    public boolean remove(long id) {
        IndexedFields fields = this.indexed.remove(id);
        KnowledgeEntry removed = this.entries.remove(id);
        if (fields == null) return removed != null;
        remove(this.full, fields.full(), id);
        remove(this.tokens, fields.tokens(), id);
        remove(this.namespaces, fields.namespaces(), id);
        return removed != null;
    }

    public List<Match> search(String query, Set<Long> allowedIds, int limit) {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isEmpty() || limit <= 0) return List.of();
        Set<Long> allowed = allowedIds == null ? Set.of() : Set.copyOf(allowedIds);
        List<Match> matches = new ArrayList<>();
        for (long id : candidates(normalizedQuery)) {
            if (!allowed.isEmpty() && !allowed.contains(id)) continue;
            KnowledgeEntry entry = this.entries.get(id);
            Match match = entry == null ? null : match(entry, normalizedQuery);
            if (match != null) matches.add(match);
        }
        // Search-engine-style typo recovery is deliberately a last resort. It only runs when the symbolic
        // inverted index found nothing, and every query token must strongly resemble a token in one field.
        if (matches.isEmpty()) {
            Collection<Long> scan = allowed.isEmpty() ? this.entries.keySet() : allowed;
            for (long id : scan) {
                KnowledgeEntry entry = this.entries.get(id);
                Match match = entry == null ? null : fuzzyMatch(entry, normalizedQuery);
                if (match != null) matches.add(match);
            }
        }
        matches.sort(Comparator.comparingInt(Match::score).reversed().thenComparingLong(Match::id));
        return List.copyOf(matches.subList(0, Math.min(limit, matches.size())));
    }

    private Set<Long> candidates(String query) {
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        add(result, this.full.get(query));
        Set<String> queryTokens = tokens(query);
        Set<Long> intersection = null;
        for (String token : queryTokens) {
            Set<Long> found = this.tokens.get(token);
            if (found == null) {
                intersection = Set.of();
                break;
            }
            if (intersection == null) intersection = new LinkedHashSet<>(found);
            else intersection.retainAll(found);
        }
        add(result, intersection);
        if (result.isEmpty()) for (String token : queryTokens) add(result, this.tokens.get(token));
        if (query.indexOf(':') >= 0) add(result, this.namespaces.get(namespace(query)));
        // Prefix/suffix is intentionally a rare fallback. Normal identifier/path/class lookups use the maps above.
        if (result.isEmpty()) for (Map.Entry<String, Set<Long>> entry : this.tokens.entrySet()) {
            for (String token : queryTokens) {
                if (entry.getKey().startsWith(token) || entry.getKey().endsWith(token)
                        || token.startsWith(entry.getKey()) || token.endsWith(entry.getKey())) {
                    add(result, entry.getValue());
                    break;
                }
            }
        }
        return result;
    }

    private static void add(Map<String, Set<Long>> index, Set<String> keys, long id) {
        for (String key : keys) index.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(id);
    }

    private static void remove(Map<String, Set<Long>> index, Set<String> keys, long id) {
        for (String key : keys) {
            Set<Long> values = index.get(key);
            if (values == null) continue;
            values.remove(id);
            if (values.isEmpty()) index.remove(key);
        }
    }

    private static void add(Set<Long> target, Set<Long> values) {
        if (values != null) target.addAll(values);
    }

    private static Match match(KnowledgeEntry entry, String query) {
        Category category = null;
        int score = 0;
        Set<String> queryTokens = tokens(query);
        for (String rawField : fields(entry)) {
            String field = normalize(rawField);
            if (field.equals(query)) return new Match(entry.id(), Category.FULL_IDENTIFIER, 1_000);
            if (field.contains(query)) {
                category = Category.EXACT_PHRASE;
                score = Math.max(score, 700 + Math.min(99, query.length()));
                continue;
            }
            Set<String> fieldTokens = tokens(rawField);
            int shared = shared(queryTokens, fieldTokens);
            if (!queryTokens.isEmpty() && shared == queryTokens.size()) {
                category = Category.EXACT_TOKEN;
                score = Math.max(score, 500 + shared * 10);
            }
            if (field.startsWith(query) || field.endsWith(query) || query.startsWith(field) || query.endsWith(field)) {
                if (category == null || category.rank() < Category.PREFIX_SUFFIX.rank()) category = Category.PREFIX_SUFFIX;
                score = Math.max(score, 300 + Math.min(99, query.length()));
            }
            if (query.indexOf(':') >= 0 && field.indexOf(':') >= 0 && namespace(query).equals(namespace(field))) {
                if (category == null || category.rank() < Category.NAMESPACE.rank()) category = Category.NAMESPACE;
                score = Math.max(score, 200 + shared * 10);
            }
            if (query.indexOf('/') >= 0 && field.indexOf('/') >= 0 && shared > 0) {
                if (category == null || category.rank() < Category.PATH.rank()) category = Category.PATH;
                score = Math.max(score, 250 + shared * 10);
            }
        }
        return category == null ? null : new Match(entry.id(), category, score);
    }

    private static Match fuzzyMatch(KnowledgeEntry entry, String query) {
        Set<String> queryTokens = tokens(query);
        if (queryTokens.isEmpty() || queryTokens.size() > 8) return null;
        double best = 0.0D;
        for (String rawField : fields(entry)) {
            Set<String> fieldTokens = tokens(rawField);
            if (fieldTokens.isEmpty()) continue;
            double total = 0.0D;
            boolean all = true;
            for (String queryToken : queryTokens) {
                double tokenBest = 0.0D;
                for (String fieldToken : fieldTokens) {
                    tokenBest = Math.max(tokenBest, tokenSimilarity(queryToken, fieldToken));
                    if (tokenBest >= 0.999D) break;
                }
                if (tokenBest < 0.75D) {
                    all = false;
                    break;
                }
                total += tokenBest;
            }
            if (all) best = Math.max(best, total / queryTokens.size());
        }
        if (best < 0.75D) return null;
        return new Match(entry.id(), Category.FUZZY_TOKEN, 400 + (int) Math.round(best * 80.0D));
    }

    private static double tokenSimilarity(String left, String right) {
        if (left.equals(right)) return 1.0D;
        int max = Math.max(left.length(), right.length());
        if (max == 0) return 1.0D;
        if (Math.min(left.length(), right.length()) < 4 && !left.equals(right)) return 0.0D;
        int distance = editDistance(left, right);
        return Math.max(0.0D, 1.0D - (double) distance / max);
    }

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static List<String> fields(KnowledgeEntry entry) {
        List<String> fields = new ArrayList<>();
        fields.add(entry.text());
        fields.add(entry.source());
        fields.add(entry.scope());

        // Only user/content-facing metadata belongs in the symbolic index. Internal snapshot/provenance
        // fields (especially sourceRevision) can contain global registry fingerprints and hashes; indexing
        // them makes every record appear to exactly match terms that only occur in the snapshot metadata.
        entry.metadata().forEach((key, value) -> {
            if (value == null || value.isBlank() || !searchableMetadataKey(key)) return;
            fields.add(value);
        });
        return fields;
    }

    private static boolean searchableMetadataKey(String key) {
        if (key == null || key.isBlank()) return false;
        return switch (key) {
            case "toolId", "documentId", "section", "path", "file", "class", "symbol",
                    "identifier", "minecraftKind", "url", "provider", "title", "name",
                    "kind", "evidenceKind", "semanticOperation", "tags", "indexId",
                    "failureSignature", "failureSummary", "recoverySequence", "workflowSequence", "outcome" -> true;
            default -> false;
        };
    }

    private static Set<String> tokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : value.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isEmpty() && !STOPWORDS.contains(token)) tokens.add(token);
        }
        return tokens;
    }

    private static int shared(Set<String> left, Set<String> right) {
        int count = 0;
        for (String token : left) if (right.contains(token)) count++;
        return count;
    }

    private static String namespace(String value) {
        int separator = value.indexOf(':');
        return separator < 0 ? "" : value.substring(0, separator);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    public enum Category {
        FULL_IDENTIFIER(7), EXACT_PHRASE(6), EXACT_TOKEN(5), FUZZY_TOKEN(4), PREFIX_SUFFIX(3), PATH(2), NAMESPACE(1);

        private final int rank;

        Category(int rank) {
            this.rank = rank;
        }

        int rank() {
            return this.rank;
        }
    }

    public record Match(long id, Category category, int score) {
        public Match {
            if (id <= 0L || category == null || score <= 0) throw new IllegalArgumentException("invalid exact match");
        }
    }

    private record IndexedFields(Set<String> full, Set<String> tokens, Set<String> namespaces) {
        private static IndexedFields from(KnowledgeEntry entry) {
            Set<String> full = new LinkedHashSet<>();
            Set<String> tokens = new LinkedHashSet<>();
            Set<String> namespaces = new LinkedHashSet<>();
            for (String field : fields(entry)) {
                String normalized = normalize(field);
                if (normalized.isEmpty()) continue;
                full.add(normalized);
                tokens.addAll(ExactKnowledgeIndex.tokens(field));
                if (normalized.indexOf(':') >= 0) namespaces.add(namespace(normalized));
            }
            return new IndexedFields(Set.copyOf(full), Set.copyOf(tokens), Set.copyOf(namespaces));
        }
    }
}
