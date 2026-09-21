package com.spirit.client.gui.automation;

import com.spirit.koil.api.telemetry.TelemetryDiagnostics;
import com.spirit.koil.api.telemetry.TelemetrySnapshots;
import com.spirit.koil.api.telemetry.TelemetryStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Incremental read model for the Automation Workspace. Rendering consumes this
 * immutable projection instead of repeatedly walking runtime systems.
 */
final class AutomationWorkspaceTelemetryViewModel {
    private long observedRevision = Long.MIN_VALUE;
    private List<TelemetrySnapshots.Request> requests = List.of();
    private TelemetryDiagnostics.StoreHealth health = TelemetryStore.health();
    private final Map<UUID, TelemetrySnapshots.Request> byRequest = new LinkedHashMap<>();
    private final Map<UUID, TelemetryDiagnostics.RequestIntegrity> integrityByRequest = new LinkedHashMap<>();
    private final Map<UUID, Long> integrityEpochByRequest = new LinkedHashMap<>();

    long revision() {
        refreshIfNeeded();
        return this.observedRevision;
    }

    List<TelemetrySnapshots.Request> requests() {
        refreshIfNeeded();
        return this.requests;
    }

    TelemetrySnapshots.Request request(UUID requestId) {
        if (requestId == null) return null;
        refreshIfNeeded();
        return this.byRequest.get(requestId);
    }

    long projectionRevision(UUID requestId) {
        if (requestId == null) return revision();
        TelemetrySnapshots.Request request = request(requestId);
        if (request == null) return Long.MIN_VALUE;
        long epoch = TelemetryStore.integrityEpoch(request);
        return request.revision() ^ (epoch == 0L ? 0L : Long.MIN_VALUE);
    }

    TelemetryDiagnostics.StoreHealth health() {
        refreshIfNeeded();
        return this.health;
    }

    TelemetryDiagnostics.RequestIntegrity integrity(UUID requestId) {
        if (requestId == null) return null;
        refreshIfNeeded();
        TelemetrySnapshots.Request request = this.byRequest.get(requestId);
        if (request == null) return TelemetryStore.integrity(requestId);
        long epoch = TelemetryStore.integrityEpoch(request);
        TelemetryDiagnostics.RequestIntegrity cached = this.integrityByRequest.get(requestId);
        long cachedEpoch = this.integrityEpochByRequest.getOrDefault(requestId, Long.MIN_VALUE);
        if (cached != null && cached.requestRevision() == request.revision() && cachedEpoch == epoch) return cached;
        TelemetryDiagnostics.RequestIntegrity next = TelemetryStore.integrity(request);
        this.integrityByRequest.put(requestId, next);
        this.integrityEpochByRequest.put(requestId, epoch);
        return next;
    }

    List<TelemetrySnapshots.Span> timeline(UUID requestId) {
        TelemetrySnapshots.Request request = request(requestId);
        if (request == null) return List.of();
        return request.spans().stream()
                .sorted(Comparator.comparingLong(TelemetrySnapshots.Span::startedAtMillis)
                        .thenComparing(TelemetrySnapshots.Span::spanId))
                .toList();
    }

    Map<String, List<TelemetrySnapshots.Span>> topology(UUID requestId) {
        TelemetrySnapshots.Request request = request(requestId);
        if (request == null) return Map.of();
        LinkedHashMap<String, List<TelemetrySnapshots.Span>> children = new LinkedHashMap<>();
        for (TelemetrySnapshots.Span span : request.spans()) {
            children.computeIfAbsent(span.parentSpanId(), ignored -> new ArrayList<>()).add(span);
        }
        children.values().forEach(list -> list.sort(Comparator.comparingLong(TelemetrySnapshots.Span::startedAtMillis)));
        return Map.copyOf(children);
    }

