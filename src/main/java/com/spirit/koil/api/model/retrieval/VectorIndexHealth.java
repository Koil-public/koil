package com.spirit.koil.api.model.retrieval;

/** Truthful backend availability snapshot; diagnostics never imply native availability. */
public record VectorIndexHealth(State state, String backend, String detail, long entryCount, int dimensions) {
    public enum State { READY, DEGRADED, UNAVAILABLE, REBUILD_REQUIRED, CLOSED }

    public VectorIndexHealth {
        state = state == null ? State.UNAVAILABLE : state;
        backend = backend == null || backend.isBlank() ? "unknown" : backend.strip();
        detail = detail == null ? "" : detail.strip();
        if (entryCount < 0L) throw new IllegalArgumentException("entryCount must not be negative");
        if (dimensions < 0) throw new IllegalArgumentException("dimensions must not be negative");
    }

    public static VectorIndexHealth ready(String backend, long entryCount, int dimensions) {
        return new VectorIndexHealth(State.READY, backend, "", entryCount, dimensions);
    }

    public boolean ready() {
        return this.state == State.READY;
    }
}
