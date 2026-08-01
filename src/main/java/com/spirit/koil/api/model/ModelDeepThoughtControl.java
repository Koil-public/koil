package com.spirit.koil.api.model;

public interface ModelDeepThoughtControl {
    boolean pause();
    boolean resume();
    Status status();

    record Status(
            String sessionId,
            String phase,
            long activeMillis,
            int evidenceCount,
            int verifiedClaims,
            int unresolvedClaims,
            int hypothesisCount,
            int contradictionCount,
            int testsPassed,
            int testsFailed,
            String confidence,
            String lastDiscovery,
            boolean paused
    ) {}
}
