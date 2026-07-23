package com.spirit.koil.api.registry.definition;

import com.spirit.koil.api.registry.WorldContentIndex;

import java.util.Locale;

/** Converts scanner entries into typed definitions without rewriting their source JSON. */
public final class DefinitionParser {
    private DefinitionParser() {
    }

    public static ContentDefinition parse(WorldContentIndex.DefinitionEntry source) {
        return switch (source.type().toLowerCase(Locale.ROOT)) {
            case "item" -> new ItemDefinition(source);
            case "block" -> new BlockDefinition(source);
            case "entity" -> new EntityDefinition(source);
            case "particle" -> new ParticleDefinition(source);
            case "effect" -> new EffectDefinition(source);
            case "sound" -> new SoundDefinition(source);
            case "creative_tab", "creative-tab" -> new CreativeTabDefinition(source);
            case "recipe" -> new RecipeDefinition(source);
            case "tag" -> new TagDefinition(source);
            default -> new GenericContentDefinition(source);
        };
    }
}
