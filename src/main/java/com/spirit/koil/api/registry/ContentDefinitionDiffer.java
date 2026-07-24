package com.spirit.koil.api.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.registry.definition.ContentDefinition;
import com.spirit.koil.api.registry.definition.ContentVersionAdapters;
import com.spirit.koil.api.registry.definition.DefinitionParser;
import com.spirit.koil.api.registry.definition.ReloadClassification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic structural diff and reload-safety classification for definitions. */
public final class ContentDefinitionDiffer {
    private ContentDefinitionDiffer() {
    }

    public static List<ContentDefinitionChange> diff(
            Map<String, WorldContentIndex.DefinitionEntry> before,
            Map<String, WorldContentIndex.DefinitionEntry> after
    ) {
        TreeSet<String> ids = new TreeSet<>();
        ids.addAll(before.keySet());
        ids.addAll(after.keySet());
        List<ContentDefinitionChange> changes = new ArrayList<>();
        for (String id : ids) {
            WorldContentIndex.DefinitionEntry oldDefinition = before.get(id);
            WorldContentIndex.DefinitionEntry newDefinition = after.get(id);
            if (oldDefinition == null) {
                ReloadClassification classification = additionClassification(newDefinition);
                changes.add(new ContentDefinitionChange(
                        id,
                        newDefinition.type(),
                        ContentChangeKind.ADDED,
                        classification,
                        List.of("/"),
                        classification == ReloadClassification.RESTART_REQUIRED
                                ? "A new physical id was discovered after startup and requires restart."
                                : "Definition activated using an existing startup holder."
                ));
            } else if (newDefinition == null) {
                changes.add(new ContentDefinitionChange(
                        id,
                        oldDefinition.type(),
                        ContentChangeKind.REMOVED,
                        ReloadClassification.HOT_RELOADABLE,
                        List.of("/"),
                        "Definition deactivated; its stable holder remains hidden for save safety."
                ));
            } else {
                Set<String> changedPaths = changedPaths(
                        oldDefinition.definition(),
                        newDefinition.definition()
                );
                if (changedPaths.isEmpty()) {
                    continue;
                }
                ContentDefinition parsed = DefinitionParser.parse(newDefinition);
                ReloadClassification classification = supportsRuntimeType(newDefinition.type())
                        ? ContentVersionAdapters.current().classifyChangedPaths(parsed, changedPaths)
                        : ReloadClassification.UNSUPPORTED;
                changes.add(new ContentDefinitionChange(
                        id,
                        newDefinition.type(),
                        ContentChangeKind.EDITED,
                        classification,
                        changedPaths.stream().sorted().toList(),
                        switch (classification) {
                            case HOT_RELOADABLE -> "Reloadable fields changed and were activated.";
                            case RESTART_REQUIRED -> "Registry-shape fields changed; runtime-safe fields activated and physical shape remains startup-stable.";
                            case UNSUPPORTED -> "This Content type has no physical runtime adapter yet.";
                        }
                ));
            }
        }
        return List.copyOf(changes);
    }

    public static Set<String> changedPaths(JsonElement before, JsonElement after) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        collect(before, after, "", paths);
        return Set.copyOf(paths);
    }

    private static void collect(
            JsonElement before,
            JsonElement after,
            String path,
            Set<String> paths
    ) {
        if (java.util.Objects.equals(before, after)) {
            return;
        }
        if (before != null && after != null && before.isJsonObject() && after.isJsonObject()) {
            JsonObject left = before.getAsJsonObject();
            JsonObject right = after.getAsJsonObject();
            TreeSet<String> keys = new TreeSet<>();
            keys.addAll(left.keySet());
            keys.addAll(right.keySet());
            for (String key : keys) {
                collect(left.get(key), right.get(key), path + "/" + escape(key), paths);
            }
            return;
        }
        if (before != null && after != null && before.isJsonArray() && after.isJsonArray()) {
            // Arrays represent ordered definitions (lore, properties, recipes,
            // multipart). Classify the owning path atomically to avoid unstable
            // index-based reports after insertion/removal.
            paths.add(path.isBlank() ? "/" : path);
            return;
        }
        paths.add(path.isBlank() ? "/" : path);
    }

    private static ReloadClassification additionClassification(
            WorldContentIndex.DefinitionEntry definition
    ) {
        if (!supportsRuntimeType(definition.type())) {
            return ReloadClassification.UNSUPPORTED;
        }
        return DynamicContentHolderRegistry.hasHolder(definition.id())
                ? ReloadClassification.HOT_RELOADABLE
                : ReloadClassification.RESTART_REQUIRED;
    }

    private static boolean supportsRuntimeType(String type) {
        return "item".equals(type) || "block".equals(type);
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }
}
