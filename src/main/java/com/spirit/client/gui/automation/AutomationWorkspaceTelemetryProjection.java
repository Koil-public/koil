package com.spirit.client.gui.automation;

import com.spirit.koil.api.telemetry.TelemetryCapabilityState;
import com.spirit.koil.api.telemetry.TelemetryDiagnostics;
import com.spirit.koil.api.telemetry.TelemetryProvenance;
import com.spirit.koil.api.telemetry.TelemetrySnapshots;
import com.spirit.koil.api.telemetry.TelemetryText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure projection layer for canonical workspace telemetry. It owns no Minecraft
 * rendering state, so topology/timeline/diff construction only reruns when the
 * telemetry view model revision changes.
 */
final class AutomationWorkspaceTelemetryProjection {
    enum View { TIMELINE, TOPOLOGY }
    enum Detail { SUMMARY, DETAILS, RAW }

    enum FlowKind {
        NONE, REQUEST, CONTEXT, PLAN, THINKING, SKILL, TOOL, EXECUTION, APPROVAL, SERIALIZATION, OUTPUT, QUEUE, VALIDATION, KNOWLEDGE
    }

    record Flow(FlowKind kind, long timestampMillis, long amount, String label) {
        Flow {
            kind = kind == null ? FlowKind.NONE : kind;
            timestampMillis = Math.max(0L, timestampMillis);
            amount = Math.max(0L, amount);
            label = safe(label);
        }

        static Flow none() {
            return new Flow(FlowKind.NONE, 0L, 0L, "");
        }
    }

    static final class Node {
        final String id;
        final String title;
        final String kind;
        final String summary;
        final TelemetryCapabilityState state;
        List<List<TelemetryText>> text;
        final List<Node> children = new ArrayList<>();
        final Set<String> links = new LinkedHashSet<>();
        Flow inboundFlow = Flow.none();
        long startedAtMillis;
        long durationMillis;
        boolean active;
        int concurrentPeers;

        Node(String id, String title, String kind, String summary, TelemetryCapabilityState state) {
            this.id = safe(id);
            this.title = safe(title);
            this.kind = safe(kind);
            this.summary = safe(summary);
            this.state = state == null ? TelemetryCapabilityState.IDLE : state;
            this.text = List.of();
        }

        Node text(List<List<TelemetryText>> lines) {
            this.text = lines == null ? List.of() : List.copyOf(lines);
            return this;
        }

        Node add(Node child) {
            if (child != null) this.children.add(child);
            return this;
        }

        Node flow(Flow value) {
            this.inboundFlow = value == null ? Flow.none() : value;
            return this;
        }

        Node timing(long startedAtMillis, long durationMillis, boolean active) {
            this.startedAtMillis = Math.max(0L, startedAtMillis);
            this.durationMillis = Math.max(0L, durationMillis);
            this.active = active;
            return this;
        }
    }

    private AutomationWorkspaceTelemetryProjection() {}

    static List<Node> build(
            AutomationWorkspaceTelemetryViewModel viewModel,
            View view,
            UUID requestId,
            Detail detail
    ) {
        if (requestId == null) {
            List<TelemetrySnapshots.Request> requests = viewModel.requests();
            Node all = new Node(
                    "telemetry:" + view.name().toLowerCase(Locale.ROOT) + ":all",
                    view == View.TIMELINE ? "Chronological request timeline" : "Causal request topology",
                    view.name(),
                    requests.size() + " retained request(s)",
                    TelemetryCapabilityState.AVAILABLE
            ).text(List.of(
                    line(TelemetryText.Kind.KEY, "relationship source", TelemetryText.Kind.VALUE,
                            view == View.TIMELINE ? "recorded timestamps" : "recorded parentSpanId"),
                    line(TelemetryText.Kind.KEY, "inference", TelemetryText.Kind.SUCCESS, "none")
            ));
            all.add(storeHealthNode(viewModel.health()));
            for (TelemetrySnapshots.Request request : requests) {
                all.add(view == View.TIMELINE
                        ? timelineRequest(viewModel, request, detail)
                        : topologyRequest(viewModel, request, detail));
            }
            return List.of(all);
        }
        TelemetrySnapshots.Request request = viewModel.request(requestId);
        if (request == null) return List.of(unavailable(requestId));
        return List.of(view == View.TIMELINE
                ? timelineRequest(viewModel, request, detail)
                : topologyRequest(viewModel, request, detail));
    }

