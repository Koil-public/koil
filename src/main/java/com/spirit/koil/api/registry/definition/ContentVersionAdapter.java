package com.spirit.koil.api.registry.definition;

import com.spirit.koil.api.registry.WorldContentIndex;

import java.util.List;
import java.util.Set;

/** Isolates Minecraft-version validation and registry/reload rules from definitions. */
public interface ContentVersionAdapter {
    String minecraftVersion();

    Set<String> supportedRootFields();

    List<WorldContentIndex.ValidationMessage> validate(ContentDefinition definition);

    ReloadClassification classifyChangedPaths(ContentDefinition definition, Set<String> changedPaths);
}
