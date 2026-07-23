package com.spirit.koil.api.registry;

import com.spirit.koil.api.registry.definition.ContentDefinition;
import net.minecraft.block.Block;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.Entity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Rarity;
import net.minecraft.world.World;

import java.util.List;

/** Block-item holder that refuses placement when its definition is inactive in the loaded world. */
final class DynamicContentBlockItem extends BlockItem {
    private final String contentId;
    private final WorldContentIndex.DefinitionEntry bootstrapDefinition;

    DynamicContentBlockItem(Block block, ContentDefinition definition) {
        super(block, DefinitionValueReader.itemSettings(definition));
        contentId = definition.id();
        bootstrapDefinition = definition.source();
    }

    @Override
    public Text getName(ItemStack stack) {
        return WorldScopedContentPresentation.name(contentId, bootstrapDefinition, true);
    }

    @Override
    public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
        WorldScopedContentPresentation.appendTooltip(contentId, bootstrapDefinition, tooltip);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        return WorldScopedContentPresentation.isActive(contentId) ? super.useOnBlock(context) : ActionResult.FAIL;
    }

    @Override
    public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        WorldScopedContentPresentation.updateInactiveMarker(contentId, stack, world);
        super.inventoryTick(stack, world, entity, slot, selected);
    }

    @Override
    public Rarity getRarity(ItemStack stack) {
        return WorldScopedContentPresentation.rarity(contentId, bootstrapDefinition);
    }
}
