package com.spirit.koil.api.automation.navigation;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-route asynchronous cache. New terrain fingerprints cancel stale expansion. */
public final class RecedingHorizonNavigationController implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Koil-Navigation-AStar");
        thread.setDaemon(true);
        return thread;
    });
    private CompletableFuture<BoundedNavigationPlanner.Plan> pending;
    private AtomicBoolean cancellation = new AtomicBoolean();
    private long requestedFingerprint = Long.MIN_VALUE;
    private int requestedRoute = 0;
    private int cachedRoute = 0;
    private BoundedNavigationPlanner.Plan cached;

    public synchronized Optional<BoundedNavigationPlanner.Plan> poll(
            BoundedNavigationSnapshot snapshot, BoundedNavigationPlanner.Request request) {
        if (pending != null && pending.isDone()) {
            try {
                BoundedNavigationPlanner.Plan completed = pending.join();
                if (completed.snapshotFingerprint() == requestedFingerprint) {
                    cached = completed;
                    cachedRoute = requestedRoute;
                }
            } catch (RuntimeException ignored) {
                cached = null;
            }
            pending = null;
        }
        int route = request.hashCode();
        if ((snapshot.fingerprint() != requestedFingerprint || route != requestedRoute)) {
            cancellation.set(true);
            if (pending != null) pending.cancel(true);
            cancellation = new AtomicBoolean();
            requestedFingerprint = snapshot.fingerprint();
            requestedRoute = route;
            AtomicBoolean token = cancellation;
            pending = CompletableFuture.supplyAsync(
                    () -> BoundedNavigationPlanner.plan(snapshot, request, token::get), executor);
        }
        return cached != null && cached.snapshotFingerprint() == snapshot.fingerprint() && cachedRoute == route
                ? Optional.of(cached) : Optional.empty();
    }

    public synchronized void cancel() {
        cancellation.set(true);
        if (pending != null) pending.cancel(true);
        pending = null;
        cached = null;
        requestedFingerprint = Long.MIN_VALUE;
        requestedRoute = 0;
        cachedRoute = 0;
    }

    @Override
    public synchronized void close() {
        cancel();
        executor.shutdownNow();
    }
}
