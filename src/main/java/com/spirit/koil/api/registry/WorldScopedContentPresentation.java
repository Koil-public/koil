package com.spirit.koil.api.registry;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Rarity;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

import java.util.List;

/** Shared dynamic name, tooltip, and inactive-marker behavior for physical Content holders. */
final class WorldScopedContentPresentation {
    private static final String INACTIVE_KEY = "KoilInactiveContent";

    private WorldScopedContentPresentation() {
    }

    static WorldContentIndex.DefinitionEntry activeDefinition(String id) {
        return DynamicRegistryManager.instance().activeDefinitions().get(id);
    }

    static boolean isActive(String id) {
        return activeDefinition(id) != null;
    }

    static MutableText name(String id, WorldContentIndex.DefinitionEntry bootstrap, boolean block) {
        WorldContentIndex.DefinitionEntry active = activeDefinition(id);
        if (active == null) {
            return Text.literal("Inactive Content: " + id).formatted(Formatting.RED);
        }
        return Text.translatableWithFallback(
                DefinitionValueReader.languageKey(active, block),
                DefinitionValueReader.displayName(active)
        );
    }

    static void appendTooltip(
            String id,
            WorldContentIndex.DefinitionEntry bootstrap,
            List<Text> tooltip
    ) {
        WorldContentIndex.DefinitionEntry active = activeDefinition(id);
        if (active == null) {
            tooltip.add(Text.literal("Not active in this world").formatted(Formatting.RED));
            tooltip.add(Text.literal(id).formatted(Formatting.DARK_GRAY));
            return;
        }
        for (String line : DefinitionValueReader.lore(active)) {
            tooltip.add(Text.literal(line).formatted(Formatting.GRAY));
        }
        tooltip.add(Text.literal("World-scoped Content").formatted(Formatting.DARK_GRAY));
    }

    static void updateInactiveMarker(String id, ItemStack stack, World world) {
        if (world.isClient) {
            return;
        }
        if (isActive(id)) {
            NbtCompound nbt = stack.getNbt();
            if (nbt != null) {
                nbt.remove(INACTIVE_KEY);
            }
        } else {
            stack.getOrCreateNbt().putString(INACTIVE_KEY, id);
        }
    }

    static int enchantability(String id, WorldContentIndex.DefinitionEntry bootstrap) {
        WorldContentIndex.DefinitionEntry active = activeDefinition(id);
        return active == null ? 0 : DefinitionValueReader.enchantability(active);
    }

    static Rarity rarity(String id, WorldContentIndex.DefinitionEntry bootstrap) {
        WorldContentIndex.DefinitionEntry active = activeDefinition(id);
        return active == null ? Rarity.COMMON : DefinitionValueReader.rarity(active);
    }

    static UseAction useAction(String id, WorldContentIndex.DefinitionEntry bootstrap) {
        WorldContentIndex.DefinitionEntry active = activeDefinition(id);
        return active == null ? UseAction.NONE : DefinitionValueReader.useAction(active);
    }
}
