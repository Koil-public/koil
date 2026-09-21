package com.spirit.koil.api.telemetry;

import com.spirit.koil.api.telemetry.TelemetrySnapshots.Capability;
import com.spirit.koil.api.telemetry.TelemetrySnapshots.Event;
import com.spirit.koil.api.telemetry.TelemetrySnapshots.Request;
import com.spirit.koil.api.telemetry.TelemetrySnapshots.Span;
import com.spirit.koil.api.telemetry.TelemetryDiagnostics.Issue;
import com.spirit.koil.api.telemetry.TelemetryDiagnostics.RequestIndex;
import com.spirit.koil.api.telemetry.TelemetryDiagnostics.RequestIntegrity;
import com.spirit.koil.api.telemetry.TelemetryDiagnostics.Severity;
import com.spirit.koil.api.telemetry.TelemetryDiagnostics.StoreHealth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide causal telemetry store for Koil's model, tools, automation,
 * executor and KTL runtime. The store records only observable system state and
 * data flow. It deliberately does not expose hidden model chain-of-thought.
 */
public final class TelemetryStore {
    private static final int MAX_REQUESTS = 64;
    private static final int MAX_SPANS_PER_REQUEST = 1024;
    private static final int MAX_EVENTS_PER_SPAN = 256;
    private static final int MAX_PROVENANCE_PER_REQUEST = 512;
    private static final int MAX_PROVENANCE_PAYLOAD_CHARS = 16_384;
    private static final Map<UUID, MutableRequest> REQUESTS = new ConcurrentHashMap<>();
    private static final Map<String, UUID> SPAN_TO_REQUEST = new ConcurrentHashMap<>();
    private static final Deque<UUID> REQUEST_ORDER = new ArrayDeque<>();
    private static final AtomicLong GLOBAL_REVISION = new AtomicLong();
    private static final AtomicLong EVICTED_REQUESTS = new AtomicLong();
    private static final AtomicLong DROPPED_SPANS = new AtomicLong();
    private static final AtomicLong DROPPED_EVENTS = new AtomicLong();
    private static final AtomicLong DROPPED_PROVENANCE = new AtomicLong();
    private static final AtomicLong IMPLICIT_REQUESTS = new AtomicLong();
    private static final AtomicLong MISSING_SPAN_WRITES = new AtomicLong();
    private static final AtomicLong REPAIRED_PARENT_LINKS = new AtomicLong();
    private static final AtomicLong DUPLICATE_FINISHES = new AtomicLong();
    private static final AtomicLong SNAPSHOT_BUILDS = new AtomicLong();
    private static final AtomicLong SNAPSHOT_BUILD_NANOS = new AtomicLong();
    private static final AtomicLong INDEX_READS = new AtomicLong();
    private static final AtomicLong INDEX_READ_NANOS = new AtomicLong();
    private static final long STALE_ACTIVE_SPAN_MILLIS = 120_000L;

    private TelemetryStore() {}

    public static long revision() {
        return GLOBAL_REVISION.get();
    }

    public static String beginRequest(UUID requestId, String title, String source) {
        UUID safeId = requestId == null ? UUID.randomUUID() : requestId;
        MutableRequest request = new MutableRequest(safeId, clean(title));
        MutableRequest existing = REQUESTS.putIfAbsent(safeId, request);
        MutableRequest target = existing == null ? request : existing;
        synchronized (REQUEST_ORDER) {
            if (existing == null) REQUEST_ORDER.addLast(safeId);
            trimRequests();
        }
        synchronized (target) {
            if (target.rootSpanId.isBlank()) {
                String root = target.beginSpan("", TelemetrySpanKind.REQUEST, "request", TelemetryCapabilityState.ACTIVE,
                        Map.of("source", clean(source), "request_id", safeId.toString()));
                target.rootSpanId = root;
            }
            target.state = TelemetryCapabilityState.ACTIVE;
        }
        touch(target);
        return target.rootSpanId;
    }

    public static String rootSpan(UUID requestId) {
        MutableRequest request = requestId == null ? null : REQUESTS.get(requestId);
        return request == null ? "" : request.rootSpanId;
    }

    public static String beginSpan(UUID requestId, String parentSpanId, TelemetrySpanKind kind, String name) {
        return beginSpan(requestId, parentSpanId, kind, name, Map.of());
    }

