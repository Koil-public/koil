package com.spirit.koil.api.registry.definition;

import com.google.gson.JsonObject;
import com.spirit.koil.api.registry.WorldContentIndex;

/**
 * Stable data-driven Content definition contract. Implementations preserve the
 * complete source document, including fields Koil or this Minecraft version does not know.
 */
public interface ContentDefinition {
    WorldContentIndex.DefinitionEntry source();

    DefinitionSections sections();

    default String id() {
        return source().id();
    }

    default String localId() {
        return source().localId();
    }

    default String namespace() {
        return source().namespace();
    }

    default String type() {
        return source().type();
    }

    default String sourcePath() {
        return source().sourcePath();
    }

    default String packId() {
        return source().packId();
    }

    default JsonObject raw() {
        return source().definition().deepCopy();
    }
}
