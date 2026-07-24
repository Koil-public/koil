package com.spirit.koil.api.registry.client;

import com.spirit.koil.api.registry.ActiveContentResourcePackSet;
import com.spirit.koil.api.registry.ContentActivationEvents;
import com.spirit.koil.api.registry.DynamicRegistryManager;
import com.spirit.koil.api.registry.WorldContentIndex;
import com.spirit.koil.api.world.WorldInstanceResourceProfileService;
import com.spirit.mixin.client.content.MinecraftClientContentReloadAccessor;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.SplashOverlay;
import net.minecraft.resource.ResourcePackManager;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.spirit.Main.SUBLOGGER;

/**
 * Applies active Content datapack assets to the integrated client and coalesces
 * overlapping activation/reload requests into sequential resource reloads.
 */
public final class ActiveWorldContentResourceBridge {
    private static final int WORLD_JOIN_SETTLE_TICKS = 40;
    private static final int RELOAD_QUIET_TICKS = 4;
    private static final int PRE_JOIN_IDLE_TICKS = 40;

    private static boolean initialized;
    private static boolean reloadInFlight;
    private static long inFlightRevision;
    private static long requestedRevision;
    private static long completedRevision;
    private static long observedReloadRevision;
    private static int worldJoinSettleTicks;
    private static int reloadQuietTicks;
    private static int preJoinIdleTicks;
    private static List<ActiveContentResourcePackSet.PackSource> desiredSources = List.of();
    private static final List<RevisionWaiter> REVISION_WAITERS = new ArrayList<>();
    private static String preloadedWorldPath = "";
    private static boolean unloadedBeforeDisconnect;

