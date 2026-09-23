package com.spirit.koil.api.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded local canonical store used until a configured persistent Koil store is introduced. */
public final class InMemoryContextStore implements ContextStore {
    private final Clock clock;
    private final int maximumEntries;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, ContextArtifact> artifacts = new LinkedHashMap<>(16, 0.75F, true);
    private final Map<String, Integer> pins = new LinkedHashMap<>();

    public InMemoryContextStore(Clock clock, int maximumEntries) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.maximumEntries = Math.max(1, maximumEntries);
    }

    @Override
    public synchronized ContextArtifact register(ContextArtifactRequest request) {
        if (request == null) throw new IllegalArgumentException("request");
        expire();
        Instant created = this.clock.instant();
        ContextArtifact artifact = new ContextArtifact(
                "ctx-" + String.format(java.util.Locale.ROOT, "%06d", this.sequence.incrementAndGet()), request.scopeId(),
                digest(request.canonicalContent()), request.sourceType(), request.sourceId(), request.canonicalContent(),
                ContextRepresentationLevel.L0_EXACT, created, created.plusSeconds(request.ttlSeconds())
        );
        this.artifacts.put(artifact.id(), artifact);
        trim();
        return artifact;
    }

    @Override
    public synchronized Optional<ContextArtifact> retrieve(String scopeId, String artifactId) {
        expire();
        ContextArtifact artifact = this.artifacts.get(artifactId);
        return artifact != null && artifact.scopeId().equals(normalizeScope(scopeId)) ? Optional.of(artifact) : Optional.empty();
    }

    @Override
    public synchronized boolean pin(String scopeId, String artifactId) {
        expire();
        ContextArtifact artifact = this.artifacts.get(artifactId);
        if (artifact == null || !artifact.scopeId().equals(normalizeScope(scopeId))) return false;
        this.pins.merge(artifactId, 1, Integer::sum);
        return true;
    }

    @Override
    public synchronized boolean release(String scopeId, String artifactId) {
        ContextArtifact artifact = this.artifacts.get(artifactId);
        if (artifact == null || !artifact.scopeId().equals(normalizeScope(scopeId))) return false;
        Integer count = this.pins.get(artifactId);
        if (count == null) return false;
        if (count <= 1) this.pins.remove(artifactId);
        else this.pins.put(artifactId, count - 1);
        trim();
        return true;
    }

    @Override
    public synchronized List<ContextArtifact> search(String scopeId, String query, int limit) {
        expire();
        String needle = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        int maximum = Math.max(1, Math.min(8, limit));
        // ponytail: bounded lexical fallback; P7 may add TurboVec segment reranking without changing scope authority.
        return this.artifacts.values().stream()
                .filter(artifact -> artifact.scopeId().equals(normalizeScope(scopeId)))
                .filter(artifact -> needle.isBlank() || searchable(artifact).contains(needle))
                .limit(maximum)
                .toList();
    }

    @Override
    public synchronized ContextStoreStats stats(String scopeId) {
        expire();
        String scope = normalizeScope(scopeId);
        List<ContextArtifact> matching = this.artifacts.values().stream().filter(artifact -> artifact.scopeId().equals(scope)).toList();
        return new ContextStoreStats(matching.size(), matching.stream().filter(artifact -> this.pins.containsKey(artifact.id())).count(),
                matching.stream().mapToLong(artifact -> artifact.canonicalContent().length()).sum());
    }

    private void expire() {
        Instant now = this.clock.instant();
        this.artifacts.entrySet().removeIf(entry -> {
            boolean expired = !entry.getValue().expiresAt().isAfter(now);
            if (expired) this.pins.remove(entry.getKey());
            return expired;
        });
    }

    private void trim() {
        while (this.artifacts.size() > this.maximumEntries) {
            boolean removed = false;
            Iterator<String> ids = this.artifacts.keySet().iterator();
            while (ids.hasNext()) {
                String id = ids.next();
                if (this.pins.containsKey(id)) continue;
                ids.remove();
                removed = true;
                break;
            }
            if (!removed) return;
        }
    }

    private static String digest(String content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String normalizeScope(String scopeId) {
        return scopeId == null || scopeId.isBlank() ? "local" : scopeId.strip();
    }

    private static String searchable(ContextArtifact artifact) {
        return (artifact.sourceType() + "\n" + artifact.sourceId() + "\n" + artifact.canonicalContent()).toLowerCase(java.util.Locale.ROOT);
    }
}
