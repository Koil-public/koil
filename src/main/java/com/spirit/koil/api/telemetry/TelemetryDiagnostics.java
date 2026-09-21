package com.spirit.koil.api.telemetry;

import java.util.List;
import java.util.UUID;

/**
 * Immutable health and integrity diagnostics for Koil's telemetry pipeline.
 * These records describe the observability system itself, so failures in
 * telemetry retention or causal linkage are visible instead of silently
 * disappearing from the workspace.
 */
public final class TelemetryDiagnostics {
    private TelemetryDiagnostics() {}

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    public record RequestIndex(
            UUID requestId,
            long revision,
            long createdAtMillis,
            boolean completed
    ) {
        public RequestIndex {
            revision = Math.max(0L, revision);
            createdAtMillis = Math.max(0L, createdAtMillis);
        }
    }

    public record StoreHealth(
            long revision,
            int retainedRequests,
            long evictedRequests,
            long droppedSpans,
            long droppedEvents,
            long droppedProvenance,
            long implicitRequests,
            long missingSpanWrites,
            long repairedParentLinks,
            long duplicateFinishes,
            long snapshotBuilds,
            long snapshotBuildNanos,
            long indexReads,
            long indexReadNanos
    ) {
        public StoreHealth {
            revision = Math.max(0L, revision);
            retainedRequests = Math.max(0, retainedRequests);
            evictedRequests = Math.max(0L, evictedRequests);
            droppedSpans = Math.max(0L, droppedSpans);
            droppedEvents = Math.max(0L, droppedEvents);
            droppedProvenance = Math.max(0L, droppedProvenance);
            implicitRequests = Math.max(0L, implicitRequests);
            missingSpanWrites = Math.max(0L, missingSpanWrites);
            repairedParentLinks = Math.max(0L, repairedParentLinks);
            duplicateFinishes = Math.max(0L, duplicateFinishes);
            snapshotBuilds = Math.max(0L, snapshotBuilds);
            snapshotBuildNanos = Math.max(0L, snapshotBuildNanos);
            indexReads = Math.max(0L, indexReads);
            indexReadNanos = Math.max(0L, indexReadNanos);
        }

        public long snapshotBuildMicrosAverage() {
            return snapshotBuilds <= 0L ? 0L : (snapshotBuildNanos / snapshotBuilds) / 1_000L;
        }

        public long indexReadMicrosAverage() {
            return indexReads <= 0L ? 0L : (indexReadNanos / indexReads) / 1_000L;
        }

        public boolean degraded() {
            return droppedSpans > 0L || droppedEvents > 0L
                    || droppedProvenance > 0L || missingSpanWrites > 0L || repairedParentLinks > 0L;
        }
    }

    public record Issue(
            Severity severity,
            String code,
            String detail,
            String spanId
    ) {
        public Issue {
            severity = severity == null ? Severity.INFO : severity;
            code = clean(code);
            detail = clean(detail);
            spanId = clean(spanId);
        }
    }

    public record RequestIntegrity(
            UUID requestId,
            long requestRevision,
            int openSpans,
            int orphanedParents,
            int danglingProvenance,
            int staleActiveSpans,
            int terminalRequestOpenSpans,
            long droppedSpans,
            long droppedEvents,
            long droppedProvenance,
            List<Issue> issues
    ) {
        public RequestIntegrity {
            requestRevision = Math.max(0L, requestRevision);
            openSpans = Math.max(0, openSpans);
            orphanedParents = Math.max(0, orphanedParents);
            danglingProvenance = Math.max(0, danglingProvenance);
            staleActiveSpans = Math.max(0, staleActiveSpans);
            terminalRequestOpenSpans = Math.max(0, terminalRequestOpenSpans);
            droppedSpans = Math.max(0L, droppedSpans);
            droppedEvents = Math.max(0L, droppedEvents);
            droppedProvenance = Math.max(0L, droppedProvenance);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public boolean healthy() {
            return issues.stream().noneMatch(issue -> issue.severity() == Severity.ERROR)
                    && orphanedParents == 0
                    && danglingProvenance == 0
                    && terminalRequestOpenSpans == 0
                    && droppedSpans == 0L
                    && droppedEvents == 0L
                    && droppedProvenance == 0L;
        }

        public Severity highestSeverity() {
            Severity highest = Severity.INFO;
            for (Issue issue : issues) {
                if (issue.severity() == Severity.ERROR) return Severity.ERROR;
                if (issue.severity() == Severity.WARNING) highest = Severity.WARNING;
            }
            return highest;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').strip();
    }
}
