package com.spirit.koil.api.registry;

import com.spirit.koil.api.registry.definition.DynamicStatePropertyDefinition;

import java.util.List;
import java.util.Optional;

/** API hook allowing a mod to materialize a custom blockstate property type. */
public interface DynamicStatePropertyHandler {
    /** Custom JSON type id, preferably namespaced, such as {@code examplemod:phase}. */
    String typeId();

    default List<WorldContentIndex.ValidationMessage> validate(
            DynamicStatePropertyDefinition definition,
            String sourcePath
    ) {
        return List.of();
    }

    Optional<MaterializedStateProperty<?>> materialize(DynamicStatePropertyDefinition definition);
}
