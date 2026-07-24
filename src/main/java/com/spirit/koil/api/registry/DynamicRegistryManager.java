package com.spirit.koil.api.registry;

import com.spirit.koil.api.registry.definition.ContentDefinition;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.spirit.Main.SUBLOGGER;

/** Central lifecycle owner for world-scoped Content discovery and active definition state. */
public final class DynamicRegistryManager {
    private static final DynamicRegistryManager INSTANCE = new DynamicRegistryManager();

    private final AtomicBoolean initialized = new AtomicBoolean();
    private final Path gameDirectory;
    private final WorldDatapackScanner scanner;
    private final ContentIndexStore indexStore;
    private final RuntimeDefinitionStore runtimeStore = new RuntimeDefinitionStore();
    private final List<ContentReloadResult> reloadHistory = new ArrayList<>();
    private volatile WorldContentIndex worldIndex;
    private volatile ContentReloadResult lastReloadResult = new ContentReloadResult(
            "", "not_run", "", "", true, 0, java.util.List.of(), java.util.List.of()
    );

    private DynamicRegistryManager() {
        gameDirectory = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        scanner = new WorldDatapackScanner(gameDirectory.resolve("saves"));
        indexStore = new ContentIndexStore(gameDirectory);
        worldIndex = new WorldContentIndex(
                WorldContentIndex.CURRENT_SCHEMA_VERSION,
                "",
                gameDirectory.resolve("saves").toString(),
                java.util.List.of(),
                java.util.List.of()
        );
    }

    public static DynamicRegistryManager instance() {
        return INSTANCE;
    }

    public static void initialize() {
        INSTANCE.start();
    }

