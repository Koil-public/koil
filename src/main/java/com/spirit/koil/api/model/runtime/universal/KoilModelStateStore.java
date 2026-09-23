package com.spirit.koil.api.model.runtime.universal;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Architecture-neutral ownership for persistent native model state. */
public final class KoilModelStateStore {
    private final EnumMap<KoilModelStateKind, StateHandle> states = new EnumMap<>(KoilModelStateKind.class);

    public synchronized void put(StateHandle state) {
        Objects.requireNonNull(state, "state");
        this.states.put(state.kind(), state);
    }

    public synchronized Optional<StateHandle> get(KoilModelStateKind kind) {
        return Optional.ofNullable(this.states.get(kind));
    }

    public synchronized void remove(KoilModelStateKind kind) {
        if (kind != null) this.states.remove(kind);
    }

    public synchronized void clear() {
        this.states.clear();
    }

    public synchronized Map<KoilModelStateKind, StateHandle> snapshot() {
        return Map.copyOf(this.states);
    }

    public synchronized long estimatedBytes() {
        return this.states.values().stream().mapToLong(StateHandle::estimatedBytes).sum();
    }

    public record StateHandle(
            KoilModelStateKind kind,
            String nativeHandle,
            long estimatedBytes,
            Map<String, String> metadata
    ) {
        public StateHandle {
            if (kind == null) throw new IllegalArgumentException("state kind is required");
            nativeHandle = nativeHandle == null ? "" : nativeHandle.strip();
            estimatedBytes = Math.max(0L, estimatedBytes);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
