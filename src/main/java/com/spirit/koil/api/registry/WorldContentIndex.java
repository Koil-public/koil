package com.spirit.koil.api.registry;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * Immutable, generated view of Content definitions discovered in world datapacks.
 * Source datapacks remain authoritative; this model is safe to cache and rebuild.
 */
public record WorldContentIndex(
        int schemaVersion,
        String scannedAt,
        String savesRoot,
        List<WorldEntry> worlds,
        List<ValidationMessage> validation
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public WorldContentIndex {
        worlds = List.copyOf(worlds);
        validation = List.copyOf(validation);
    }

    public int definitionCount() {
        return worlds.stream().mapToInt(WorldEntry::definitionCount).sum();
    }

    public record WorldEntry(
            String worldId,
            String displayName,
            String worldPath,
            String minecraftVersion,
            int dataVersion,
            long lastPlayed,
            List<PackEntry> packs,
            List<ValidationMessage> validation
    ) {
        public WorldEntry {
            packs = List.copyOf(packs);
            validation = List.copyOf(validation);
        }

        public int definitionCount() {
            return packs.stream().mapToInt(pack -> pack.definitions().size()).sum();
        }
    }

    public record PackEntry(
            String packId,
            String fileName,
            String sourcePath,
            boolean packed,
            String state,
            String displayName,
            String description,
            int packFormat,
            String contentVersion,
            String minecraftRequirement,
            List<String> namespaces,
            List<String> dependencies,
            List<String> requiredMods,
            JsonObject metadata,
            List<DefinitionEntry> definitions,
            List<ValidationMessage> validation
    ) {
        public PackEntry {
            namespaces = List.copyOf(namespaces);
            dependencies = List.copyOf(dependencies);
            requiredMods = List.copyOf(requiredMods);
            definitions = List.copyOf(definitions);
            validation = List.copyOf(validation);
            metadata = metadata == null ? new JsonObject() : metadata.deepCopy();
        }
    }

    public record DefinitionEntry(
            String id,
            String localId,
            String namespace,
            String type,
            String sourcePath,
            String packId,
            JsonObject definition,
            boolean activatable,
            List<ValidationMessage> validation
    ) {
        public DefinitionEntry {
            definition = definition == null ? new JsonObject() : definition.deepCopy();
            validation = List.copyOf(validation);
        }
    }

    public record ValidationMessage(
            String severity,
            String code,
            String message,
            String sourcePath,
            boolean blocksActivation,
            String suggestion
    ) {
        public static ValidationMessage warning(String code, String message, String sourcePath, String suggestion) {
            return new ValidationMessage("warning", code, message, sourcePath, false, suggestion);
        }

        public static ValidationMessage error(String code, String message, String sourcePath, String suggestion) {
            return new ValidationMessage("error", code, message, sourcePath, true, suggestion);
        }
    }

    public record ActiveWorldSnapshot(
            int schemaVersion,
            String updatedAt,
            String worldId,
            String worldPath,
            List<String> enabledPackIds,
            List<DefinitionEntry> definitions,
            List<ValidationMessage> validation
    ) {
        public ActiveWorldSnapshot {
            enabledPackIds = List.copyOf(enabledPackIds);
            definitions = List.copyOf(definitions);
            validation = List.copyOf(validation);
        }

        public static ActiveWorldSnapshot empty(String updatedAt) {
            return new ActiveWorldSnapshot(CURRENT_SCHEMA_VERSION, updatedAt, "", "", List.of(), List.of(), List.of());
        }
    }
}
