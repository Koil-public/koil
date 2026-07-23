package com.spirit.koil.api.registry;

import com.spirit.koil.api.registry.definition.ContentDefinition;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Rarity;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

import java.util.List;

/** Generic item holder whose presentation and safe behavior follow the active world definition. */
final class DynamicContentItem extends Item {
    private final String contentId;
    private final WorldContentIndex.DefinitionEntry bootstrapDefinition;

    DynamicContentItem(ContentDefinition definition) {
        super(DefinitionValueReader.itemSettings(definition));
        contentId = definition.id();
        bootstrapDefinition = definition.source();
    }

    @Override
    public Text getName(ItemStack stack) {
        return WorldScopedContentPresentation.name(contentId, bootstrapDefinition, false);
    }

    @Override
    public void appendTooltip(ItemStack stack, World world, List<Text> tooltip, TooltipContext context) {
        WorldScopedContentPresentation.appendTooltip(contentId, bootstrapDefinition, tooltip);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        if (!WorldScopedContentPresentation.isActive(contentId)) {
            return TypedActionResult.fail(user.getStackInHand(hand));
        }
        return super.use(world, user, hand);
    }

    @Override
    public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        WorldScopedContentPresentation.updateInactiveMarker(contentId, stack, world);
        super.inventoryTick(stack, world, entity, slot, selected);
    }

    @Override
    public int getEnchantability() {
        return WorldScopedContentPresentation.enchantability(contentId, bootstrapDefinition);
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return WorldScopedContentPresentation.isActive(contentId) && getEnchantability() > 0 && super.isEnchantable(stack);
    }

    @Override
    public Rarity getRarity(ItemStack stack) {
        return WorldScopedContentPresentation.rarity(contentId, bootstrapDefinition);
    }

    @Override
    public UseAction getUseAction(ItemStack stack) {
        return WorldScopedContentPresentation.useAction(contentId, bootstrapDefinition);
    }
}
