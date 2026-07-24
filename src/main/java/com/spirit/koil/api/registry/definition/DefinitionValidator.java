package com.spirit.koil.api.registry.definition;

import com.google.gson.JsonElement;
import com.spirit.koil.api.registry.ContentExtensionRegistry;
import com.spirit.koil.api.registry.WorldContentIndex;

import java.util.ArrayList;
import java.util.List;

/** Typed, extension-preserving validation shared by scanners, editors, and mods. */
public final class DefinitionValidator {
    private DefinitionValidator() {
    }

    public static List<WorldContentIndex.ValidationMessage> validate(
            ContentDefinition definition,
            ContentVersionAdapter adapter
    ) {
        List<WorldContentIndex.ValidationMessage> messages = new ArrayList<>();
        validateObjectSection(definition, "display", messages);
        validateObjectSection(definition, "behavior", messages);
        validateObjectSection(definition, "properties", messages);
        validateObjectSection(definition, "creative", messages);
        validateObjectSection(definition, "assets", messages);
        validateObjectSection(definition, "compatibility", messages);
        validateObjectSection(definition, "metadata", messages);
        validateObjectSection(definition, "extensions", messages);
        validateObjectSection(definition, "blockstates", messages);
        validateArraySection(definition, "tags", messages);
        validateArraySection(definition, "recipes", messages);

        for (String unknown : definition.sections().unknownRootFieldNames()) {
            messages.add(WorldContentIndex.ValidationMessage.warning(
                    unknown.contains(":") ? "unknown_extension_field" : "unknown_field",
                    "Unknown field \"" + unknown + "\" was preserved.",
                    definition.sourcePath(),
                    unknown.contains(":")
                            ? "Install an extension handler if this namespaced field needs runtime behavior."
                            : "Check the field name or keep it as forward-compatible metadata."
            ));
        }
        if (definition instanceof BlockDefinition blockDefinition) {
            messages.addAll(DynamicBlockStateValidator.validate(definition, blockDefinition.blockStates()));
        }
        messages.addAll(ContentExtensionRegistry.validate(definition));
        messages.addAll(adapter.validate(definition));
        return List.copyOf(messages);
    }

    private static void validateObjectSection(
            ContentDefinition definition,
            String key,
            List<WorldContentIndex.ValidationMessage> messages
    ) {
        JsonElement value = definition.source().definition().get(key);
        if (value != null && !value.isJsonObject()) {
            messages.add(typeError(definition, key, "object"));
        }
    }

    private static void validateArraySection(
            ContentDefinition definition,
            String key,
            List<WorldContentIndex.ValidationMessage> messages
    ) {
        JsonElement value = definition.source().definition().get(key);
        if (value != null && !value.isJsonArray()) {
            messages.add(typeError(definition, key, "array"));
        }
    }

    private static WorldContentIndex.ValidationMessage typeError(
            ContentDefinition definition,
            String key,
            String expected
    ) {
        return WorldContentIndex.ValidationMessage.error(
                "invalid_" + key + "_type",
                "\"" + key + "\" must be a JSON " + expected + ".",
                definition.sourcePath(),
                "Change \"" + key + "\" to a JSON " + expected + " without deleting its custom fields."
        );
    }
}