    static Node diff(
            AutomationWorkspaceTelemetryViewModel viewModel,
            UUID firstId,
            UUID secondId
    ) {
        AutomationWorkspaceTelemetryViewModel.Diff diff = viewModel.diff(firstId, secondId);
        if (!diff.available()) return null;
        List<List<TelemetryText>> typed = new ArrayList<>();
        typed.add(line(TelemetryText.Kind.KEY, "A", TelemetryText.Kind.ID, String.valueOf(diff.firstRequestId())));
        typed.add(line(TelemetryText.Kind.KEY, "B", TelemetryText.Kind.ID, String.valueOf(diff.secondRequestId())));
        diff.metrics().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var delta = entry.getValue();
            typed.add(List.of(
                    new TelemetryText(TelemetryText.Kind.KEY, entry.getKey()),
                    new TelemetryText(TelemetryText.Kind.PLAIN, "  "),
                    new TelemetryText(TelemetryText.Kind.COUNT, Long.toString(delta.before())),
                    new TelemetryText(TelemetryText.Kind.PLAIN, " -> "),
                    new TelemetryText(TelemetryText.Kind.COUNT, Long.toString(delta.after())),
                    new TelemetryText(delta.delta() > 0 ? TelemetryText.Kind.WARNING : delta.delta() < 0
                            ? TelemetryText.Kind.SUCCESS : TelemetryText.Kind.MUTED,
                            "  (" + (delta.delta() >= 0 ? "+" : "") + delta.delta() + ")")
            ));
        });
        diff.spans().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var delta = entry.getValue();
            typed.add(List.of(
                    new TelemetryText(TelemetryText.Kind.KEY, entry.getKey()),
                    new TelemetryText(TelemetryText.Kind.PLAIN, "  "),
                    new TelemetryText(TelemetryText.Kind.DURATION, duration(delta.beforeMillis())),
                    new TelemetryText(TelemetryText.Kind.PLAIN, " -> "),
                    new TelemetryText(TelemetryText.Kind.DURATION, duration(delta.afterMillis())),
                    new TelemetryText(TelemetryText.Kind.MUTED, "  " + delta.beforeState() + " -> " + delta.afterState())
            ));
        });
        diff.fields().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var delta = entry.getValue();
            typed.add(List.of(
                    new TelemetryText(TelemetryText.Kind.KEY, entry.getKey()),
                    new TelemetryText(TelemetryText.Kind.PLAIN, "  "),
                    new TelemetryText(TelemetryText.Kind.VALUE, delta.before()),
                    new TelemetryText(TelemetryText.Kind.PLAIN, " -> "),
                    new TelemetryText(TelemetryText.Kind.VALUE, delta.after())
            ));
        });
        for (String added : diff.provenanceAdded()) {
            typed.add(line(TelemetryText.Kind.SUCCESS, "+ provenance", TelemetryText.Kind.SOURCE, added));
        }
        for (String removed : diff.provenanceRemoved()) {
            typed.add(line(TelemetryText.Kind.WARNING, "- provenance", TelemetryText.Kind.SOURCE, removed));
        }
        return new Node("telemetry:diff", "Request comparison", "DIFF",
                shortId(firstId) + " -> " + shortId(secondId), TelemetryCapabilityState.AVAILABLE).text(typed);
    }

    private static Node timelineRequest(
            AutomationWorkspaceTelemetryViewModel viewModel,
            TelemetrySnapshots.Request request,
            Detail detail
    ) {
        Node root = requestHeader(request, "TIMELINE", detail);
        long origin = request.createdAtMillis();
        Node previous = null;
        for (TelemetrySnapshots.Span span : viewModel.timeline(request.requestId())) {
            Node node = spanNode(span,
                    "+" + Math.max(0L, span.startedAtMillis() - origin) + "ms  " + span.name(), detail);
            root.add(node);
            if (previous != null) previous.links.add(node.id);
            previous = node;
        }
        appendRequestMetadata(root, request, detail, viewModel.integrity(request.requestId()));
        return root;
    }

    private static Node topologyRequest(
            AutomationWorkspaceTelemetryViewModel viewModel,
            TelemetrySnapshots.Request request,
            Detail detail
    ) {
        Node root = requestHeader(request, "TOPOLOGY", detail);
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (TelemetrySnapshots.Span span : request.spans()) {
            nodes.put(span.spanId(), spanNode(span, span.name(), detail));
        }
        long overlapNow = System.currentTimeMillis();
        for (TelemetrySnapshots.Span span : request.spans()) {
            Node node = nodes.get(span.spanId());
            if (node == null) continue;
            long start = span.startedAtMillis();
            long end = span.endedAtMillis() > 0L ? span.endedAtMillis() : overlapNow;
            int peers = 0;
            for (TelemetrySnapshots.Span other : request.spans()) {
                if (other == span || other.spanId().equals(span.spanId())) continue;
                long otherStart = other.startedAtMillis();
                long otherEnd = other.endedAtMillis() > 0L ? other.endedAtMillis() : overlapNow;
                if (start < otherEnd && otherStart < end) peers++;
            }
            node.concurrentPeers = peers;
        }
        for (TelemetrySnapshots.Span span : request.spans()) {
            Node node = nodes.get(span.spanId());
            if (node == null) continue;
            Node parent = nodes.get(span.parentSpanId());
            if (parent != null) {
                parent.add(node);
                parent.links.add(node.id);
            } else if (!span.spanId().equals(request.rootSpanId())) {
                root.add(node);
            }
        }
        Node recordedRoot = nodes.get(request.rootSpanId());
        if (recordedRoot != null) root.add(recordedRoot);
        sortChildrenByRecordedStart(root);
        appendRequestMetadata(root, request, detail, viewModel.integrity(request.requestId()));
        return root;
    }

    private static void sortChildrenByRecordedStart(Node node) {
        if (node == null || node.children.isEmpty()) return;
        node.children.sort(java.util.Comparator
                .comparingLong((Node child) -> child.startedAtMillis <= 0L ? Long.MAX_VALUE : child.startedAtMillis)
                .thenComparing(child -> child.id));
        for (Node child : node.children) sortChildrenByRecordedStart(child);
    }

    private static Node requestHeader(TelemetrySnapshots.Request request, String kind, Detail detail) {
        Node root = new Node(
                "telemetry:request:" + request.requestId() + ":" + kind.toLowerCase(Locale.ROOT),
                request.title().isBlank() ? "Request " + shortId(request.requestId()) : request.title(),
                kind,
                request.state().name().toLowerCase(Locale.ROOT) + " | " + request.spans().size() + " span(s)",
                request.state()
        );
        root.timing(request.createdAtMillis(),
                Math.max(0L, (request.completedAtMillis() > 0L ? request.completedAtMillis() : System.currentTimeMillis()) - request.createdAtMillis()),
                request.completedAtMillis() <= 0L && request.state() == TelemetryCapabilityState.ACTIVE);
        root.text = detail == Detail.RAW
                ? AutomationWorkspaceTelemetryFormatter.raw(request)
                : List.of(
                line(TelemetryText.Kind.KEY, "request", TelemetryText.Kind.ID, request.requestId().toString()),
                line(TelemetryText.Kind.KEY, "state", stateKind(request.state()), request.state().name().toLowerCase(Locale.ROOT)),
                line(TelemetryText.Kind.KEY, "root span", TelemetryText.Kind.ID, request.rootSpanId()),
                line(TelemetryText.Kind.KEY, "revision", TelemetryText.Kind.COUNT, Long.toString(request.revision()))
        );
        return root;
    }

    private static void appendRequestMetadata(
            Node root,
            TelemetrySnapshots.Request request,
            Detail detail,
            TelemetryDiagnostics.RequestIntegrity integrity
    ) {
        if (integrity != null) root.add(integrityNode(root.id, integrity, detail));

        Node volumes = new Node(root.id + ":volumes", "Data volume and cache telemetry", "METRICS",
                request.metrics().size() + " metric(s)", TelemetryCapabilityState.AVAILABLE);
        if (detail == Detail.RAW) {
            volumes.text = AutomationWorkspaceTelemetryFormatter.raw(request.metrics());
        } else {
            List<List<TelemetryText>> metricLines = new ArrayList<>();
            request.metrics().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    metricLines.add(line(TelemetryText.Kind.KEY, entry.getKey(), metricKind(entry.getKey()),
                            Long.toString(entry.getValue()))));
            volumes.text = List.copyOf(metricLines);
        }
        root.add(volumes);

        Node provenance = new Node(root.id + ":provenance", "Why the model knew it", "PROVENANCE",
                request.provenance().size() + " observable input(s)", TelemetryCapabilityState.AVAILABLE);
        for (TelemetryProvenance item : request.provenance()) {
            Node fact = new Node("telemetry:provenance:" + item.id(), item.source(), "KNOWLEDGE",
                    item.stage() + " | " + item.producer(), TelemetryCapabilityState.AVAILABLE)
                    .flow(new Flow(FlowKind.KNOWLEDGE, item.timestampMillis(), item.sizeBytes(), "knowledge"));
            fact.text = switch (detail) {
                case SUMMARY -> List.of(
                        line(TelemetryText.Kind.KEY, "stage", TelemetryText.Kind.VALUE, item.stage()),
                        line(TelemetryText.Kind.KEY, "summary", TelemetryText.Kind.PLAIN, item.summary())
                );
                case DETAILS -> List.of(
                        line(TelemetryText.Kind.KEY, "producer", TelemetryText.Kind.SOURCE, item.producer()),
                        line(TelemetryText.Kind.KEY, "stage", TelemetryText.Kind.VALUE, item.stage()),
                        line(TelemetryText.Kind.KEY, "span", TelemetryText.Kind.ID, item.spanId()),
                        line(TelemetryText.Kind.KEY, "bytes", TelemetryText.Kind.COUNT, Long.toString(item.sizeBytes())),
                        line(TelemetryText.Kind.KEY, "tokens est", TelemetryText.Kind.COUNT, Integer.toString(item.estimatedTokens())),
                        line(TelemetryText.Kind.KEY, "digest", TelemetryText.Kind.ID, item.digest()),
                        line(TelemetryText.Kind.KEY, "payload retained", item.payloadTruncated()
                                ? TelemetryText.Kind.WARNING : TelemetryText.Kind.SUCCESS,
                                item.payloadTruncated() ? "truncated to 16384 chars" : "complete"),
                        line(TelemetryText.Kind.KEY, "summary", TelemetryText.Kind.PLAIN, item.summary())
                );
                case RAW -> AutomationWorkspaceTelemetryFormatter.raw(item);
            };
            provenance.add(fact);
            Node owner = find(root, "telemetry:span:" + item.spanId());
            if (owner != null) owner.links.add(fact.id);
        }
        root.add(provenance);

        Node capabilities = new Node(root.id + ":capabilities", "Capability states", "CAPABILITY",
                request.capabilities().size() + " reported capability state(s)", TelemetryCapabilityState.AVAILABLE);
        for (TelemetrySnapshots.Capability capability : request.capabilities()) {
            Node item = new Node(
                    "telemetry:capability:" + request.requestId() + ":" + capability.subsystem() + ":" + capability.capability(),
                    capability.subsystem() + " / " + capability.capability(), "CAPABILITY",
                    capability.state().name().toLowerCase(Locale.ROOT), capability.state());
            item.text = switch (detail) {
                case SUMMARY -> List.of(
                        line(TelemetryText.Kind.KEY, "state", stateKind(capability.state()), capability.state().name().toLowerCase(Locale.ROOT)),
                        line(TelemetryText.Kind.KEY, "reason", capability.state().terminalProblem()
                                ? TelemetryText.Kind.ERROR : TelemetryText.Kind.VALUE, capability.reasonCode())
                );
                case DETAILS -> List.of(
                        line(TelemetryText.Kind.KEY, "state", stateKind(capability.state()), capability.state().name().toLowerCase(Locale.ROOT)),
                        line(TelemetryText.Kind.KEY, "reason", capability.state().terminalProblem()
                                ? TelemetryText.Kind.ERROR : TelemetryText.Kind.VALUE, capability.reasonCode()),
                        line(TelemetryText.Kind.KEY, "detail", TelemetryText.Kind.PLAIN, capability.detail()),
                        line(TelemetryText.Kind.KEY, "updated", TelemetryText.Kind.DURATION, Long.toString(capability.timestampMillis()))
                );
                case RAW -> AutomationWorkspaceTelemetryFormatter.raw(capability);
            };
            capabilities.add(item);
        }
        root.add(capabilities);
    }

    private static Node storeHealthNode(TelemetryDiagnostics.StoreHealth health) {
        if (health == null) return new Node("telemetry:store-health", "Telemetry store health", "OBSERVABILITY",
                "unavailable", TelemetryCapabilityState.UNAVAILABLE);
        TelemetryCapabilityState state = health.degraded()
                ? TelemetryCapabilityState.DEGRADED : TelemetryCapabilityState.AVAILABLE;
        List<List<TelemetryText>> lines = List.of(
                line(TelemetryText.Kind.KEY, "retained requests", TelemetryText.Kind.COUNT, Integer.toString(health.retainedRequests())),
                line(TelemetryText.Kind.KEY, "retention-evicted requests", countKind(health.evictedRequests()), Long.toString(health.evictedRequests())),
                line(TelemetryText.Kind.KEY, "dropped spans", countKind(health.droppedSpans()), Long.toString(health.droppedSpans())),
                line(TelemetryText.Kind.KEY, "dropped events", countKind(health.droppedEvents()), Long.toString(health.droppedEvents())),
                line(TelemetryText.Kind.KEY, "dropped provenance", countKind(health.droppedProvenance()), Long.toString(health.droppedProvenance())),
                line(TelemetryText.Kind.KEY, "missing span writes", countKind(health.missingSpanWrites()), Long.toString(health.missingSpanWrites())),
                line(TelemetryText.Kind.KEY, "repaired parent links", countKind(health.repairedParentLinks()), Long.toString(health.repairedParentLinks())),
                line(TelemetryText.Kind.KEY, "snapshot builds", TelemetryText.Kind.COUNT, Long.toString(health.snapshotBuilds())),
                line(TelemetryText.Kind.KEY, "avg snapshot build", TelemetryText.Kind.DURATION, health.snapshotBuildMicrosAverage() + " us"),
                line(TelemetryText.Kind.KEY, "index reads", TelemetryText.Kind.COUNT, Long.toString(health.indexReads())),
                line(TelemetryText.Kind.KEY, "avg index read", TelemetryText.Kind.DURATION, health.indexReadMicrosAverage() + " us")
        );
        return new Node("telemetry:store-health", "Telemetry store health", "OBSERVABILITY",
                health.degraded() ? "degraded · retention/linkage loss observed" : "healthy · no telemetry loss observed",
                state).text(lines);
    }

    private static Node integrityNode(String parentId, TelemetryDiagnostics.RequestIntegrity integrity, Detail detail) {
        TelemetryCapabilityState state = switch (integrity.highestSeverity()) {
            case ERROR -> TelemetryCapabilityState.FAILED;
            case WARNING -> TelemetryCapabilityState.DEGRADED;
            case INFO -> TelemetryCapabilityState.AVAILABLE;
        };
        List<List<TelemetryText>> lines = new ArrayList<>();
        lines.add(line(TelemetryText.Kind.KEY, "open spans", TelemetryText.Kind.COUNT, Integer.toString(integrity.openSpans())));
        lines.add(line(TelemetryText.Kind.KEY, "orphaned parents", countKind(integrity.orphanedParents()), Integer.toString(integrity.orphanedParents())));
        lines.add(line(TelemetryText.Kind.KEY, "dangling provenance", countKind(integrity.danglingProvenance()), Integer.toString(integrity.danglingProvenance())));
        lines.add(line(TelemetryText.Kind.KEY, "stale active spans", countKind(integrity.staleActiveSpans()), Integer.toString(integrity.staleActiveSpans())));
        lines.add(line(TelemetryText.Kind.KEY, "terminal request open spans", countKind(integrity.terminalRequestOpenSpans()), Integer.toString(integrity.terminalRequestOpenSpans())));
        lines.add(line(TelemetryText.Kind.KEY, "dropped spans", countKind(integrity.droppedSpans()), Long.toString(integrity.droppedSpans())));
        lines.add(line(TelemetryText.Kind.KEY, "dropped events", countKind(integrity.droppedEvents()), Long.toString(integrity.droppedEvents())));
        lines.add(line(TelemetryText.Kind.KEY, "dropped provenance", countKind(integrity.droppedProvenance()), Long.toString(integrity.droppedProvenance())));
        if (detail != Detail.SUMMARY) {
            for (TelemetryDiagnostics.Issue issue : integrity.issues()) {
                TelemetryText.Kind issueKind = switch (issue.severity()) {
                    case ERROR -> TelemetryText.Kind.ERROR;
                    case WARNING -> TelemetryText.Kind.WARNING;
                    case INFO -> TelemetryText.Kind.MUTED;
                };
                lines.add(List.of(
                        new TelemetryText(issueKind, issue.severity().name().toLowerCase(Locale.ROOT)),
                        new TelemetryText(TelemetryText.Kind.PLAIN, "  "),
                        new TelemetryText(TelemetryText.Kind.KEY, issue.code()),
                        new TelemetryText(TelemetryText.Kind.PLAIN, "  "),
                        new TelemetryText(TelemetryText.Kind.PLAIN, issue.detail()),
                        new TelemetryText(issue.spanId().isBlank() ? TelemetryText.Kind.MUTED : TelemetryText.Kind.ID,
                                issue.spanId().isBlank() ? "" : "  " + issue.spanId())
                ));
            }
        }
        return new Node(parentId + ":integrity", "Telemetry integrity", "OBSERVABILITY",
                integrity.healthy() ? "healthy" : integrity.issues().size() + " issue(s)", state).text(lines);
    }

    private static TelemetryText.Kind countKind(long count) {
        return count > 0L ? TelemetryText.Kind.WARNING : TelemetryText.Kind.COUNT;
    }

    private static Node spanNode(TelemetrySnapshots.Span span, String title, Detail detail) {
        Node node = new Node("telemetry:span:" + span.spanId(), title, span.kind().name(),
                span.state().name().toLowerCase(Locale.ROOT) + " | " + span.durationMillis(System.currentTimeMillis()) + " ms",
                span.state());
        node.text = switch (detail) {
            case SUMMARY -> AutomationWorkspaceTelemetryFormatter.summary(span, System.currentTimeMillis());
            case DETAILS -> AutomationWorkspaceTelemetryFormatter.details(span, System.currentTimeMillis());
            case RAW -> AutomationWorkspaceTelemetryFormatter.raw(span);
        };
        node.inboundFlow = flowFor(span);
        node.timing(span.startedAtMillis(), span.durationMillis(System.currentTimeMillis()), span.active());
        return node;
    }

    private static Flow flowFor(TelemetrySnapshots.Span span) {
        if (span == null) return Flow.none();
        FlowKind kind = switch (span.kind()) {
            case REQUEST -> FlowKind.REQUEST;
            case PROMPT_INGESTION, PREFILL, CONTEXT, RETRIEVAL -> FlowKind.CONTEXT;
            case PLANNING, AUTOMATION_PLAN -> FlowKind.PLAN;
            case MODEL_PASS, MODEL_CONTINUATION -> FlowKind.THINKING;
            case SKILL_SELECTION, SKILL_INVOCATION -> FlowKind.SKILL;
            case TOOL_INVOCATION, TOOL_DISPATCH -> FlowKind.TOOL;
            case MAIN_THREAD, EXECUTOR_TASK, KTL_ACTION -> FlowKind.EXECUTION;
            case APPROVAL -> FlowKind.APPROVAL;
            case SERIALIZATION -> FlowKind.SERIALIZATION;
            case TOKEN_GENERATION, OUTPUT -> FlowKind.OUTPUT;
            case QUEUE_WAIT, MODEL_STARTUP -> FlowKind.QUEUE;
            case VALIDATION -> FlowKind.VALIDATION;
            case OTHER -> FlowKind.NONE;
        };
        long amount = 0L;
        for (Map.Entry<String, Long> metric : span.metrics().entrySet()) {
            String key = safe(metric.getKey()).toLowerCase(Locale.ROOT);
            if (key.contains("bytes") || key.contains("token") || key.contains("count")) {
                amount = Math.max(amount, metric.getValue() == null ? 0L : metric.getValue());
            }
        }
        return new Flow(kind, span.startedAtMillis(), amount, flowLabel(kind));
    }

    private static String flowLabel(FlowKind kind) {
        return switch (kind == null ? FlowKind.NONE : kind) {
            case REQUEST -> "request";
            case CONTEXT -> "context";
            case PLAN -> "plan";
            case THINKING -> "thinking";
            case SKILL -> "skill";
            case TOOL -> "tool";
            case EXECUTION -> "execute";
            case APPROVAL -> "approval";
            case SERIALIZATION -> "serialize";
            case OUTPUT -> "output";
            case QUEUE -> "queue";
            case VALIDATION -> "validate";
            case KNOWLEDGE -> "knowledge";
            case NONE -> "";
        };
    }

    private static Node unavailable(UUID requestId) {
        return new Node("telemetry:missing:" + requestId, "Telemetry not recorded", "OBSERVABILITY",
                "not observed", TelemetryCapabilityState.BLOCKED).text(List.of(
                line(TelemetryText.Kind.KEY, "request", TelemetryText.Kind.ID, String.valueOf(requestId)),
                line(TelemetryText.Kind.KEY, "state", TelemetryText.Kind.WARNING, "not observed"),
                line(TelemetryText.Kind.KEY, "meaning", TelemetryText.Kind.PLAIN,
                        "No inferred causal links are fabricated when telemetry is absent.")
        ));
    }

    private static Node find(Node root, String id) {
        if (root == null) return null;
        if (root.id.equals(id)) return root;
        for (Node child : root.children) {
            Node found = find(child, id);
            if (found != null) return found;
        }
        return null;
    }

    private static List<TelemetryText> line(TelemetryText.Kind keyKind, String key,
                                            TelemetryText.Kind valueKind, String value) {
        return List.of(
                new TelemetryText(keyKind, key),
                new TelemetryText(TelemetryText.Kind.PLAIN, " = "),
                new TelemetryText(valueKind, value == null ? "" : value)
        );
    }

    private static TelemetryText.Kind stateKind(TelemetryCapabilityState state) {
        if (state == null) return TelemetryText.Kind.STATE;
        return switch (state) {
            case AVAILABLE, ACTIVE -> TelemetryText.Kind.SUCCESS;
            case DEGRADED, BLOCKED, CANCELLED -> TelemetryText.Kind.WARNING;
            case NOT_IMPLEMENTED, UNAVAILABLE, FAILED -> TelemetryText.Kind.ERROR;
            case IDLE -> TelemetryText.Kind.MUTED;
        };
    }

    private static TelemetryText.Kind metricKind(String key) {
        String normalized = safe(key).toLowerCase(Locale.ROOT);
        return normalized.endsWith("_ms") || normalized.endsWith("_us")
                || normalized.contains("duration") || normalized.contains("latency")
                ? TelemetryText.Kind.DURATION : TelemetryText.Kind.COUNT;
    }

    private static String shortId(UUID id) {
        if (id == null) return "none";
        String value = id.toString();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private static String duration(long millis) {
        return millis < 0L ? "missing" : millis + "ms";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
