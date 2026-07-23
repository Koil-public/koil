package com.spirit.koil.api.registry;

import com.spirit.koil.api.registry.definition.ContentDefinition;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.ToolMaterial;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** Immutable startup material for a physical tool holder. Runtime-safe fields remain descriptor-driven. */
final class DynamicContentToolMaterial implements ToolMaterial {
    private final int durability;
    private final float miningSpeed;
    private final int miningLevel;
    private final int enchantability;
    private final String repairIngredient;

    DynamicContentToolMaterial(ContentDefinition definition) {
        var properties = DefinitionValueReader.object(definition.raw(), "properties");
        var tool = DefinitionValueReader.object(properties, "tool");
        durability = Math.max(1, DefinitionValueReader.integer(properties, "durability", 250));
        miningSpeed = Math.max(0.0F, DefinitionValueReader.decimal(tool, "mining_speed", 1.0F));
        miningLevel = Math.max(0, DefinitionValueReader.integer(tool, "mining_level", 0));
        enchantability = DefinitionValueReader.enchantability(definition);
        repairIngredient = DefinitionValueReader.repairIngredient(definition);
    }

    @Override
    public int getDurability() {
        return durability;
    }

    @Override
    public float getMiningSpeedMultiplier() {
        return miningSpeed;
    }

    @Override
    public float getAttackDamage() {
        return 0.0F;
    }

    @Override
    public int getMiningLevel() {
        return miningLevel;
    }

    @Override
    public int getEnchantability() {
        return enchantability;
    }

    @Override
    public Ingredient getRepairIngredient() {
        Identifier id = Identifier.tryParse(repairIngredient);
        Item item = id != null && Registries.ITEM.containsId(id) ? Registries.ITEM.get(id) : Items.AIR;
        return item == Items.AIR ? Ingredient.EMPTY : Ingredient.ofItems(item);
    }
}
