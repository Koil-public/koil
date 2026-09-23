package com.spirit.koil.api.model.codeintelligence;

import java.util.ArrayDeque;
import java.util.List;

/** Bounded metadata-only diagnostic history; never stores source text or MCP payloads. */
public final class CodeIntelligenceProvenance {
    private static final int MAXIMUM_ENTRIES = 256;
    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();

    private CodeIntelligenceProvenance() { }

    public static synchronized void record(Entry entry) {
        if (entry == null) return;
        while (ENTRIES.size() >= MAXIMUM_ENTRIES) ENTRIES.removeFirst();
        ENTRIES.addLast(entry);
    }

    public static synchronized List<Entry> recent() {
        return List.copyOf(ENTRIES);
    }

    static synchronized void clear() {
        ENTRIES.clear();
    }

    public record Entry(
            String requestId,
            String sessionId,
            String workspace,
            String providerTool,
            String externalTool,
            String argumentSummary,
            long startedAtMillis,
            long completedAtMillis,
            String status,
            int resultCount,
            boolean truncated,
            String indexState,
            String provider,
            String providerVersion
    ) {
        public Entry {
            requestId = clean(requestId, 96);
            sessionId = clean(sessionId, 96);
            workspace = clean(workspace, 512);
            providerTool = clean(providerTool, 96);
            externalTool = clean(externalTool, 96);
            argumentSummary = clean(argumentSummary, 512);
            long now = System.currentTimeMillis();
            startedAtMillis = startedAtMillis <= 0L ? now : startedAtMillis;
            completedAtMillis = Math.max(startedAtMillis, completedAtMillis);
            status = clean(status, 48);
            resultCount = Math.max(0, resultCount);
            indexState = clean(indexState, 48);
            provider = clean(provider, 96);
            providerVersion = clean(providerVersion, 96);
        }

        private static String clean(String value, int maximum) {
            String clean = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').strip();
            return clean.length() <= maximum ? clean : clean.substring(0, maximum);
        }
    }
}
