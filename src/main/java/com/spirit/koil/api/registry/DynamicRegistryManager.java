package com.spirit.koil.api.registry;

import com.spirit.koil.api.registry.definition.ContentDefinition;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
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
    private volatile WorldContentIndex worldIndex;

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
        ServerLifecycleEvents.SERVER_STARTED.register(this::activateServerWorld);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success) {
                activateServerWorld(server);
            } else {
                SUBLOGGER.logW("Content Registry", "Datapack reload failed; retaining the last valid active Content snapshot.");
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> deactivateWorld());
    }

    public synchronized WorldContentIndex scanWorlds() {
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
        return worldIndex;
    }

    public WorldContentIndex worldIndex() {
        return worldIndex;
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

    private synchronized void activateServerWorld(MinecraftServer server) {
        WorldContentIndex refreshed = scanWorlds();
        Path activePath = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        WorldContentIndex.WorldEntry activeWorld = refreshed.worlds().stream()
                .filter(world -> samePath(world.worldPath(), activePath))
                .findFirst()
                .orElseGet(() -> scanner.scanWorld(activePath));
        Collection<String> enabledPacks = server.getDataPackManager().getEnabledNames();
        WorldContentIndex.ActiveWorldSnapshot snapshot = runtimeStore.activate(activeWorld, enabledPacks);
        writeActiveSnapshot(snapshot);
        ContentActivationEvents.publish(snapshot);
        SUBLOGGER.logI(
                "Content Registry",
                "Activated " + snapshot.definitions().size() + " definitions for world " + activeWorld.worldId()
                        + " from " + snapshot.enabledPackIds().size() + " enabled world datapacks."
        );
    }

    private synchronized void deactivateWorld() {
        WorldContentIndex.ActiveWorldSnapshot snapshot = runtimeStore.clear();
        writeActiveSnapshot(snapshot);
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

    private static boolean samePath(String indexedPath, Path activePath) {
        try {
            return Path.of(indexedPath).toAbsolutePath().normalize().equals(activePath);
        } catch (Exception ignored) {
            return false;
        }
    }
}
