package com.spirit.koil.api.model.catalog;

import com.spirit.koil.api.util.file.KoilInstancePaths;

import java.nio.file.Path;

/** Optional specialized selection, intentionally separate from the chat-generation selection. */
public final class EmbeddingModelSelectionStore {
    private EmbeddingModelSelectionStore() {
    }

    public static LocalModelSelection load() {
        return LocalModelSelectionStore.load(defaultPath());
    }

    public static void save(LocalModelSelection selection) {
        LocalModelSelectionStore.save(defaultPath(), selection);
    }

    public static void clear() {
        LocalModelSelectionStore.clear(defaultPath());
    }

    public static Path defaultPath() {
        return KoilInstancePaths.modelRoot().resolve("embedding-selection.json");
    }
}
