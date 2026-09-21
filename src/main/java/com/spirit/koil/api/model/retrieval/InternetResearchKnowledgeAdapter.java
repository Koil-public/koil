package com.spirit.koil.api.model.retrieval;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.model.ModelToolCall;
import com.spirit.koil.api.model.ModelToolResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Provenance-preserving external evidence memory backed by Koil's shared retrieval engine.
 *
 * <p>Only completed tool output is admitted. Model prose is never accepted here. Records keep
 * source URL/provider/tool-call provenance, remain UNTRUSTED_RETRIEVED, and expire so dynamic
 * public data cannot silently become permanent truth.</p>
 */
public final class InternetResearchKnowledgeAdapter {
    private static final Duration DEFAULT_TTL = Duration.ofHours(6);
    private static final int MAXIMUM_SESSION_DOCUMENTS = 192;
    private static final int MAXIMUM_DOCUMENT_CHARACTERS = 2_400;
    private static final Set<String> SUPPORTED_PREFIXES = Set.of("internet.", "browser.", "content.", "dataset.");
    private final KoilRetrievalEngine engine;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final Map<String, Session> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    public InternetResearchKnowledgeAdapter(KoilRetrievalEngine engine) {
        this(engine, DEFAULT_TTL, System::currentTimeMillis);
    }

    InternetResearchKnowledgeAdapter(KoilRetrievalEngine engine, Duration ttl, LongSupplier clock) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.ttlMillis = requireTtl(ttl);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Records only completed external-tool evidence and leaves the caller's tool result untouched. */
    public CompletableFuture<SourceSyncResult> record(String sessionId, ModelToolCall call, ModelToolResult result) {
        String sessionScope = normalized(sessionId);
        if (sessionScope.isEmpty() || call == null || result == null || !"completed".equals(result.status())
                || !supported(call.toolId())) {
            return CompletableFuture.completedFuture(new SourceSyncResult(0, 0, 0, 0));
        }
        long now = this.clock.getAsLong();
        List<Document> documents = documents(call, result, now);
        if (documents.isEmpty()) return expire().thenApply(ignored -> new SourceSyncResult(0, 0, 0, 0));
        Session session = this.sessions.compute(sessionScope, (ignored, prior) -> {
            List<Document> merged = new ArrayList<>(prior == null ? List.of() : prior.documents());
            merged.addAll(documents);
            // Deduplicate by exact source-content hash while preserving newest evidence.
            LinkedHashMap<String, Document> deduplicated = new LinkedHashMap<>();
            for (Document document : merged) deduplicated.put(document.contentHash(), document);
            List<Document> values = new ArrayList<>(deduplicated.values());
            if (values.size() > MAXIMUM_SESSION_DOCUMENTS) values = values.subList(values.size() - MAXIMUM_SESSION_DOCUMENTS, values.size());
            return new Session(now + this.ttlMillis, List.copyOf(values));
        });
        return expire().thenCompose(ignored -> this.engine.synchronizeSource(snapshot(sessionScope, session)));
    }

    /** Retrieves bounded external evidence from one active request/session scope. */
    public CompletableFuture<RetrievalResult> retrieve(String sessionId, String query, int contextTokenBudget) {
        String scope = normalized(sessionId);
        String text = normalized(query);
        if (scope.isEmpty() || text.isEmpty()) return CompletableFuture.completedFuture(new RetrievalResult(List.of(), List.of(), "", 0));
        return expire().thenCompose(ignored -> this.engine.retrieve(new KnowledgeQuery(text,
                new KnowledgeFilter(Set.of(KnowledgeType.EXTERNAL_EVIDENCE), Set.of("external-evidence"),
                        Set.of(scope), Map.of("sourceId", sourceId(scope)), 0L),
                16, Math.max(0, Math.min(3_072, contextTokenBudget)), "external-evidence:" + digest(scope),
                KnowledgeTrust.UNTRUSTED_RETRIEVED)));
    }

    /** Removes expired source snapshots from the authoritative store. */
    public CompletableFuture<Integer> expire() {
        long now = this.clock.getAsLong();
        List<String> expired = this.sessions.entrySet().stream()
                .filter(entry -> entry.getValue().expiresAtMillis() <= now)
                .map(Map.Entry::getKey).toList();
        CompletableFuture<?>[] removals = expired.stream().map(scope -> {
            this.sessions.remove(scope);
            return this.engine.synchronizeSource(new KnowledgeSourceSnapshot(sourceId(scope), "expired", List.of()));
        }).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(removals)
                .thenCompose(ignored -> this.engine.expireEntries(now))
                .thenApply(ignored -> expired.size());
    }

    public CompletableFuture<Void> release(String sessionId) {
        String scope = normalized(sessionId);
        if (scope.isEmpty()) return CompletableFuture.completedFuture(null);
        this.sessions.remove(scope);
        return this.engine.synchronizeSource(new KnowledgeSourceSnapshot(sourceId(scope), "released", List.of())).thenApply(ignored -> null);
    }

    private static KnowledgeSourceSnapshot snapshot(String sessionId, Session session) {
        List<KnowledgeEntry> entries = new ArrayList<>();
        for (int index = 0; index < session.documents().size(); index++) {
            Document document = session.documents().get(index);
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("sourceId", sourceId(sessionId));
            metadata.put("sourceKey", document.contentHash());
            metadata.put("url", document.url());
            metadata.put("provider", document.provider());
            metadata.put("contentHash", document.contentHash());
            metadata.put("chunkPosition", Integer.toString(index));
            metadata.put("retrievedAtMillis", Long.toString(document.retrievedAtMillis()));
            metadata.put("expiresAtMillis", Long.toString(session.expiresAtMillis()));
            metadata.put("toolId", document.toolId());
            metadata.put("toolCallId", document.callId());
            metadata.put("evidenceKind", document.kind());
            metadata.put("origin", "external_tool_result");
            metadata.put("modelAuthored", "false");
            entries.add(new KnowledgeEntry(1L, KnowledgeType.EXTERNAL_EVIDENCE, "external-evidence", document.text(),
                    "external.evidence", sessionId, document.retrievedAtMillis(), 0.55D, 0.60D,
                    KnowledgeTrust.UNTRUSTED_RETRIEVED, metadata));
        }
        return new KnowledgeSourceSnapshot(sourceId(sessionId), digest(session.documents().toString() + session.expiresAtMillis()), entries);
    }