    Diff diff(UUID firstId, UUID secondId) {
        TelemetrySnapshots.Request first = request(firstId);
        TelemetrySnapshots.Request second = request(secondId);
        if (first == null || second == null) return Diff.empty(firstId, secondId);

        LinkedHashMap<String, MetricDelta> metrics = new LinkedHashMap<>();
        Set<String> metricKeys = new LinkedHashSet<>();
        metricKeys.addAll(first.metrics().keySet());
        metricKeys.addAll(second.metrics().keySet());
        for (String key : metricKeys) {
            long before = first.metrics().getOrDefault(key, 0L);
            long after = second.metrics().getOrDefault(key, 0L);
            metrics.put(key, new MetricDelta(before, after, after - before));
        }

        LinkedHashMap<String, SpanDelta> spans = new LinkedHashMap<>();
        Map<String, TelemetrySnapshots.Span> firstByKey = indexSpans(first.spans());
        Map<String, TelemetrySnapshots.Span> secondByKey = indexSpans(second.spans());
        Set<String> spanKeys = new LinkedHashSet<>();
        spanKeys.addAll(firstByKey.keySet());
        spanKeys.addAll(secondByKey.keySet());
        long now = System.currentTimeMillis();
        for (String key : spanKeys) {
            TelemetrySnapshots.Span before = firstByKey.get(key);
            TelemetrySnapshots.Span after = secondByKey.get(key);
            spans.put(key, new SpanDelta(
                    before == null ? -1L : before.durationMillis(now),
                    after == null ? -1L : after.durationMillis(now),
                    before == null ? "missing" : before.state().name().toLowerCase(java.util.Locale.ROOT),
                    after == null ? "missing" : after.state().name().toLowerCase(java.util.Locale.ROOT)
            ));
        }

        LinkedHashMap<String, TextDelta> fields = new LinkedHashMap<>();
        compareTextField(fields, "request.state",
                first.state().name().toLowerCase(java.util.Locale.ROOT),
                second.state().name().toLowerCase(java.util.Locale.ROOT));
        compareTextField(fields, "request.reason", first.reasonCode(), second.reasonCode());
        for (String key : spanKeys) {
            TelemetrySnapshots.Span before = firstByKey.get(key);
            TelemetrySnapshots.Span after = secondByKey.get(key);
            Set<String> attributeKeys = new LinkedHashSet<>();
            if (before != null) attributeKeys.addAll(before.attributes().keySet());
            if (after != null) attributeKeys.addAll(after.attributes().keySet());
            for (String attribute : attributeKeys) {
                compareTextField(fields, key + "." + attribute,
                        before == null ? "<missing>" : before.attributes().getOrDefault(attribute, ""),
                        after == null ? "<missing>" : after.attributes().getOrDefault(attribute, ""));
            }
        }
        Map<String, TelemetrySnapshots.Capability> firstCapabilities = indexCapabilities(first.capabilities());
        Map<String, TelemetrySnapshots.Capability> secondCapabilities = indexCapabilities(second.capabilities());
        Set<String> capabilityKeys = new LinkedHashSet<>();
        capabilityKeys.addAll(firstCapabilities.keySet());
        capabilityKeys.addAll(secondCapabilities.keySet());
        for (String key : capabilityKeys) {
            TelemetrySnapshots.Capability before = firstCapabilities.get(key);
            TelemetrySnapshots.Capability after = secondCapabilities.get(key);
            compareTextField(fields, "capability." + key + ".state",
                    before == null ? "<missing>" : before.state().name().toLowerCase(java.util.Locale.ROOT),
                    after == null ? "<missing>" : after.state().name().toLowerCase(java.util.Locale.ROOT));
            compareTextField(fields, "capability." + key + ".reason",
                    before == null ? "<missing>" : before.reasonCode(),
                    after == null ? "<missing>" : after.reasonCode());
        }

        Set<String> firstProvenance = provenanceKeys(first.provenance());
        Set<String> secondProvenance = provenanceKeys(second.provenance());
        LinkedHashSet<String> added = new LinkedHashSet<>(secondProvenance);
        added.removeAll(firstProvenance);
        LinkedHashSet<String> removed = new LinkedHashSet<>(firstProvenance);
        removed.removeAll(secondProvenance);

        return new Diff(firstId, secondId, Map.copyOf(metrics), Map.copyOf(spans), Map.copyOf(fields),
                Set.copyOf(added), Set.copyOf(removed));
    }

