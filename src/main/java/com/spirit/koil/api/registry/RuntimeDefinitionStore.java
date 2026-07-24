package com.spirit.koil.api.registry;

import com.spirit.koil.api.registry.definition.ContentDefinition;
import com.spirit.koil.api.registry.definition.DefinitionParser;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RuntimeDefinitionStore {
    private volatile Map<String, WorldContentIndex.DefinitionEntry> activeDefinitions = Map.of();
    private volatile Map<String, ContentDefinition> activeTypedDefinitions = Map.of();
    private volatile WorldContentIndex.ActiveWorldSnapshot snapshot = WorldContentIndex.ActiveWorldSnapshot.empty(Instant.now().toString());

    synchronized WorldContentIndex.ActiveWorldSnapshot activate(
            WorldContentIndex.WorldEntry world,
            Collection<String> liveEnabledPackNames
    ) {
        Map<String, WorldContentIndex.DefinitionEntry> previous =
                snapshot.worldPath().equals(world.worldPath()) ? activeDefinitions : Map.of();
        Set<String> enabledNames = new LinkedHashSet<>(liveEnabledPackNames);
        Map<String, List<WorldContentIndex.DefinitionEntry>> candidates = new LinkedHashMap<>();
        Map<String, List<WorldContentIndex.DefinitionEntry>> invalidCandidates = new LinkedHashMap<>();
        List<String> activePackIds = new ArrayList<>();
        List<WorldContentIndex.ValidationMessage> validation = new ArrayList<>();

        for (WorldContentIndex.PackEntry pack : world.packs()) {
            if (!isEnabled(pack, enabledNames)) {
                continue;
            }
            activePackIds.add(pack.packId());
            for (WorldContentIndex.DefinitionEntry definition : pack.definitions()) {
                if (definition.activatable()) {
                    candidates.computeIfAbsent(definition.id(), ignored -> new ArrayList<>()).add(definition);
                } else {
                    invalidCandidates.computeIfAbsent(definition.id(), ignored -> new ArrayList<>()).add(definition);
                    validation.addAll(definition.validation());
                }
            }
        }

        Map<String, WorldContentIndex.DefinitionEntry> activated = new LinkedHashMap<>();
        for (Map.Entry<String, List<WorldContentIndex.DefinitionEntry>> candidate : candidates.entrySet()) {
            if (candidate.getValue().size() == 1) {
                activated.put(candidate.getKey(), candidate.getValue().get(0));
            } else {
                validation.add(WorldContentIndex.ValidationMessage.error(
                        "duplicate_active_id",
                        "Multiple enabled datapacks define " + candidate.getKey() + "; activation was blocked for that id.",
                        world.worldPath(),
                        "Disable one conflicting pack or give the definitions unique ids."
                ));
            }
        }
        for (Map.Entry<String, List<WorldContentIndex.DefinitionEntry>> invalid : invalidCandidates.entrySet()) {
            if (activated.containsKey(invalid.getKey())
                    || invalid.getValue().size() != 1
                    || candidates.containsKey(invalid.getKey())) {
                continue;
            }
            WorldContentIndex.DefinitionEntry oldDefinition = previous.get(invalid.getKey());
            WorldContentIndex.DefinitionEntry brokenDefinition = invalid.getValue().get(0);
            if (oldDefinition != null && oldDefinition.packId().equals(brokenDefinition.packId())) {
                activated.put(invalid.getKey(), oldDefinition);
                validation.add(WorldContentIndex.ValidationMessage.warning(
                        "retained_last_valid_definition",
                        "The edited definition for " + invalid.getKey()
                                + " is invalid; Koil retained the last valid active version.",
                        brokenDefinition.sourcePath(),
                        "Fix the reported validation errors and reload again."
                ));
            }
        }

        activeDefinitions = Map.copyOf(activated);
        Map<String, ContentDefinition> typed = new LinkedHashMap<>();
        activated.forEach((id, definition) -> typed.put(id, DefinitionParser.parse(definition)));
        activeTypedDefinitions = Map.copyOf(typed);
        snapshot = new WorldContentIndex.ActiveWorldSnapshot(
                WorldContentIndex.CURRENT_SCHEMA_VERSION,
                Instant.now().toString(),
                world.worldId(),
                world.worldPath(),
                activePackIds,
                new ArrayList<>(activated.values()),
                validation
        );
        return snapshot;
    }

    synchronized WorldContentIndex.ActiveWorldSnapshot clear() {
        activeDefinitions = Map.of();
        activeTypedDefinitions = Map.of();
        snapshot = WorldContentIndex.ActiveWorldSnapshot.empty(Instant.now().toString());
        return snapshot;
    }

    Map<String, WorldContentIndex.DefinitionEntry> definitions() {
        return activeDefinitions;
    }

    Map<String, ContentDefinition> typedDefinitions() {
        return activeTypedDefinitions;
    }

    WorldContentIndex.ActiveWorldSnapshot snapshot() {
        return snapshot;
    }

    private static boolean isEnabled(WorldContentIndex.PackEntry pack, Set<String> liveEnabledPackNames) {
        if (!liveEnabledPackNames.isEmpty()) {
            return liveEnabledPackNames.contains(pack.packId())
                    || liveEnabledPackNames.contains(pack.fileName())
                    || liveEnabledPackNames.contains("file/" + pack.fileName());
        }
        return "enabled".equals(pack.state());
    }
}