    private ActiveWorldContentResourceBridge() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        ContentActivationEvents.register(ActiveWorldContentResourceBridge::queueSnapshot);
        MinecraftClient initialClient = MinecraftClient.getInstance();
        if (initialClient != null) {
            initialClient.execute(() -> removeTransientProfileOptions(initialClient));
        }
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            worldJoinSettleTicks = WORLD_JOIN_SETTLE_TICKS;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            worldJoinSettleTicks = 0;
            reloadQuietTicks = Math.max(reloadQuietTicks, RELOAD_QUIET_TICKS);
            if (client != null) {
                client.execute(() -> {
                    boolean profileChanged =
                            WorldInstanceResourceProfileService.restoreBeforeDisconnect(client);
                    clearIfNeeded(client, profileChanged);
                });
            }
        });
    }

    public static List<ActiveContentResourcePackSet.PackSource> activeSources() {
        return ActiveWorldContentResourcePackProvider.INSTANCE.sources();
    }

    /**
     * Refreshes and loads one selected world's Content assets before Minecraft
     * begins opening that world.
     */
    public static CompletableFuture<Void> prepareWorldBeforeJoin(
            MinecraftClient client,
            Path worldPath,
            boolean forceReload
    ) {
        if (client == null || worldPath == null) {
            return CompletableFuture.completedFuture(null);
        }
        Path normalized = worldPath.toAbsolutePath().normalize();
        WorldContentIndex.WorldEntry world =
                DynamicRegistryManager.instance().refreshWorldIndex(normalized);
        WorldContentIndex.ActiveWorldSnapshot preview = previewSnapshot(world);
        List<ActiveContentResourcePackSet.PackSource> resolved = ActiveContentResourcePackSet.resolve(
                DynamicRegistryManager.instance().worldIndex(),
                preview
        );
        preloadedWorldPath = normalized.toString();
        unloadedBeforeDisconnect = false;
        preJoinIdleTicks = PRE_JOIN_IDLE_TICKS;
        long revision = request(client, resolved, forceReload);
        return awaitRevision(revision);
    }

    /**
     * Removes active Content assets while the saving screen still covers the
     * old world. The title screen is revealed only after this revision settles.
     */
    public static CompletableFuture<Void> unloadBeforeDisconnect(
            MinecraftClient client,
            boolean forceReload
    ) {
        if (client == null) {
            return CompletableFuture.completedFuture(null);
        }
        preloadedWorldPath = "";
        unloadedBeforeDisconnect = true;
        worldJoinSettleTicks = 0;
        long revision = request(client, List.of(), forceReload);
        return awaitRevision(revision);
    }

    /**
     * Advances a pending Content resource transition after Minecraft's current
     * splash reload has finished. Calling reloadResources while a splash reload
     * is active only returns that old future and does not include newly scanned
     * profiles, so this small lifecycle check is required for reliable hot edits.
     */
    public static void tick(MinecraftClient client) {
        if (client != null) {
            observeReloadActivity();
            finishReloadIfMinecraftIsIdle(client);
            if (worldJoinSettleTicks > 0) {
                worldJoinSettleTicks--;
                return;
            }
            if (preJoinIdleTicks > 0) {
                if (minecraftReloadIsBusy(client)) {
                    preJoinIdleTicks = PRE_JOIN_IDLE_TICKS;
                    return;
                }
                preJoinIdleTicks--;
                return;
            }
            if (reloadQuietTicks > 0) {
                reloadQuietTicks--;
                return;
            }
            drain(client);
        }
    }

    private static void queueSnapshot(WorldContentIndex.ActiveWorldSnapshot snapshot) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> {
            List<ActiveContentResourcePackSet.PackSource> resolved = ActiveContentResourcePackSet.resolve(
                    DynamicRegistryManager.instance().worldIndex(),
                    snapshot
            );
            boolean alreadyPrepared = consumePreparedTransition(snapshot, resolved);
            request(client, resolved, !alreadyPrepared);
        });
    }

    private static void clearIfNeeded(MinecraftClient client, boolean forceReload) {
        if (!forceReload
                && desiredSources.isEmpty()
                && ActiveWorldContentResourcePackProvider.INSTANCE.sources().isEmpty()) {
            return;
        }
        request(client, List.of(), forceReload);
    }

    private static long request(
            MinecraftClient client,
            List<ActiveContentResourcePackSet.PackSource> sources,
            boolean forceReload
    ) {
        List<ActiveContentResourcePackSet.PackSource> immutable = List.copyOf(sources);
        if (immutable.equals(desiredSources)
                && (!forceReload || completedRevision < requestedRevision)) {
            return requestedRevision;
        }
        desiredSources = immutable;
        requestedRevision++;
        replaceProfileCatalog(client, immutable);
        return requestedRevision;
    }

    private static void drain(MinecraftClient client) {
        if (reloadInFlight || completedRevision >= requestedRevision) {
            return;
        }
        if (minecraftReloadIsBusy(client)) {
            return;
        }
        ResourcePackManager manager = client.getResourcePackManager();
        if (manager == null) {
            return;
        }

        long revision = requestedRevision;
        List<ActiveContentResourcePackSet.PackSource> applying = desiredSources;
        replaceProfileCatalog(client, applying);
        reloadInFlight = true;
        inFlightRevision = revision;
        SUBLOGGER.logI(
                "Content Resources",
                "Reloading " + applying.size() + " active world Content resource pack(s)."
        );

        CompletableFuture<Void> reload = ContentClientResourceReload.start(client);
        reload.whenComplete((ignored, failure) -> client.execute(() -> {
            if (inFlightRevision != revision) {
                return;
            }
            if (failure == null) {
                SUBLOGGER.logI(
                        "Content Resources",
                        "Applied " + applying.size() + " active world Content resource pack(s)."
                );
            } else {
                SUBLOGGER.logE(
                        "Content Resources",
                        "Client resource reload failed without changing the Content source files: "
                                + failure.getMessage()
                );
            }
            finishReload(revision, client);
            drain(client);
        }));
    }

    private static void finishReloadIfMinecraftIsIdle(MinecraftClient client) {
        if (reloadInFlight && !minecraftReloadIsBusy(client)) {
            long revision = inFlightRevision;
            SUBLOGGER.logI(
                    "Content Resources",
                    "Minecraft completed Content resource revision " + revision + "."
            );
            finishReload(revision, client);
        }
    }

    private static void finishReload(long revision, MinecraftClient client) {
        if (!reloadInFlight || inFlightRevision != revision) {
            return;
        }
        removeTransientProfileOptions(client);
        reloadInFlight = false;
        inFlightRevision = 0L;
        completedRevision = Math.max(completedRevision, revision);
        completeRevisionWaiters();
    }

    private static boolean minecraftReloadIsBusy(MinecraftClient client) {
        if (client.getOverlay() instanceof SplashOverlay
                || ((MinecraftClientContentReloadAccessor) (Object) client)
                .koil$getContentResourceReloadFuture() != null) {
            return true;
        }
        return ClientResourceReloadTracker.isAnyReloading()
                || client.getResourceManager() instanceof net.minecraft.resource.ReloadableResourceManagerImpl manager
                && ClientResourceReloadTracker.isReloading(manager);
    }

    private static void observeReloadActivity() {
        long revision = ClientResourceReloadTracker.revision();
        if (revision != observedReloadRevision) {
            observedReloadRevision = revision;
            reloadQuietTicks = Math.max(reloadQuietTicks, RELOAD_QUIET_TICKS);
        }
    }

    private static void replaceProfileCatalog(
            MinecraftClient client,
            List<ActiveContentResourcePackSet.PackSource> sources
    ) {
        removeTransientProfileOptions(client);
        ActiveWorldContentResourcePackProvider.INSTANCE.replace(sources);
        ResourcePackManager manager = client.getResourcePackManager();
        if (manager != null) {
            manager.scanPacks();
        }
    }

    private static void removeTransientProfileOptions(MinecraftClient client) {
        if (client == null || client.options == null) {
            return;
        }
        boolean changed = client.options.resourcePacks.removeIf(ActiveWorldContentResourceBridge::isTransientProfile);
        changed |= client.options.incompatibleResourcePacks.removeIf(ActiveWorldContentResourceBridge::isTransientProfile);
        if (changed) {
            client.options.write();
        }
    }

    private static boolean isTransientProfile(String name) {
        return name != null && name.startsWith("koil_content/");
    }

    private static WorldContentIndex.ActiveWorldSnapshot previewSnapshot(
            WorldContentIndex.WorldEntry world
    ) {
        if (world == null) {
            return WorldContentIndex.ActiveWorldSnapshot.empty(Instant.now().toString());
        }
        List<String> enabledPackIds = world.packs().stream()
                .filter(pack -> "enabled".equals(pack.state()))
                .map(WorldContentIndex.PackEntry::packId)
                .toList();
        List<WorldContentIndex.DefinitionEntry> definitions = world.packs().stream()
                .filter(pack -> enabledPackIds.contains(pack.packId()))
                .flatMap(pack -> pack.definitions().stream())
                .filter(WorldContentIndex.DefinitionEntry::activatable)
                .toList();
        return new WorldContentIndex.ActiveWorldSnapshot(
                WorldContentIndex.CURRENT_SCHEMA_VERSION,
                Instant.now().toString(),
                world.worldId(),
                world.worldPath(),
                enabledPackIds,
                definitions,
                world.validation()
        );
    }

    private static boolean consumePreparedTransition(
            WorldContentIndex.ActiveWorldSnapshot snapshot,
            List<ActiveContentResourcePackSet.PackSource> resolved
    ) {
        if (snapshot.worldPath().isBlank()) {
            if (unloadedBeforeDisconnect && resolved.isEmpty() && desiredSources.isEmpty()) {
                unloadedBeforeDisconnect = false;
                return true;
            }
            return false;
        }
        boolean matches = samePath(preloadedWorldPath, snapshot.worldPath())
                && resolved.equals(desiredSources)
                && resolved.equals(ActiveWorldContentResourcePackProvider.INSTANCE.sources());
        if (matches) {
            preloadedWorldPath = "";
        }
        return matches;
    }

    private static boolean samePath(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return false;
        }
        try {
            return Path.of(left).toAbsolutePath().normalize()
                    .equals(Path.of(right).toAbsolutePath().normalize());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static CompletableFuture<Void> awaitRevision(long revision) {
        if (completedRevision >= revision) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        REVISION_WAITERS.add(new RevisionWaiter(revision, future));
        return future;
    }

    private static void completeRevisionWaiters() {
        REVISION_WAITERS.removeIf(waiter -> {
            if (completedRevision < waiter.revision()) {
                return false;
            }
            waiter.future().complete(null);
            return true;
        });
    }

    private record RevisionWaiter(long revision, CompletableFuture<Void> future) {
    }
}
