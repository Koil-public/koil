package com.spirit.koil.api.registry.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.registry.WorldContentIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Minecraft 1.20.1 validation and reload boundary for the first physical-holder slice. */
public final class VersionAdapter_1_20_1 implements ContentVersionAdapter {
    private static final Set<String> SUPPORTED_ROOT_FIELDS = Set.of(
            "schema_version", "id", "type", "namespace",
            "display", "behavior", "properties", "components", "tags",
            "creative", "assets", "model", "texture", "loot", "recipes",
            "compatibility", "version", "metadata", "extensions", "blockstates"
    );
    private static final Set<String> STARTUP_PATHS = Set.of(
            "/type",
            "/behavior/profile",
            "/properties/max_count",
            "/properties/durability",
            "/properties/rarity",
            "/properties/fireproof",
            "/properties/recipe_remainder",
            "/properties/hardness",
            "/properties/resistance",
            "/properties/collision",
            "/properties/opaque",
            "/properties/solid",
            "/properties/map_color",
            "/properties/sound_group",
            "/properties/instrument",
            "/properties/piston_behavior",
            "/blockstates"
    );

    @Override
    public String minecraftVersion() {
        return "1.20.1";
    }

    @Override
    public Set<String> supportedRootFields() {
        return SUPPORTED_ROOT_FIELDS;
    }

    @Override
    public List<WorldContentIndex.ValidationMessage> validate(ContentDefinition definition) {
        List<WorldContentIndex.ValidationMessage> messages = new ArrayList<>();
        JsonObject properties = definition.sections().properties();
        validateInteger(properties, "max_count", 1, 64, definition, messages);
        validateInteger(properties, "durability", 0, Integer.MAX_VALUE, definition, messages);
        validateNumberAtLeast(properties, "hardness", 0.0, definition, messages);
        validateNumberAtLeast(properties, "resistance", 0.0, definition, messages);
        validateInteger(properties, "luminance", 0, 15, definition, messages);
        return List.copyOf(messages);
    }

    @Override
    public ReloadClassification classifyChangedPaths(
            ContentDefinition definition,
            Set<String> changedPaths
    ) {
        for (String path : changedPaths) {
            if (STARTUP_PATHS.stream().anyMatch(path::startsWith)) {
                return ReloadClassification.RESTART_REQUIRED;
            }
        }
        return ReloadClassification.HOT_RELOADABLE;
    }

    private static void validateInteger(
            JsonObject object,
            String key,
            int minimum,
            int maximum,
            ContentDefinition definition,
            List<WorldContentIndex.ValidationMessage> messages
    ) {
        JsonElement value = object.get(key);
        if (value == null) {
            return;
        }
        try {
            int parsed = value.getAsInt();
            if (parsed < minimum || parsed > maximum) {
                messages.add(error(
                        "invalid_" + key,
                        key + " must be between " + minimum + " and " + maximum + ".",
                        definition
                ));
            }
        } catch (RuntimeException exception) {
            messages.add(error("invalid_" + key, key + " must be an integer.", definition));
        }
    }

    private static void validateNumberAtLeast(
            JsonObject object,
            String key,
            double minimum,
            ContentDefinition definition,
            List<WorldContentIndex.ValidationMessage> messages
    ) {
        JsonElement value = object.get(key);
        if (value == null) {
            return;
        }
        try {
            if (value.getAsDouble() < minimum) {
                messages.add(error("invalid_" + key, key + " must be at least " + minimum + ".", definition));
            }
        } catch (RuntimeException exception) {
            messages.add(error("invalid_" + key, key + " must be numeric.", definition));
        }
    }

    private static WorldContentIndex.ValidationMessage error(
            String code,
            String message,
            ContentDefinition definition
    ) {
        return WorldContentIndex.ValidationMessage.error(
                code,
                message,
                definition.sourcePath(),
                "Update the field for Minecraft 1.20.1 or remove it to use the version default."
        );
    }
}
