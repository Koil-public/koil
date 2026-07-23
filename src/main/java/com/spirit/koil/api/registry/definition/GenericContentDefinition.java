package com.spirit.koil.api.registry.definition;

import com.spirit.koil.api.registry.WorldContentIndex;

/** Preserving fallback for future or mod-provided definition types. */
public final class GenericContentDefinition extends BaseContentDefinition {
    public GenericContentDefinition(WorldContentIndex.DefinitionEntry source) {
        super(source);
    }
}
