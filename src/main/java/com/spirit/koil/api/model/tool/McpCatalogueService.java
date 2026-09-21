package com.spirit.koil.api.model.tool;

import com.spirit.koil.api.model.retrieval.KnowledgeEntry;
import com.spirit.koil.api.model.retrieval.KnowledgeFilter;
import com.spirit.koil.api.model.retrieval.KnowledgeQuery;
import com.spirit.koil.api.model.retrieval.KnowledgeSourceSnapshot;
import com.spirit.koil.api.model.retrieval.KnowledgeTrust;
import com.spirit.koil.api.model.retrieval.KnowledgeType;
import com.spirit.koil.api.model.retrieval.KoilKnowledgeRuntime;
import com.spirit.koil.api.util.text.FuzzyTextMatcher;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Bounded cache and normalizer for the configured MCP discovery source. */
public final class McpCatalogueService {
    private static final URI SOURCE = URI.create("https://raw.githubusercontent.com/punkpeye/awesome-mcp-servers/main/README.md");
    private static final int MAXIMUM_SOURCE_BYTES = 2_000_000;
    private static final Duration CACHE_TTL = Duration.ofHours(6);
    private static final Pattern HEADING = Pattern.compile("^###\\s+.*?(?:<a[^>]*>)?([^<]+?)(?:</a>)?\\s*$");
    private static final Pattern ENTRY = Pattern.compile("^-\\s+\\[([^]]+)]\\((https://github\\.com/[^)]+)\\).*?(?:-\\s+)(.+)$");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static volatile Snapshot snapshot = new Snapshot(List.of(), "", Instant.EPOCH, false);
    private static volatile String indexedRevision = "";
    private static volatile CompletableFuture<Void> indexing = CompletableFuture.completedFuture(null);

    private McpCatalogueService() {}

    public static List<McpCatalogueEntry> search(String query, int limit) throws Exception {
        Snapshot active = snapshot();
        indexForRetrieval(active);
        int boundedLimit = Math.max(1, Math.min(12, limit));
        List<McpCatalogueEntry> semantic = semanticSearch(active, query, boundedLimit);
        List<McpCatalogueEntry> fuzzy = fuzzySearch(active, query, boundedLimit);
        if (semantic.isEmpty()) return fuzzy;
        if (fuzzy.isEmpty()) return semantic;

        // Semantic retrieval can be cold on the first catalogue request while
        // indexing is still happening. Merge it with deterministic fuzzy search
        // instead of treating either mechanism as all-or-nothing.
        java.util.LinkedHashMap<String, McpCatalogueEntry> merged = new java.util.LinkedHashMap<>();
        for (McpCatalogueEntry entry : fuzzy) merged.putIfAbsent(entry.stableId(), entry);
        for (McpCatalogueEntry entry : semantic) merged.putIfAbsent(entry.stableId(), entry);
        return merged.values().stream().limit(boundedLimit).toList();
    }

    /** Converts untrusted discovery records into a separate incremental source. */
    static KnowledgeSourceSnapshot retrievalSnapshot(List<McpCatalogueEntry> entries, String revision) {
        List<KnowledgeEntry> values = new ArrayList<>();
        for (McpCatalogueEntry entry : entries == null ? List.<McpCatalogueEntry>of() : entries) {
            values.add(new KnowledgeEntry(1L, KnowledgeType.TOOL, "mcp-catalogue",
                    entry.name() + "\n" + entry.description() + "\nCategory: " + entry.category(),
                    "mcp.catalogue", entry.stableId(), 0L, 0.45D, 0.45D, KnowledgeTrust.UNTRUSTED_RETRIEVED,
                    Map.of("sourceId", "mcp.catalogue", "sourceKey", "catalogue:" + entry.stableId(),
                            "catalogueId", entry.stableId(), "sourceRevision", entry.sourceRevision())));
        }
        return new KnowledgeSourceSnapshot("mcp.catalogue", revision == null || revision.isBlank() ? "unknown" : revision, values);
    }

