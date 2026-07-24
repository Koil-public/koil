package com.spirit.koil.api.registry;

import com.google.gson.JsonElement;
import com.spirit.koil.api.registry.definition.ContentDefinition;

import java.util.List;

/** Mod/API hook for one namespaced Content extension section. */
public interface ContentExtensionHandler {
    /** Exact namespaced extension id, for example {@code examplemod:machine}. */
    String extensionId();

    default List<WorldContentIndex.ValidationMessage> validate(
            ContentDefinition definition,
            JsonElement extensionData
    ) {
        return List.of();
    }

    default void onDefinitionActivated(ContentDefinition definition, JsonElement extensionData) {
    }

    default void onDefinitionDeactivated(ContentDefinition definition, JsonElement extensionData) {
    }
}
