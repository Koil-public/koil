package com.spirit.koil.api.automation.runtime;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Atomic runtime ownership for KTL-declared resources. */
public final class AutomationResourceLockManager {
    private final Map<String, Lease> owners = new LinkedHashMap<>();

    public synchronized Acquisition acquire(String frameId, Set<String> requestedLocks, int priority) {
        String owner = frameId == null ? "" : frameId.strip();
        if (owner.isBlank()) throw new IllegalArgumentException("lock owner is required");
        Set<String> locks = normalize(requestedLocks);
        for (String lock : locks) {
            Lease held = owners.get(lock);
            if (held != null && !owner.equals(held.frameId())) {
                return new Acquisition(false, held.frameId(), locks);
            }
        }
        for (String lock : locks) {
            owners.put(lock, new Lease(owner, Math.max(0, priority)));
        }
        return new Acquisition(true, "", locks);
    }

    public synchronized void release(String frameId) {
        String owner = frameId == null ? "" : frameId.strip();
        owners.entrySet().removeIf(entry -> owner.equals(entry.getValue().frameId()));
    }

    public synchronized boolean heldBy(String frameId, String lock) {
        Lease held = owners.get(lock == null ? "" : lock.strip());
        return held != null && held.frameId().equals(frameId == null ? "" : frameId.strip());
    }

    public synchronized Map<String, String> snapshot() {
        Map<String, String> view = new LinkedHashMap<>();
        owners.forEach((lock, lease) -> view.put(lock, lease.frameId()));
        return Map.copyOf(view);
    }

    private static Set<String> normalize(Set<String> requestedLocks) {
        Set<String> locks = new LinkedHashSet<>();
        if (requestedLocks != null) {
            for (String lock : requestedLocks) {
                if (lock != null && !lock.isBlank()) locks.add(lock.strip());
            }
        }
        return Set.copyOf(locks);
    }

    public record Acquisition(boolean granted, String blockingFrameId, Set<String> locks) {
        public Acquisition {
            blockingFrameId = blockingFrameId == null ? "" : blockingFrameId;
            locks = locks == null ? Set.of() : Set.copyOf(locks);
        }
    }

    private record Lease(String frameId, int priority) {
    }
}
