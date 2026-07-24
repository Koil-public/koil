package com.spirit.koil.api.registry.definition;

import com.spirit.koil.api.registry.WorldContentIndex;

public final class BlockDefinition extends BaseContentDefinition {
    private final DynamicBlockStateDefinition blockStates;

    public BlockDefinition(WorldContentIndex.DefinitionEntry source) {
        super(source);
        blockStates = DynamicBlockStateDefinition.parse(this);
    }

    public DynamicBlockStateDefinition blockStates() {
        return blockStates;
    }
}
