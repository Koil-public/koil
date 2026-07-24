package com.spirit.koil.api.registry;

import java.util.List;

/** Immutable reload/activation result used by commands, reports, UI, and mods. */
public record ContentReloadResult(
        String updatedAt,
        String reason,
        String worldId,
        String worldPath,
        boolean successful,
        int activeDefinitionCount,
        List<ContentDefinitionChange> changes,
        List<WorldContentIndex.ValidationMessage> validation
) {
    public ContentReloadResult {
        updatedAt = updatedAt == null ? "" : updatedAt;
        reason = reason == null ? "" : reason;
        worldId = worldId == null ? "" : worldId;
        worldPath = worldPath == null ? "" : worldPath;
        changes = changes == null ? List.of() : List.copyOf(changes);
        validation = validation == null ? List.of() : List.copyOf(validation);
    }

    public long hotReloadableCount() {
        return changes.stream()
                .filter(change -> change.classification()
                        == com.spirit.koil.api.registry.definition.ReloadClassification.HOT_RELOADABLE)
                .count();
    }

    public long restartRequiredCount() {
        return changes.stream()
                .filter(change -> change.classification()
                        == com.spirit.koil.api.registry.definition.ReloadClassification.RESTART_REQUIRED)
                .count();
    }
}
