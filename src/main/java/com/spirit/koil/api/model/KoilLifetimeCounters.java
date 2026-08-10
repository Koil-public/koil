package com.spirit.koil.api.model;

import java.util.concurrent.atomic.AtomicLong;

/** Startup-lifetime request/session counters. Values are never persisted. */
public final class KoilLifetimeCounters {
    private static final AtomicLong MODEL_REQUESTS = new AtomicLong();
    private static final AtomicLong AUTOMATION_SESSIONS = new AtomicLong();

    private KoilLifetimeCounters() {
    }

    public static Snapshot modelRequestStarted() {
        MODEL_REQUESTS.incrementAndGet();
        return snapshot();
    }

    public static Snapshot automationSessionStarted() {
        AUTOMATION_SESSIONS.incrementAndGet();
        return snapshot();
    }

    public static Snapshot snapshot() {
        long kms = MODEL_REQUESTS.get();
        long kes = AUTOMATION_SESSIONS.get();
        return new Snapshot(kms, kes, kms + kes);
    }

    public static void resetForProof() {
        MODEL_REQUESTS.set(0L);
        AUTOMATION_SESSIONS.set(0L);
    }

    public record Snapshot(long kms, long kes, long kts) {
        public Snapshot {
            kms = Math.max(0L, kms);
            kes = Math.max(0L, kes);
            kts = Math.max(kms + kes, kts);
        }

        /** Compatibility adapter for integrations compiled against the old name. */
        @Deprecated
        public long kas() {
            return kes;
        }
    }
}
