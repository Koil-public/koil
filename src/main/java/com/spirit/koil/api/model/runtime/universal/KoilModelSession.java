package com.spirit.koil.api.model.runtime.universal;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One architecture-neutral loaded-model/session identity owned by the unified runtime. */
public final class KoilModelSession implements AutoCloseable {
    private final UUID id;
    private final KoilModelProfile modelProfile;
    private final AtomicReference<KoilExecutionPlan> executionPlan;
    private final KoilModelStateStore stateStore;
    private final Instant createdAt;
    private final AtomicBoolean closed = new AtomicBoolean();

    public KoilModelSession(KoilModelProfile modelProfile, KoilExecutionPlan executionPlan) {
        this(UUID.randomUUID(), modelProfile, executionPlan, new KoilModelStateStore(), Instant.now());
    }

    KoilModelSession(UUID id, KoilModelProfile modelProfile, KoilExecutionPlan executionPlan,
                     KoilModelStateStore stateStore, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.modelProfile = modelProfile;
        this.executionPlan = new AtomicReference<>(Objects.requireNonNull(executionPlan, "executionPlan"));
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public UUID id() { return this.id; }
    public KoilModelProfile modelProfile() { return this.modelProfile; }
    public KoilExecutionPlan executionPlan() { return this.executionPlan.get(); }
    public void updateExecutionPlan(KoilExecutionPlan plan) {
        if (!closed() && plan != null) this.executionPlan.set(plan);
    }
    public KoilModelStateStore stateStore() { return this.stateStore; }
    public Instant createdAt() { return this.createdAt; }
    public boolean closed() { return this.closed.get(); }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) this.stateStore.clear();
    }
}