    private static List<Document> documents(ModelToolCall call, ModelToolResult result, long now) {
        JsonObject output = result.output();
        String provider = primitive(output, "provider", providerFallback(call.toolId()));
        List<Document> values = new ArrayList<>();
        collect(values, output, "", provider, call, now, 0);
        // Some diagnostics have useful factual payload but no URL. Preserve them with a tool URI
        // so provenance remains explicit without pretending they came from a website.
        if (values.isEmpty() && !output.entrySet().isEmpty()) {
            String body = compact(output.toString());
            if (!body.isBlank()) add(values, "tool://" + call.toolId(), provider, call.toolId(), body,
                    call.toolId(), call.id(), "structured_tool_output", now);
        }
        return List.copyOf(values);
    }

    private static void collect(List<Document> target, JsonElement element, String inheritedUrl, String provider,
                                ModelToolCall call, long now, int depth) {
        if (element == null || depth > 5 || target.size() >= 64) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collect(target, child, inheritedUrl, provider, call, now, depth + 1);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        String url = firstPrimitive(object, inheritedUrl, "url", "href", "sourceUrl", "startUrl", "canonicalUrl");
        String title = firstPrimitive(object, "", "title", "name", "field", "heading");
        String body = firstPrimitive(object, "", "snippet", "extract", "text", "content", "body", "description", "value");
        if (body.isBlank() && object.has("values") && object.get("values").isJsonArray()) body = primitiveValues(object.getAsJsonArray("values"));
        if (!body.isBlank() && (!url.isBlank() || !title.isBlank())) {
            String effectiveUrl = url.isBlank() ? "tool://" + call.toolId() : url;
            add(target, effectiveUrl, provider, title, body, call.toolId(), call.id(), evidenceKind(call.toolId()), now);
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (Set.of("url", "href", "sourceUrl", "startUrl", "canonicalUrl", "title", "name", "field", "heading",
                    "snippet", "extract", "text", "content", "body", "description", "value", "values").contains(entry.getKey())) continue;
            if (entry.getValue().isJsonArray() || entry.getValue().isJsonObject()) {
                collect(target, entry.getValue(), url, provider, call, now, depth + 1);
            }
        }
    }

    private static void add(List<Document> target, String url, String provider, String title, String body,
                            String toolId, String callId, String kind, long retrievedAtMillis) {
        String content = compact((normalized(title).isEmpty() ? "" : normalized(title) + "\n") + normalized(body));
        if (url.isBlank() || content.isBlank()) return;
        String hash = digest(url + '\u0000' + content + '\u0000' + toolId);
        target.add(new Document(url.strip(), normalized(provider), content, hash, retrievedAtMillis,
                normalized(toolId), normalized(callId), normalized(kind)));
    }

    private static boolean supported(String toolId) {
        if (toolId == null) return false;
        for (String prefix : SUPPORTED_PREFIXES) if (toolId.startsWith(prefix)) return true;
        return false;
    }

    private static String evidenceKind(String toolId) {
        if (toolId == null) return "external";
        if (toolId.startsWith("internet.")) return "web_research";
        if (toolId.startsWith("browser.")) return "browser_observation";
        if (toolId.startsWith("content.")) return "content_source";
        if (toolId.startsWith("dataset.")) return "dataset_observation";
        return "external";
    }

    private static String providerFallback(String toolId) {
        return toolId != null && toolId.startsWith("internet.") ? "unknown_search_provider" : "koil_external_tool";
    }

    private static String primitiveValues(JsonArray values) {
        if (values == null) return "";
        StringBuilder text = new StringBuilder();
        for (JsonElement value : values) if (value != null && value.isJsonPrimitive()) {
            if (!text.isEmpty()) text.append('\n');
            text.append(value.getAsString());
            if (text.length() >= MAXIMUM_DOCUMENT_CHARACTERS) break;
        }
        return text.toString();
    }

    private static String primitive(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }

    private static String firstPrimitive(JsonObject object, String fallback, String... keys) {
        for (String key : keys) {
            String value = primitive(object, key, "");
            if (!value.isBlank()) return value;
        }
        return fallback == null ? "" : fallback;
    }

    private static String sourceId(String sessionId) {
        return "external.evidence:" + digest(sessionId);
    }

    private static long requireTtl(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("ttl must be positive");
        return ttl.toMillis();
    }

    private static String compact(String text) {
        String normalized = normalized(text);
        if (normalized.length() <= MAXIMUM_DOCUMENT_CHARACTERS) return normalized;
        int head = (int) Math.round(MAXIMUM_DOCUMENT_CHARACTERS * 0.72D);
        int tail = MAXIMUM_DOCUMENT_CHARACTERS - head - 20;
        return normalized.substring(0, head).stripTrailing() + " …[bounded]… "
                + normalized.substring(Math.max(0, normalized.length() - tail)).stripLeading();
    }

    private static String normalized(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static String digest(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Document(String url, String provider, String text, String contentHash, long retrievedAtMillis,
                            String toolId, String callId, String kind) {}
    private record Session(long expiresAtMillis, List<Document> documents) {}
}
