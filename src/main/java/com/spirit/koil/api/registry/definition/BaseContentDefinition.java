package com.spirit.koil.api.registry.definition;

import com.spirit.koil.api.registry.WorldContentIndex;

abstract class BaseContentDefinition implements ContentDefinition {
    private final WorldContentIndex.DefinitionEntry source;
    private final DefinitionSections sections;

    BaseContentDefinition(WorldContentIndex.DefinitionEntry source) {
        this.source = source;
        sections = new DefinitionSections(source.definition());
    }

    @Override
    public final WorldContentIndex.DefinitionEntry source() {
        return source;
    }

    @Override
    public final DefinitionSections sections() {
        return sections;
    }
}
