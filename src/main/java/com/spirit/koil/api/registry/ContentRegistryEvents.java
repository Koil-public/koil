package com.spirit.koil.api.registry;

import com.spirit.koil.api.registry.definition.ContentDefinition;

import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static com.spirit.Main.SUBLOGGER;

/** Fault-isolated publisher for the complete Content registry lifecycle API. */
public final class ContentRegistryEvents {
    private static final CopyOnWriteArrayList<ContentRegistryEventListener> LISTENERS =
            new CopyOnWriteArrayList<>();

    private ContentRegistryEvents() {
    }

    public static void register(ContentRegistryEventListener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void unregister(ContentRegistryEventListener listener) {
        LISTENERS.remove(listener);
    }

    static void beforeScan(Path savesRoot) {
        publish(listener -> listener.beforeWorldContentScan(savesRoot));
    }

    static void afterScan(WorldContentIndex index) {
        publish(listener -> listener.afterWorldContentScan(index));
    }

    static void beforeParse(String sourcePath) {
        publish(listener -> listener.beforeDefinitionParse(sourcePath));
    }

    static void afterParse(ContentDefinition definition) {
        publish(listener -> listener.afterDefinitionParse(definition));
    }

    static void beforeActivate(WorldContentIndex.WorldEntry world) {
        publish(listener -> listener.beforeWorldContentActivate(world));
    }

    static void afterActivate(WorldContentIndex.ActiveWorldSnapshot snapshot) {
        publish(listener -> listener.afterWorldContentActivate(snapshot));
    }

    static void beforeDeactivate(WorldContentIndex.ActiveWorldSnapshot snapshot) {
        publish(listener -> listener.beforeWorldContentDeactivate(snapshot));
    }

    static void afterDeactivate(WorldContentIndex.ActiveWorldSnapshot snapshot) {
        publish(listener -> listener.afterWorldContentDeactivate(snapshot));
    }

    static void beforeReload(String reason, WorldContentIndex.ActiveWorldSnapshot previous) {
        publish(listener -> listener.beforeContentReload(reason, previous));
    }

    static void afterReload(ContentReloadResult result) {
        publish(listener -> listener.afterContentReload(result));
        for (ContentDefinitionChange change : result.changes()) {
            switch (change.kind()) {
                case ADDED -> publish(listener -> listener.onDefinitionAdded(change));
                case REMOVED -> publish(listener -> listener.onDefinitionRemoved(change));
                case EDITED -> publish(listener -> listener.onDefinitionEdited(change));
            }
            if (change.classification()
                    == com.spirit.koil.api.registry.definition.ReloadClassification.RESTART_REQUIRED) {
                publish(listener -> listener.onRestartRequiredChange(change));
            }
        }
        for (WorldContentIndex.ValidationMessage validation : result.validation()) {
            if (validation.blocksActivation()) {
                publish(listener -> listener.onDefinitionValidationFailed(validation));
            }
        }
    }

    static void legacyModId(String sourcePath) {
        publish(listener -> listener.onLegacyModIdDetected(sourcePath));
    }

    private static void publish(Consumer<ContentRegistryEventListener> invocation) {
        for (ContentRegistryEventListener listener : LISTENERS) {
            try {
                invocation.accept(listener);
            } catch (RuntimeException exception) {
                SUBLOGGER.logE(
                        "Content Registry",
                        "Content registry event listener failed without blocking runtime state: "
                                + exception.getMessage()
                );
            }
        }
    }
}
