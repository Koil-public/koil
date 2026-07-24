package com.spirit.koil.api.registry;

import com.spirit.koil.api.registry.definition.ContentDefinition;

import java.nio.file.Path;

/** API-first lifecycle surface for Content tools, integrations, and mods. */
public interface ContentRegistryEventListener {
    default void beforeWorldContentScan(Path savesRoot) {
    }

    default void afterWorldContentScan(WorldContentIndex index) {
    }

    default void beforeDefinitionParse(String sourcePath) {
    }

    default void afterDefinitionParse(ContentDefinition definition) {
    }

    default void beforeWorldContentActivate(WorldContentIndex.WorldEntry world) {
    }

    default void afterWorldContentActivate(WorldContentIndex.ActiveWorldSnapshot snapshot) {
    }

    default void beforeWorldContentDeactivate(WorldContentIndex.ActiveWorldSnapshot snapshot) {
    }

    default void afterWorldContentDeactivate(WorldContentIndex.ActiveWorldSnapshot snapshot) {
    }

    default void beforeContentReload(String reason, WorldContentIndex.ActiveWorldSnapshot previous) {
    }

    default void afterContentReload(ContentReloadResult result) {
    }

    default void onDefinitionAdded(ContentDefinitionChange change) {
    }

    default void onDefinitionRemoved(ContentDefinitionChange change) {
    }

    default void onDefinitionEdited(ContentDefinitionChange change) {
    }

    default void onDefinitionValidationFailed(WorldContentIndex.ValidationMessage validation) {
    }

    default void onLegacyModIdDetected(String sourcePath) {
    }

    default void onRestartRequiredChange(ContentDefinitionChange change) {
    }
}