    public static String beginSpan(UUID requestId, String parentSpanId, TelemetrySpanKind kind, String name,
                                   Map<String, String> attributes) {
        MutableRequest request = ensureRequest(requestId);
        String requestedParent = clean(parentSpanId);
        String parent = requestedParent;
        LinkedHashMap<String, String> safeAttributes = new LinkedHashMap<>();
        if (attributes != null) safeAttributes.putAll(attributes);
        synchronized (request) {
            if (parent.isBlank()) {
                parent = request.rootSpanId;
            } else if (!request.spans.containsKey(parent)) {
                UUID owner = SPAN_TO_REQUEST.get(parent);
                safeAttributes.put("telemetry_requested_parent_span_id", parent);
                safeAttributes.put("telemetry_parent_repair", owner == null ? "missing_parent" : "cross_request_parent");
                request.repairedParentLinks++;
                REPAIRED_PARENT_LINKS.incrementAndGet();
                parent = request.rootSpanId;
            }
        }
        String id = request.beginSpan(parent, kind, name, TelemetryCapabilityState.ACTIVE, safeAttributes);
        touch(request);
        return id;
    }

    public static String ensureSpan(UUID requestId, String spanId, String parentSpanId,
                                    TelemetrySpanKind kind, String name) {
        MutableRequest request = ensureRequest(requestId);
        String safe = clean(spanId);
        synchronized (request) {
            if (!safe.isBlank() && request.spans.containsKey(safe)) return safe;
        }
        return beginSpan(requestId, parentSpanId, kind, name);
    }