    private static void indexForRetrieval(Snapshot active) {
        if (active.revision().isBlank() || active.revision().equals(indexedRevision)) return;
        synchronized (McpCatalogueService.class) {
            if (active.revision().equals(indexedRevision) || !indexing.isDone()) return;
            indexing = CompletableFuture.supplyAsync(KoilKnowledgeRuntime::shared)
                    .thenCompose(runtime -> runtime
                            .map(engine -> engine.synchronizeSource(retrievalSnapshot(active.entries(), active.revision()))
                                    .thenApply(ignored -> (Void) null))
                            .orElseGet(() -> CompletableFuture.failedFuture(
                                    new IllegalStateException("Koil retrieval runtime is unavailable"))));
            indexing.whenComplete((ignored, failure) -> {
                if (failure == null) indexedRevision = active.revision();
            });
        }
    }

    private static List<McpCatalogueEntry> semanticSearch(Snapshot active, String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        return KoilKnowledgeRuntime.shared().map(engine -> {
            try {
                Map<String, McpCatalogueEntry> entries = new java.util.LinkedHashMap<>();
                for (McpCatalogueEntry entry : active.entries()) entries.put(entry.stableId(), entry);
                return engine.retrieve(new KnowledgeQuery(query,
                                new KnowledgeFilter(java.util.Set.of(KnowledgeType.TOOL), java.util.Set.of(), java.util.Set.of(),
                                        Map.of("sourceId", "mcp.catalogue"), 0L),
                                Math.max(1, Math.min(12, limit)), 256, "mcp-catalogue", KnowledgeTrust.UNTRUSTED_RETRIEVED))
                        .get(750L, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .selected().stream()
                        .map(candidate -> entries.get(candidate.entry().metadata().get("catalogueId")))
                        .filter(java.util.Objects::nonNull)
                        .toList();
            } catch (Exception ignored) {
                return List.<McpCatalogueEntry>of();
            }
        }).orElseGet(List::of);
    }

    public static List<CategorySummary> categories(int limit) throws Exception {
        Snapshot active = snapshot();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (McpCatalogueEntry entry : active.entries()) {
            String category = entry.category() == null || entry.category().isBlank() ? "Uncategorized" : entry.category();
            counts.merge(category, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> new CategorySummary(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(CategorySummary::count).reversed().thenComparing(CategorySummary::name))
                .limit(Math.max(1, Math.min(64, limit)))
                .toList();
    }

    public static McpCatalogueEntry details(String stableId) throws Exception {
        if (stableId == null || stableId.isBlank()) return null;
        Snapshot active = snapshot();
        String requested = stableId.strip();
        McpCatalogueEntry exact = active.entries().stream()
                .filter(entry -> entry.stableId().equalsIgnoreCase(requested)
                        || entry.name().equalsIgnoreCase(requested)
                        || entry.repositoryUrl().equalsIgnoreCase(requested))
                .findFirst().orElse(null);
        if (exact != null) return exact;
        return active.entries().stream()
                .map(entry -> new ScoredEntry(entry, detailScore(requested, entry)))
                .filter(match -> match.score() >= 650)
                .sorted(Comparator.comparingInt(ScoredEntry::score).reversed())
                .map(ScoredEntry::entry)
                .findFirst().orElse(null);
    }

    static List<McpCatalogueEntry> parse(String markdown, String revision) {
        ArrayList<McpCatalogueEntry> entries = new ArrayList<>();
        String category = "";
        for (String line : markdown == null ? List.<String>of() : markdown.lines().toList()) {
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) { category = clean(heading.group(1)); continue; }
            Matcher item = ENTRY.matcher(line);
            if (!item.matches()) continue;
            String name = clean(item.group(1));
            String repository = item.group(2).replaceAll("/$", "");
            String description = clean(item.group(3));
            if (name.isBlank() || repository.isBlank()) continue;
            entries.add(new McpCatalogueEntry("mcp-catalogue-" + shortHash(repository), name, description,
                    repository, category, revision, McpCatalogueEntry.TrustState.DISCOVERED));
        }
        return List.copyOf(entries);
    }

    private static synchronized Snapshot snapshot() throws Exception {
        Snapshot current = snapshot;
        if (!current.entries().isEmpty() && current.loadedAt().plus(CACHE_TTL).isAfter(Instant.now())) return current;
        PublicNetworkPolicy.validate(SOURCE);
        HttpRequest request = HttpRequest.newBuilder(SOURCE).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Koil-Mcp-Catalogue/1.0").GET().build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("Catalogue source returned HTTP " + response.statusCode() + ".");
        byte[] bytes;
        try (InputStream body = response.body()) { bytes = body.readNBytes(MAXIMUM_SOURCE_BYTES + 1); }
        if (bytes.length > MAXIMUM_SOURCE_BYTES) throw new IllegalStateException("Catalogue source exceeded the configured size limit.");
        String text = new String(bytes, StandardCharsets.UTF_8);
        String revision = response.headers().firstValue("etag").orElseGet(() -> shortHash(text));
        snapshot = new Snapshot(parse(text, revision), revision, Instant.now(), true);
        return snapshot;
    }

    private static List<McpCatalogueEntry> fuzzySearch(Snapshot active, String query, int limit) {
        String normalized = FuzzyTextMatcher.normalize(query);
        if (normalized.isBlank()) return List.of();
        List<String> terms = usefulTerms(normalized);
        return active.entries().stream()
                .map(entry -> new ScoredEntry(entry, score(entry, normalized, terms)))
                .filter(match -> match.score() >= 300)
                .sorted(Comparator.comparingInt(ScoredEntry::score).reversed()
                        .thenComparing(match -> match.entry().name()))
                .limit(limit)
                .map(ScoredEntry::entry)
                .toList();
    }

    private static int score(McpCatalogueEntry entry, String normalizedQuery, List<String> terms) {
        String name = entry.name();
        String description = entry.description();
        String category = entry.category();
        String repository = entry.repositoryUrl();
        int score = Math.max(
                FuzzyTextMatcher.score(normalizedQuery, name),
                Math.max(FuzzyTextMatcher.score(normalizedQuery, category),
                        FuzzyTextMatcher.score(normalizedQuery, name + " " + description))
        );
        String haystack = FuzzyTextMatcher.normalize(name + " " + description + " " + category + " " + repository);
        int covered = 0;
        int termScore = 0;
        for (String term : terms) {
            int best = FuzzyTextMatcher.score(term, haystack);
            if (haystack.contains(term)) best = Math.max(best, 900);
            if (FuzzyTextMatcher.normalize(name).contains(term)) best = Math.max(best, 980);
            if (best >= 560) covered++;
            termScore += best;
        }
        if (!terms.isEmpty()) {
            double coverage = (double) covered / terms.size();
            int average = termScore / terms.size();
            score = Math.max(score, (int) Math.round(average * (0.50D + 0.50D * coverage)));
            if (covered > 0) score += Math.min(180, covered * 45);
        }
        return Math.min(1200, score);
    }

    private static int detailScore(String query, McpCatalogueEntry entry) {
        return Math.max(FuzzyTextMatcher.score(query, entry.name()),
                Math.max(FuzzyTextMatcher.score(query, entry.repositoryUrl()),
                        FuzzyTextMatcher.score(query, entry.stableId())));
    }

    private static List<String> usefulTerms(String normalized) {
        java.util.Set<String> stop = java.util.Set.of(
                "mcp", "server", "servers", "tool", "tools", "plugin", "plugins", "find", "search",
                "look", "looking", "need", "want", "something", "anything", "that", "which", "with",
                "for", "the", "a", "an", "to", "of", "and", "or", "can", "could", "would", "please"
        );
        java.util.ArrayList<String> terms = new java.util.ArrayList<>();
        for (String token : FuzzyTextMatcher.tokens(normalized)) {
            if (token.length() >= 2 && !stop.contains(token)) terms.add(token);
        }
        return List.copyOf(terms);
    }

    public record CategorySummary(String name, int count) {}

    private record ScoredEntry(McpCatalogueEntry entry, int score) {}

    private static String clean(String value) { return value == null ? "" : value.replaceAll("\\s+", " ").strip(); }

    private static String shortHash(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int index = 0; index < 8; index++) out.append(String.format("%02x", digest[index]));
            return out.toString();
        } catch (Exception failure) { throw new IllegalStateException("SHA-256 is unavailable.", failure); }
    }

    private record Snapshot(List<McpCatalogueEntry> entries, String revision, Instant loadedAt, boolean complete) {}
}
