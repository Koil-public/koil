package com.spirit.koil.api.registry.client;

import net.minecraft.resource.ReloadableResourceManagerImpl;
import net.minecraft.resource.ResourceReload;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Version-local observation of every client resource-manager reload.
 *
 * <p>Minecraft's private reload future does not cover callers that invoke the
 * lower-level manager directly. Content uses this tracker only to wait for a
 * safe idle boundary; it never cancels or replaces another system's reload.</p>
 */
public final class ClientResourceReloadTracker {
    private static final Map<ReloadableResourceManagerImpl, CompletableFuture<?>> ACTIVE =
            new IdentityHashMap<>();
    private static long revision;

    private ClientResourceReloadTracker() {
    }

    public static void observe(ReloadableResourceManagerImpl manager, ResourceReload reload) {
        if (manager == null || reload == null) {
            return;
        }
        CompletableFuture<?> completion = reload.whenComplete();
        synchronized (ClientResourceReloadTracker.class) {
            ACTIVE.put(manager, completion);
            revision++;
        }
        completion.whenComplete((ignored, failure) -> complete(manager, completion));
    }

    public static synchronized boolean isReloading(ReloadableResourceManagerImpl manager) {
        CompletableFuture<?> completion = ACTIVE.get(manager);
        return completion != null && !completion.isDone();
    }

    public static synchronized boolean isAnyReloading() {
        return ACTIVE.values().stream().anyMatch(future -> !future.isDone());
    }

    public static synchronized long revision() {
        return revision;
    }

    private static synchronized void complete(
            ReloadableResourceManagerImpl manager,
            CompletableFuture<?> completion
    ) {
        if (ACTIVE.get(manager) == completion) {
            ACTIVE.remove(manager);
            revision++;
        }
    }

}