    public static void annotate(UUID requestId, String spanId, String key, Object value) {
        MutableSpan span = span(requestId, spanId);
        if (span == null || key == null || key.isBlank()) return;
        String safeKey = clean(key);
        String safeValue = stringify(value);
        synchronized (span) {
            span.attributes.put(safeKey, safeValue);
        }
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null) {
            synchronized (request) {
                request.indexAttribute(span, safeKey, safeValue);
            }
        }
        touch(request);
    }

    public static void metric(UUID requestId, String spanId, String key, long value) {
        if (key == null || key.isBlank()) return;
        MutableRequest request = requestId == null ? null : REQUESTS.get(requestId);
        if (request == null) return;
        synchronized (request) {
            request.metrics.put(clean(key), Math.max(0L, value));
        }
        MutableSpan span = span(requestId, spanId);
        if (span != null) {
            synchronized (span) {
                span.metrics.put(clean(key), Math.max(0L, value));
            }
        }
        touch(request);
    }

    public static void addMetric(UUID requestId, String spanId, String key, long delta) {
        if (key == null || key.isBlank()) return;
        MutableRequest request = requestId == null ? null : REQUESTS.get(requestId);
        if (request == null) return;
        synchronized (request) {
            request.metrics.merge(clean(key), delta, Long::sum);
        }
        MutableSpan span = span(requestId, spanId);
        if (span != null) {
            synchronized (span) {
                span.metrics.merge(clean(key), delta, Long::sum);
            }
        }
        touch(request);
    }

    public static void event(UUID requestId, String spanId, String type, TelemetryCapabilityState state,
                             String message, Map<String, String> fields, List<TelemetryText> typedText) {
        MutableSpan span = span(requestId, spanId);
        if (span == null) return;
        long dropped = 0L;
        synchronized (span) {
            span.events.add(new Event(
                    "event-" + UUID.randomUUID(), requestId, span.id, System.currentTimeMillis(),
                    state == null ? span.state : state, clean(type), clean(message),
                    fields == null ? Map.of() : fields,
                    typedText == null ? List.of() : typedText
            ));
            while (span.events.size() > MAX_EVENTS_PER_SPAN) {
                span.events.remove(0);
                dropped++;
            }
        }
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null && dropped > 0L) {
            synchronized (request) {
                request.droppedEvents += dropped;
            }
            DROPPED_EVENTS.addAndGet(dropped);
        }
        touch(request);
    }

    public static void event(UUID requestId, String spanId, String type, TelemetryCapabilityState state,
                             Map<String, String> fields) {
        event(requestId, spanId, type, state, "", fields, List.of());
    }

    public static void event(UUID requestId, String spanId, String type, String message) {
        event(requestId, spanId, type, TelemetryCapabilityState.ACTIVE, message, Map.of(), List.of());
    }

    public static void state(UUID requestId, String spanId, TelemetryCapabilityState state,
                             String reasonCode, String detail) {
        MutableSpan span = span(requestId, spanId);
        if (span == null) return;
        synchronized (span) {
            span.state = state == null ? TelemetryCapabilityState.IDLE : state;
            span.reasonCode = clean(reasonCode);
            if (detail != null && !detail.isBlank()) span.attributes.put("state_detail", clean(detail));
        }
        MutableRequest request = REQUESTS.get(requestId);
        if (request != null && span.id.equals(request.rootSpanId)) {
            request.state = span.state;
            request.reasonCode = span.reasonCode;
        }
        touch(request);
    }

    public static void finishSpan(UUID requestId, String spanId, TelemetryCapabilityState state,
                                  String reasonCode, String detail) {
        MutableSpan span = span(requestId, spanId);
        if (span == null) return;
        boolean duplicate;
        synchronized (span) {
            duplicate = span.endedAtMillis > 0L;
            span.state = state == null ? TelemetryCapabilityState.IDLE : state;
            span.reasonCode = clean(reasonCode);
            if (detail != null && !detail.isBlank()) span.attributes.put("result_detail", clean(detail));
            if (span.endedAtMillis <= 0L) span.endedAtMillis = System.currentTimeMillis();
            span.metrics.put("duration_ms", Math.max(0L, span.endedAtMillis - span.startedAtMillis));
        }
        MutableRequest request = REQUESTS.get(requestId);
        if (duplicate) {
            DUPLICATE_FINISHES.incrementAndGet();
            if (request != null) {
                synchronized (request) {
                    request.duplicateFinishes++;
                }
            }
        }
        touch(request);
    }

    public static void finishRequest(UUID requestId, TelemetryCapabilityState state, String reasonCode, String detail) {
        MutableRequest request = requestId == null ? null : REQUESTS.get(requestId);
        if (request == null) return;
        finishSpan(requestId, request.rootSpanId, state, reasonCode, detail);
        synchronized (request) {
            request.state = state == null ? TelemetryCapabilityState.IDLE : state;
            request.reasonCode = clean(reasonCode);
            request.completedAtMillis = System.currentTimeMillis();
        }
        touch(request);
    }

    public static void provenance(UUID requestId, String spanId, String source, String producer,
                                  String stage, String content, String summary) {
        MutableRequest request = ensureRequest(requestId);
        String safeContent = content == null ? "" : content;
        byte[] bytes = safeContent.getBytes(StandardCharsets.UTF_8);
        boolean payloadTruncated = safeContent.length() > MAX_PROVENANCE_PAYLOAD_CHARS;
        String recordedPayload = payloadTruncated
                ? safeContent.substring(0, MAX_PROVENANCE_PAYLOAD_CHARS)
                : safeContent;
        TelemetryProvenance entry = new TelemetryProvenance(
                "prov-" + UUID.randomUUID(), requestId, clean(spanId), clean(source), clean(producer), clean(stage),
                System.currentTimeMillis(), bytes.length, estimateTokens(safeContent), digest(bytes, safeContent), clean(summary),
                recordedPayload, payloadTruncated
        );
        long dropped = 0L;
        synchronized (request) {
            request.provenance.add(entry);
            while (request.provenance.size() > MAX_PROVENANCE_PER_REQUEST) {
                request.provenance.remove(0);
                dropped++;
            }
            request.droppedProvenance += dropped;
        }
        if (dropped > 0L) DROPPED_PROVENANCE.addAndGet(dropped);
        touch(request);
    }

    public static void capability(UUID requestId, String subsystem, String capability,
                                  TelemetryCapabilityState state, String reasonCode, String detail) {
        MutableRequest request = ensureRequest(requestId);
        Capability value = new Capability(clean(subsystem), clean(capability), state, clean(reasonCode), clean(detail),
                System.currentTimeMillis());
        synchronized (request) {
            request.capabilities.put(value.subsystem() + ":" + value.capability(), value);
        }
        touch(request);
    }

    public static Request snapshot(UUID requestId) {
        MutableRequest request = requestId == null ? null : REQUESTS.get(requestId);
        if (request == null) return null;
        long started = System.nanoTime();
        try {
            return request.snapshot();
        } finally {
            SNAPSHOT_BUILDS.incrementAndGet();
            SNAPSHOT_BUILD_NANOS.addAndGet(Math.max(0L, System.nanoTime() - started));
        }
    }

    /**
     * Cheap revision index used by UI projections. Consumers can compare each
     * request revision and snapshot only requests that actually changed.
     */
    public static List<RequestIndex> recentIndex() {
        long started = System.nanoTime();
        try {
            List<UUID> ids;
            synchronized (REQUEST_ORDER) {
                ids = new ArrayList<>(REQUEST_ORDER);
            }
            List<RequestIndex> out = new ArrayList<>(ids.size());
            for (int index = ids.size() - 1; index >= 0; index--) {
                MutableRequest request = REQUESTS.get(ids.get(index));
                if (request == null) continue;
                out.add(new RequestIndex(request.requestId, request.revision, request.createdAtMillis,
                        request.completedAtMillis > 0L));
            }
            return List.copyOf(out);
        } finally {
            INDEX_READS.incrementAndGet();
            INDEX_READ_NANOS.addAndGet(Math.max(0L, System.nanoTime() - started));
        }
    }

    /**
     * Returns 1 once an otherwise unchanged request has an ACTIVE span old
     * enough to qualify as stale. This lets the UI invalidate exactly once at
     * the stale boundary without polling full snapshots every frame.
     */
    public static long integrityEpoch(Request request) {
        if (request == null || request.completedAtMillis() > 0L) return 0L;
        long now = System.currentTimeMillis();
        for (Span span : request.spans()) {
            if (span.endedAtMillis() <= 0L && span.state() == TelemetryCapabilityState.ACTIVE
                    && now - span.startedAtMillis() >= STALE_ACTIVE_SPAN_MILLIS) {
                return 1L;
            }
        }
        return 0L;
    }

    public static StoreHealth health() {
        return new StoreHealth(
                GLOBAL_REVISION.get(), REQUESTS.size(), EVICTED_REQUESTS.get(), DROPPED_SPANS.get(),
                DROPPED_EVENTS.get(), DROPPED_PROVENANCE.get(), IMPLICIT_REQUESTS.get(),
                MISSING_SPAN_WRITES.get(), REPAIRED_PARENT_LINKS.get(), DUPLICATE_FINISHES.get(),
                SNAPSHOT_BUILDS.get(), SNAPSHOT_BUILD_NANOS.get(), INDEX_READS.get(), INDEX_READ_NANOS.get()
        );
    }

    public static RequestIntegrity integrity(UUID requestId) {
        Request snapshot = snapshot(requestId);
        if (snapshot == null) {
            return new RequestIntegrity(requestId, 0L, 0, 0, 0, 0, 0, 0L, 0L, 0L,
                    List.of(new Issue(Severity.ERROR, "request_missing", "Telemetry request is not retained.", "")));
        }
        return integrity(snapshot);
    }

    /** Computes integrity from an already-built request snapshot to avoid a second full copy in UI hot paths. */
    public static RequestIntegrity integrity(Request snapshot) {
        if (snapshot == null) {
            return new RequestIntegrity(null, 0L, 0, 0, 0, 0, 0, 0L, 0L, 0L,
                    List.of(new Issue(Severity.ERROR, "snapshot_missing", "Telemetry request snapshot is unavailable.", "")));
        }
        UUID requestId = snapshot.requestId();
        MutableRequest request = requestId == null ? null : REQUESTS.get(requestId);
        if (request == null) {
            return new RequestIntegrity(requestId, snapshot.revision(), 0, 0, 0, 0, 0, 0L, 0L, 0L,
                    List.of(new Issue(Severity.WARNING, "request_evicted", "Request was evicted after this snapshot was captured.", "")));
        }
        Set<String> spanIds = new HashSet<>();
        for (Span span : snapshot.spans()) spanIds.add(span.spanId());
        ArrayList<Issue> issues = new ArrayList<>();
        int openSpans = 0;
        int orphanedParents = 0;
        int staleActive = 0;
        int terminalOpen = 0;
        long now = System.currentTimeMillis();
        for (Span span : snapshot.spans()) {
            boolean root = span.spanId().equals(snapshot.rootSpanId());
            if (!root && !span.parentSpanId().isBlank() && !spanIds.contains(span.parentSpanId())) {
                orphanedParents++;
                issues.add(new Issue(Severity.ERROR, "orphan_parent",
                        "Parent span is not retained in this request: " + span.parentSpanId(), span.spanId()));
            }
            if (span.endedAtMillis() <= 0L) {
                openSpans++;
                if (snapshot.completedAtMillis() > 0L) {
                    terminalOpen++;
                    issues.add(new Issue(Severity.ERROR, "open_span_after_request_finish",
                            "Span remained open after the request completed.", span.spanId()));
                } else if (span.state() == TelemetryCapabilityState.ACTIVE
                        && now - span.startedAtMillis() >= STALE_ACTIVE_SPAN_MILLIS) {
                    staleActive++;
                    issues.add(new Issue(Severity.WARNING, "stale_active_span",
                            "Span has remained active for at least " + STALE_ACTIVE_SPAN_MILLIS + " ms.", span.spanId()));
                }
            }
        }
        int danglingProvenance = 0;
        for (TelemetryProvenance provenance : snapshot.provenance()) {
            if (!provenance.spanId().isBlank() && !spanIds.contains(provenance.spanId())) {
                danglingProvenance++;
                issues.add(new Issue(Severity.WARNING, "dangling_provenance",
                        "Provenance references a span that is not retained: " + provenance.spanId(), provenance.spanId()));
            }
        }
        long droppedSpans;
        long droppedEvents;
        long droppedProvenance;
        synchronized (request) {
            droppedSpans = request.droppedSpans;
            droppedEvents = request.droppedEvents;
            droppedProvenance = request.droppedProvenance;
            if (request.repairedParentLinks > 0L) {
                issues.add(new Issue(Severity.WARNING, "parent_links_repaired",
                        request.repairedParentLinks + " parent link(s) were repaired to the request root.", ""));
            }
            if (request.missingSpanWrites > 0L) {
                issues.add(new Issue(Severity.WARNING, "missing_span_writes",
                        request.missingSpanWrites + " telemetry write(s) targeted a missing span.", ""));
            }
            if (request.duplicateFinishes > 0L) {
                issues.add(new Issue(Severity.INFO, "duplicate_finishes",
                        request.duplicateFinishes + " span finish call(s) arrived after the span was already terminal.", ""));
            }
        }
        if (droppedSpans > 0L) issues.add(new Issue(Severity.ERROR, "span_retention_overflow",
                droppedSpans + " span(s) were dropped by the per-request retention limit.", ""));
        if (droppedEvents > 0L) issues.add(new Issue(Severity.WARNING, "event_retention_overflow",
                droppedEvents + " old event(s) were evicted by per-span retention.", ""));
        if (droppedProvenance > 0L) issues.add(new Issue(Severity.WARNING, "provenance_retention_overflow",
                droppedProvenance + " provenance item(s) were evicted by request retention.", ""));
        return new RequestIntegrity(requestId, snapshot.revision(), openSpans, orphanedParents, danglingProvenance,
                staleActive, terminalOpen, droppedSpans, droppedEvents, droppedProvenance, List.copyOf(issues));
    }

    public static List<Request> recent() {
        List<UUID> ids;
        synchronized (REQUEST_ORDER) {
            ids = new ArrayList<>(REQUEST_ORDER);
        }
        List<Request> out = new ArrayList<>();
        for (UUID id : ids) {
            Request snapshot = snapshot(id);
            if (snapshot != null) out.add(snapshot);
        }
        out.sort(Comparator.comparingLong(Request::createdAtMillis).reversed());
        return List.copyOf(out);
    }

    public static UUID requestForSpan(String spanId) {
        return SPAN_TO_REQUEST.get(clean(spanId));
    }

    public static String latestSpan(UUID requestId, TelemetrySpanKind kind, String attributeKey, String attributeValue) {
        MutableRequest request = requestId == null ? null : REQUESTS.get(requestId);
        if (request == null) return "";
        String key = clean(attributeKey);
        String value = clean(attributeValue);
        synchronized (request) {
            if (kind != null && key.isBlank()) {
                return request.latestByKind.getOrDefault(kind, "");
            }
            if (kind != null && !key.isBlank()) {
                String indexed = request.latestByAttribute.get(attributeIndexKey(kind, key, value));
                if (indexed != null) return indexed;
            }
            // Compatibility fallback for legacy spans created before an attribute
            // was indexed. Successful fallback results are promoted into the index.
            MutableSpan match = null;
            for (MutableSpan span : request.spans.values()) {
                if (kind != null && span.kind != kind) continue;
                if (!key.isBlank() && !value.equals(span.attributes.getOrDefault(key, ""))) continue;
                if (match == null || span.startedAtMillis >= match.startedAtMillis) match = span;
            }
            if (match == null) return "";
            request.latestByKind.put(match.kind, match.id);
            if (!key.isBlank()) request.latestByAttribute.put(attributeIndexKey(match.kind, key, value), match.id);
            return match.id;
        }
    }

    private static String attributeIndexKey(TelemetrySpanKind kind, String key, String value) {
        TelemetrySpanKind safeKind = kind == null ? TelemetrySpanKind.OTHER : kind;
        return safeKind.name() + '\u0000' + clean(key) + '\u0000' + clean(value);
    }

    public static int estimateTokens(String value) {
        if (value == null || value.isBlank()) return 0;
        return Math.max(1, (int) Math.ceil(value.length() / 4.0D));
    }

    private static MutableRequest ensureRequest(UUID requestId) {
        UUID safe = requestId == null ? UUID.randomUUID() : requestId;
        MutableRequest request = REQUESTS.get(safe);
        if (request != null) return request;
        IMPLICIT_REQUESTS.incrementAndGet();
        beginRequest(safe, "request " + safe, "implicit");
        return REQUESTS.get(safe);
    }

    private static MutableSpan span(UUID requestId, String spanId) {
        MutableRequest request = requestId == null ? null : REQUESTS.get(requestId);
        if (request == null) return null;
        String safe = clean(spanId);
        if (safe.isBlank()) safe = request.rootSpanId;
        synchronized (request) {
            MutableSpan span = request.spans.get(safe);
            if (span == null) {
                request.missingSpanWrites++;
                MISSING_SPAN_WRITES.incrementAndGet();
            }
            return span;
        }
    }

    private static void touch(MutableRequest request) {
        if (request == null) return;
        request.revision = GLOBAL_REVISION.incrementAndGet();
    }

    private static void trimRequests() {
        while (REQUEST_ORDER.size() > MAX_REQUESTS) {
            UUID oldest = REQUEST_ORDER.removeFirst();
            MutableRequest removed = REQUESTS.remove(oldest);
            if (removed != null) {
                EVICTED_REQUESTS.incrementAndGet();
                synchronized (removed) {
                    for (String spanId : removed.spans.keySet()) SPAN_TO_REQUEST.remove(spanId, oldest);
                }
            }
        }
    }

    private static String digest(byte[] bytes, String fallbackValue) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes == null ? new byte[0] : bytes)).substring(0, 16);
        } catch (Exception ignored) {
            return Integer.toHexString((fallbackValue == null ? "" : fallbackValue).hashCode());
        }
    }

    private static String stringify(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value).replace('\r', ' ').replace('\n', ' ').strip();
        return text.length() <= 2048 ? text : text.substring(0, 2047) + "…";
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').strip();
    }

    private static final class MutableRequest {
        private final UUID requestId;
        private final String title;
        private final long createdAtMillis = System.currentTimeMillis();
        private final LinkedHashMap<String, MutableSpan> spans = new LinkedHashMap<>();
        private final LinkedHashMap<TelemetrySpanKind, String> latestByKind = new LinkedHashMap<>();
        private final LinkedHashMap<String, String> latestByAttribute = new LinkedHashMap<>();
        private final ArrayList<TelemetryProvenance> provenance = new ArrayList<>();
        private final LinkedHashMap<String, Capability> capabilities = new LinkedHashMap<>();
        private final LinkedHashMap<String, Long> metrics = new LinkedHashMap<>();
        private volatile String rootSpanId = "";
        private volatile long completedAtMillis;
        private volatile long revision;
        private volatile TelemetryCapabilityState state = TelemetryCapabilityState.IDLE;
        private volatile String reasonCode = "";
        private long droppedSpans;
        private long droppedEvents;
        private long droppedProvenance;
        private long repairedParentLinks;
        private long missingSpanWrites;
        private long duplicateFinishes;

        private MutableRequest(UUID requestId, String title) {
            this.requestId = requestId;
            this.title = title;
        }

        private synchronized String beginSpan(String parentSpanId, TelemetrySpanKind kind, String name,
                                              TelemetryCapabilityState state, Map<String, String> attributes) {
            if (spans.size() >= MAX_SPANS_PER_REQUEST) {
                droppedSpans++;
                DROPPED_SPANS.incrementAndGet();
                // Return a non-retained sentinel. Follow-up writes safely no-op and
                // are counted as missing-span writes instead of corrupting another span.
                return "dropped-span-" + UUID.randomUUID();
            }
            String id = "span-" + UUID.randomUUID();
            MutableSpan span = new MutableSpan(id, parentSpanId, requestId, kind, clean(name), state,
                    attributes == null ? Map.of() : attributes);
            spans.put(id, span);
            SPAN_TO_REQUEST.put(id, requestId);
            latestByKind.put(span.kind, id);
            for (Map.Entry<String, String> entry : span.attributes.entrySet()) {
                indexAttribute(span, entry.getKey(), entry.getValue());
            }
            return id;
        }

        private synchronized void indexAttribute(MutableSpan span, String key, String value) {
            if (span == null || key == null || key.isBlank()) return;
            latestByAttribute.put(attributeIndexKey(span.kind, key, value), span.id);
        }

        private synchronized Request snapshot() {
            List<Span> spanSnapshots = spans.values().stream()
                    .map(MutableSpan::snapshot)
                    .sorted(Comparator.comparingLong(Span::startedAtMillis))
                    .toList();
            LinkedHashMap<String, Long> snapshotMetrics = new LinkedHashMap<>(metrics);
            long eventCount = spanSnapshots.stream().mapToLong(span -> span.events().size()).sum();
            long provenanceBytes = provenance.stream().mapToLong(TelemetryProvenance::sizeBytes).sum();
            long provenanceTokens = provenance.stream().mapToLong(TelemetryProvenance::estimatedTokens).sum();
            snapshotMetrics.put("telemetry_span_count", (long) spanSnapshots.size());
            snapshotMetrics.put("telemetry_event_count", eventCount);
            snapshotMetrics.put("telemetry_provenance_count", (long) provenance.size());
            snapshotMetrics.put("telemetry_capability_count", (long) capabilities.size());
            snapshotMetrics.put("telemetry_provenance_bytes", provenanceBytes);
            snapshotMetrics.put("telemetry_provenance_tokens_estimated", provenanceTokens);
            snapshotMetrics.put("telemetry_retained_requests", (long) REQUESTS.size());
            snapshotMetrics.put("telemetry_request_retention_limit", (long) MAX_REQUESTS);
            snapshotMetrics.put("telemetry_span_retention_limit", (long) MAX_SPANS_PER_REQUEST);
            snapshotMetrics.put("telemetry_event_retention_per_span", (long) MAX_EVENTS_PER_SPAN);
            snapshotMetrics.put("telemetry_dropped_spans", droppedSpans);
            snapshotMetrics.put("telemetry_dropped_events", droppedEvents);
            snapshotMetrics.put("telemetry_dropped_provenance", droppedProvenance);
            snapshotMetrics.put("telemetry_repaired_parent_links", repairedParentLinks);
            snapshotMetrics.put("telemetry_missing_span_writes", missingSpanWrites);
            snapshotMetrics.put("telemetry_duplicate_finishes", duplicateFinishes);
            return new Request(requestId, rootSpanId, title, createdAtMillis, completedAtMillis, revision,
                    state, reasonCode, spanSnapshots, List.copyOf(provenance),
                    List.copyOf(capabilities.values()), Map.copyOf(snapshotMetrics));
        }
    }

    private static final class MutableSpan {
        private final String id;
        private final String parentSpanId;
        private final UUID requestId;
        private final TelemetrySpanKind kind;
        private final String name;
        private final long startedAtMillis = System.currentTimeMillis();
        private long endedAtMillis;
        private TelemetryCapabilityState state;
        private String reasonCode = "";
        private final LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        private final LinkedHashMap<String, Long> metrics = new LinkedHashMap<>();
        private final ArrayList<Event> events = new ArrayList<>();

        private MutableSpan(String id, String parentSpanId, UUID requestId, TelemetrySpanKind kind, String name,
                            TelemetryCapabilityState state, Map<String, String> attributes) {
            this.id = id;
            this.parentSpanId = clean(parentSpanId);
            this.requestId = requestId;
            this.kind = kind == null ? TelemetrySpanKind.OTHER : kind;
            this.name = name;
            this.state = state == null ? TelemetryCapabilityState.IDLE : state;
            if (attributes != null) this.attributes.putAll(attributes);
        }

        private synchronized Span snapshot() {
            return new Span(id, parentSpanId, requestId, kind, name, state, reasonCode,
                    startedAtMillis, endedAtMillis, Map.copyOf(attributes), Map.copyOf(metrics), List.copyOf(events));
        }
    }
}
