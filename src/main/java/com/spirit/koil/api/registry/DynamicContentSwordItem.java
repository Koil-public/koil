package com.spirit.koil.api.registry;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.spirit.koil.api.registry.definition.ContentDefinition;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.text.Text;
import net.minecraft.util.Rarity;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

import java.util.List;

/** Sword-profile holder with fixed registry shape and world-reloadable presentation/safety state. */
final class DynamicContentSwordItem extends SwordItem {
    private final String contentId;
    private final WorldContentIndex.DefinitionEntry bootstrapDefinition;

    DynamicContentSwordItem(ContentDefinition definition) {
        super(
                new DynamicContentToolMaterial(definition),
                Math.max(0, Math.round(DefinitionValueReader.decimal(
                        DefinitionValueReader.object(
                                DefinitionValueReader.object(definition.raw(), "properties"),
                                "weapon"
                        ),
                        "attack_damage",
                        1.0F
                ))),
                DefinitionValueReader.decimal(
                        DefinitionValueReader.object(
                                DefinitionValueReader.object(definition.raw(), "properties"),
                                "weapon"
                        ),
                        "attack_speed",
                        -2.4F
                ),
                DefinitionValueReader.itemSettings(definition)
        );
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
    public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
        WorldScopedContentPresentation.updateInactiveMarker(contentId, stack, world);
        super.inventoryTick(stack, world, entity, slot, selected);
    }

    @Override
    public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        return WorldScopedContentPresentation.isActive(contentId) && super.postHit(stack, target, attacker);
    }

    @Override
    public Multimap<EntityAttribute, EntityAttributeModifier> getAttributeModifiers(EquipmentSlot slot) {
        if (!WorldScopedContentPresentation.isActive(contentId)) {
            return ImmutableMultimap.of();
        }
        return super.getAttributeModifiers(slot);
    }

    @Override
    public int getEnchantability() {
        return WorldScopedContentPresentation.enchantability(contentId, bootstrapDefinition);
    }

    @Override
    public boolean isEnchantable(ItemStack stack) {
        return WorldScopedContentPresentation.isActive(contentId) && super.isEnchantable(stack);
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