    private void refreshIfNeeded() {
        long revision = TelemetryStore.revision();
        if (revision == this.observedRevision) return;

        List<TelemetryDiagnostics.RequestIndex> index = TelemetryStore.recentIndex();
        LinkedHashMap<UUID, TelemetrySnapshots.Request> nextByRequest = new LinkedHashMap<>();
        ArrayList<TelemetrySnapshots.Request> nextRequests = new ArrayList<>(index.size());
        Set<UUID> retained = new LinkedHashSet<>();

        for (TelemetryDiagnostics.RequestIndex entry : index) {
            UUID requestId = entry.requestId();
            retained.add(requestId);
            TelemetrySnapshots.Request cached = this.byRequest.get(requestId);
            TelemetrySnapshots.Request snapshot = cached != null && cached.revision() == entry.revision()
                    ? cached
                    : TelemetryStore.snapshot(requestId);
            if (snapshot == null) continue;
            nextByRequest.put(requestId, snapshot);
            nextRequests.add(snapshot);
            if (cached == null || cached.revision() != snapshot.revision()) {
                this.integrityByRequest.remove(requestId);
                this.integrityEpochByRequest.remove(requestId);
            }
        }

        this.integrityByRequest.keySet().removeIf(id -> !retained.contains(id));
        this.integrityEpochByRequest.keySet().removeIf(id -> !retained.contains(id));
        this.byRequest.clear();
        this.byRequest.putAll(nextByRequest);
        this.requests = List.copyOf(nextRequests);
        this.health = TelemetryStore.health();
        this.observedRevision = revision;
    }

    private static Map<String, TelemetrySnapshots.Span> indexSpans(List<TelemetrySnapshots.Span> spans) {
        LinkedHashMap<String, TelemetrySnapshots.Span> out = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> occurrences = new LinkedHashMap<>();
        for (TelemetrySnapshots.Span span : spans) {
            String base = span.kind().name().toLowerCase(java.util.Locale.ROOT) + ":" + span.name();
            int occurrence = occurrences.merge(base, 1, Integer::sum);
            out.put(base + "#" + occurrence, span);
        }
        return out;
    }

    private static Map<String, TelemetrySnapshots.Capability> indexCapabilities(
            List<TelemetrySnapshots.Capability> capabilities
    ) {
        LinkedHashMap<String, TelemetrySnapshots.Capability> out = new LinkedHashMap<>();
        for (TelemetrySnapshots.Capability capability : capabilities) {
            out.put(capability.subsystem() + ":" + capability.capability(), capability);
        }
        return out;
    }

    private static void compareTextField(Map<String, TextDelta> out, String key, String before, String after) {
        String left = before == null ? "" : before;
        String right = after == null ? "" : after;
        if (!left.equals(right)) out.put(key, new TextDelta(left, right));
    }

    private static Set<String> provenanceKeys(List<com.spirit.koil.api.telemetry.TelemetryProvenance> provenance) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (var item : provenance) {
            out.add(item.source() + ":" + item.stage() + ":" + item.digest());
        }
        return out;
    }

    record MetricDelta(long before, long after, long delta) {}
    record SpanDelta(long beforeMillis, long afterMillis, String beforeState, String afterState) {}
    record TextDelta(String before, String after) {}
    record Diff(UUID firstRequestId, UUID secondRequestId, Map<String, MetricDelta> metrics,
                Map<String, SpanDelta> spans, Map<String, TextDelta> fields,
                Set<String> provenanceAdded, Set<String> provenanceRemoved) {
        static Diff empty(UUID first, UUID second) {
            return new Diff(first, second, Map.of(), Map.of(), Map.of(), Set.of(), Set.of());
        }
        boolean available() {
            return firstRequestId != null && secondRequestId != null;
        }
    }
}
