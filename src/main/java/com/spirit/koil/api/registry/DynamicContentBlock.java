package com.spirit.koil.api.registry;

import com.spirit.koil.api.registry.definition.ContentDefinition;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

/** Generic early-registered block holder for a world-scoped block definition. */
final class DynamicContentBlock extends Block {
    private final String contentId;
    private final WorldContentIndex.DefinitionEntry bootstrapDefinition;

    DynamicContentBlock(ContentDefinition definition) {
        super(DefinitionValueReader.blockSettings(definition));
        contentId = definition.id();
        bootstrapDefinition = definition.source();
    }

    @Override
    public MutableText getName() {
        return WorldScopedContentPresentation.name(contentId, bootstrapDefinition, true);
    }

    @Override
    public ItemStack getPickStack(BlockView world, BlockPos pos, BlockState state) {
        return WorldScopedContentPresentation.isActive(contentId) ? super.getPickStack(world, pos, state) : ItemStack.EMPTY;
    }
}