    private void start() {
        if (!initialized.compareAndSet(false, true)) {
            return;
        }
        scanWorlds();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> activateServerWorld(server, "world_start"));
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success) {
                activateServerWorld(server, "datapack_reload");
            } else {
                recordFailedReload("datapack_reload", "Minecraft datapack reload failed.");
                SUBLOGGER.logW("Content Registry", "Datapack reload failed; retaining the last valid active Content snapshot.");
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> deactivateWorld());
    }

    public synchronized WorldContentIndex scanWorlds() {
        ContentRegistryEvents.beforeScan(gameDirectory.resolve("saves"));
        worldIndex = scanner.scanAllWorlds();
        try {
            indexStore.writeWorldIndex(worldIndex);
        } catch (IOException exception) {
            SUBLOGGER.logE("Content Registry", "Failed to write world content index: " + exception.getMessage());
        }
        SUBLOGGER.logI(
                "Content Registry",
                "Indexed " + worldIndex.worlds().size() + " worlds and " + worldIndex.definitionCount() + " Content definitions."
        );
        ContentRegistryEvents.afterScan(worldIndex);
        return worldIndex;
    }

    public WorldContentIndex worldIndex() {
        return worldIndex;
    }

    /**
     * Refreshes only the selected world before a client-side Content preload.
     * This avoids rescanning every save on each world-selection action.
     */
    public synchronized WorldContentIndex.WorldEntry refreshWorldIndex(Path worldPath) {
        WorldContentIndex.WorldEntry refreshed = scanner.scanWorld(worldPath);
        List<WorldContentIndex.WorldEntry> worlds = new ArrayList<>(worldIndex.worlds());
        boolean replaced = false;
        for (int index = 0; index < worlds.size(); index++) {
            if (samePath(worlds.get(index).worldPath(), worldPath)) {
                worlds.set(index, refreshed);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            worlds.add(refreshed);
        }
        worlds.sort(java.util.Comparator.comparing(
                WorldContentIndex.WorldEntry::worldId,
                String.CASE_INSENSITIVE_ORDER
        ));
        worldIndex = new WorldContentIndex(
                WorldContentIndex.CURRENT_SCHEMA_VERSION,
                Instant.now().toString(),
                worldIndex.savesRoot(),
                worlds,
                worldIndex.validation()
        );
        try {
            indexStore.writeWorldIndex(worldIndex);
        } catch (IOException exception) {
            SUBLOGGER.logE(
                    "Content Registry",
                    "Failed to persist the selected-world Content refresh: " + exception.getMessage()
            );
        }
        return refreshed;
    }

    public Map<String, WorldContentIndex.DefinitionEntry> activeDefinitions() {
        return runtimeStore.definitions();
    }

    public Map<String, ContentDefinition> activeContentDefinitions() {
        return runtimeStore.typedDefinitions();
    }

    public WorldContentIndex.ActiveWorldSnapshot activeWorldSnapshot() {
        return runtimeStore.snapshot();
    }

    public ContentReloadResult lastReloadResult() {
        return lastReloadResult;
    }

    public synchronized java.util.List<ContentReloadResult> reloadHistory() {
        return java.util.List.copyOf(reloadHistory);
    }

    public Path contentReportDirectory() {
        return indexStore.reportDirectory();
    }

    private synchronized void activateServerWorld(MinecraftServer server, String reason) {
        WorldContentIndex.ActiveWorldSnapshot previousSnapshot = runtimeStore.snapshot();
        Map<String, WorldContentIndex.DefinitionEntry> previousDefinitions =
                Map.copyOf(runtimeStore.definitions());
        Map<String, ContentDefinition> previousTypedDefinitions =
                Map.copyOf(runtimeStore.typedDefinitions());
        ContentRegistryEvents.beforeReload(reason, previousSnapshot);
        WorldContentIndex refreshed = scanWorlds();
        Path activePath = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        WorldContentIndex.WorldEntry activeWorld = refreshed.worlds().stream()
                .filter(world -> samePath(world.worldPath(), activePath))
                .findFirst()
                .orElseGet(() -> scanner.scanWorld(activePath));
        ContentRegistryEvents.beforeActivate(activeWorld);
        Collection<String> enabledPacks = server.getDataPackManager().getEnabledNames();
        WorldContentIndex.ActiveWorldSnapshot snapshot = runtimeStore.activate(activeWorld, enabledPacks);
        ContentExtensionRegistry.synchronize(previousTypedDefinitions, runtimeStore.typedDefinitions());
        List<ContentDefinitionChange> changes =
                ContentDefinitionDiffer.diff(previousDefinitions, runtimeStore.definitions());
        List<WorldContentIndex.ValidationMessage> validation = collectValidation(activeWorld, snapshot);
        ContentReloadResult result = new ContentReloadResult(
                Instant.now().toString(),
                reason,
                snapshot.worldId(),
                snapshot.worldPath(),
                true,
                snapshot.definitions().size(),
                changes,
                validation
        );
        writeActiveSnapshot(snapshot);
        recordReload(result);
        ContentRegistryEvents.afterActivate(snapshot);
        ContentRegistryEvents.afterReload(result);
        ContentActivationEvents.publish(snapshot);
        SUBLOGGER.logI(
                "Content Registry",
                "Activated " + snapshot.definitions().size() + " definitions for world " + activeWorld.worldId()
                        + " from " + snapshot.enabledPackIds().size() + " enabled world datapacks."
        );
    }

    private synchronized void deactivateWorld() {
        WorldContentIndex.ActiveWorldSnapshot previousSnapshot = runtimeStore.snapshot();
        Map<String, WorldContentIndex.DefinitionEntry> previousDefinitions =
                Map.copyOf(runtimeStore.definitions());
        Map<String, ContentDefinition> previousTypedDefinitions =
                Map.copyOf(runtimeStore.typedDefinitions());
        ContentRegistryEvents.beforeReload("world_stop", previousSnapshot);
        ContentRegistryEvents.beforeDeactivate(previousSnapshot);
        WorldContentIndex.ActiveWorldSnapshot snapshot = runtimeStore.clear();
        ContentExtensionRegistry.synchronize(previousTypedDefinitions, runtimeStore.typedDefinitions());
        ContentReloadResult result = new ContentReloadResult(
                Instant.now().toString(),
                "world_stop",
                previousSnapshot.worldId(),
                previousSnapshot.worldPath(),
                true,
                0,
                ContentDefinitionDiffer.diff(previousDefinitions, Map.of()),
                List.of()
        );
        writeActiveSnapshot(snapshot);
        recordReload(result);
        ContentRegistryEvents.afterDeactivate(snapshot);
        ContentRegistryEvents.afterReload(result);
        ContentActivationEvents.publish(snapshot);
        SUBLOGGER.logI("Content Registry", "Deactivated world-scoped Content definitions.");
    }

    private void writeActiveSnapshot(WorldContentIndex.ActiveWorldSnapshot snapshot) {
        try {
            indexStore.writeActiveWorld(snapshot);
        } catch (IOException exception) {
            SUBLOGGER.logE("Content Registry", "Failed to write active world registry snapshot: " + exception.getMessage());
        }
    }

    private synchronized void recordFailedReload(String reason, String message) {
        WorldContentIndex.ActiveWorldSnapshot snapshot = runtimeStore.snapshot();
        ContentReloadResult result = new ContentReloadResult(
                Instant.now().toString(),
                reason,
                snapshot.worldId(),
                snapshot.worldPath(),
                false,
                snapshot.definitions().size(),
                List.of(),
                List.of(WorldContentIndex.ValidationMessage.error(
                        "content_reload_failed",
                        message,
                        snapshot.worldPath(),
                        "Correct the datapack error and reload again; the last valid Content remains active."
                ))
        );
        recordReload(result);
        ContentRegistryEvents.afterReload(result);
    }

    private synchronized void recordReload(ContentReloadResult result) {
        lastReloadResult = result;
        reloadHistory.add(result);
        if (reloadHistory.size() > 100) {
            reloadHistory.remove(0);
        }
        try {
            indexStore.writeReloadHistory(reloadHistory);
            indexStore.writeValidationResults(result.validation());
            indexStore.writeReloadReport(result);
        } catch (IOException exception) {
            SUBLOGGER.logE("Content Registry", "Failed to write Content reload reports: " + exception.getMessage());
        }
    }

    private static List<WorldContentIndex.ValidationMessage> collectValidation(
            WorldContentIndex.WorldEntry world,
            WorldContentIndex.ActiveWorldSnapshot snapshot
    ) {
        LinkedHashSet<WorldContentIndex.ValidationMessage> validation = new LinkedHashSet<>();
        validation.addAll(world.validation());
        for (WorldContentIndex.PackEntry pack : world.packs()) {
            validation.addAll(pack.validation());
            for (WorldContentIndex.DefinitionEntry definition : pack.definitions()) {
                validation.addAll(definition.validation());
            }
        }
        validation.addAll(snapshot.validation());
        return List.copyOf(validation);
    }

    private static boolean samePath(String indexedPath, Path activePath) {
        try {
            return Path.of(indexedPath).toAbsolutePath().normalize().equals(activePath);
        } catch (Exception ignored) {
            return false;
        }
    }
}
