package com.spirit.koil.api.f3;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.state.property.Property;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class F3TargetInspector {
    private F3TargetInspector() {
    }

    public static F3TargetSnapshot inspect(MinecraftClient client, F3Mode mode) {
        if (client == null || client.world == null || client.player == null || client.crosshairTarget == null) {
            return F3TargetSnapshot.none();
        }
        HitResult hit = client.crosshairTarget;
        if (hit instanceof EntityHitResult entityHit) {
            return inspectEntity(client, entityHit.getEntity(), mode);
        }
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return inspectBlock(client, blockHit.getBlockPos(), mode);
        }
        return inspectHeldItem(client, mode);
    }

    public static F3TargetSnapshot inspectHeldItem(MinecraftClient client, F3Mode mode) {
        if (client == null || client.player == null) {
            return F3TargetSnapshot.none();
        }
        ItemStack stack = client.player.getMainHandStack();
        if (stack.isEmpty()) {
            return F3TargetSnapshot.none();
        }
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        List<F3DataLine> lines = new ArrayList<>();
        lines.add(F3DataLine.state("Count", String.valueOf(stack.getCount()), "item", 0xFF8FC5FF, "Stack count currently held."));
        lines.add(F3DataLine.of("Durability", durabilityText(stack)));
        lines.add(F3DataLine.of("Rarity", stack.getRarity().toString()));
        lines.add(F3DataLine.of("Enchantments", String.valueOf(EnchantmentHelper.get(stack).size())));
        if (stack.isFood()) {
            lines.add(F3DataLine.of("Food", stack.getItem().getFoodComponent().getHunger() + " hunger"));
        }
        if (mode.developerDataEnabled()) {
            lines.add(F3DataLine.of("Class", stack.getItem().getClass().getName()));
            lines.add(F3DataLine.of("Damage", stack.isDamageable() ? stack.getDamage() + "/" + stack.getMaxDamage() : "none"));
            lines.add(F3DataLine.of("Max Count", String.valueOf(stack.getMaxCount())));
            lines.add(F3DataLine.of("Translation Key", stack.getTranslationKey()));
            lines.add(F3DataLine.of("NBT Present", String.valueOf(stack.hasNbt())));
            lines.add(F3DataLine.of("NBT", stack.hasNbt() ? stack.getNbt().toString() : "none"));
        }
        return new F3TargetSnapshot(
                F3TargetType.ITEM,
                stack.getName().getString(),
                "Held item",
                id,
                modOwner(id),
                "player main hand",
                "Low",
                F3TargetType.ITEM.color(),
                lines,
                itemTags(stack),
                List.of("copy_item_id", "copy_item_report", "where_from")
        );
    }

    private static F3TargetSnapshot inspectBlock(MinecraftClient client, BlockPos pos, F3Mode mode) {
        BlockState state = client.world.getBlockState(pos);
        FluidState fluid = client.world.getFluidState(pos);
        if (!fluid.isEmpty() && state.isAir()) {
            return inspectFluid(client, pos, fluid, mode);
        }
        String id = Registries.BLOCK.getId(state.getBlock()).toString();
        List<F3DataLine> lines = new ArrayList<>();
        lines.add(F3DataLine.state("Position", pos.toShortString(), "world", 0xFFE3B735, "Block position in the current dimension."));
        lines.add(F3DataLine.of("State Block", id));
        lines.add(F3DataLine.of("State Values", state.getProperties().isEmpty() ? "none" : state.getProperties().size() + " values"));
        lines.add(F3DataLine.of("Biome", client.world.getBiome(pos).getKey().map(key -> key.getValue().toString()).orElse("unknown")));
        lines.add(F3DataLine.of("Chunk Section", (pos.getX() >> 4) + ", " + (pos.getY() >> 4) + ", " + (pos.getZ() >> 4)));
        lines.add(F3DataLine.of("Hardness", formatNumber(state.getHardness(client.world, pos))));
        lines.add(F3DataLine.of("Blast Resistance", formatNumber(state.getBlock().getBlastResistance())));
        lines.add(F3DataLine.of("Break Pressure Est", blockBreakScore(state, client.world, pos)));
        lines.add(F3DataLine.of("Properties", String.valueOf(state.getProperties().size())));
        int emittedLight = state.getLuminance();
        int blockLight = client.world.getLightLevel(LightType.BLOCK, pos);
        int skyLight = client.world.getLightLevel(LightType.SKY, pos);
        int combinedLight = client.world.getLightLevel(pos);
        lines.add(F3DataLine.state("Emitted Light", String.valueOf(emittedLight), "direct", 0xFFB8C4D2, "Exact luminance emitted by this block state."));
        lines.add(F3DataLine.state("Block Light", String.valueOf(blockLight), "direct", 0xFFB8C4D2, "Exact client block-light value at the targeted position."));
        lines.add(F3DataLine.state("Sky Light", String.valueOf(skyLight), "direct", 0xFFB8C4D2, "Exact client sky-light value at the targeted position."));
        lines.add(F3DataLine.state("Combined Light", String.valueOf(combinedLight), "direct", 0xFFB8C4D2, "Minecraft's combined client light level at the targeted position."));
        lines.add(F3DataLine.of("Redstone Power", String.valueOf(client.world.getReceivedRedstonePower(pos))));
        lines.add(F3DataLine.of("Comparator Output", state.hasComparatorOutput() ? String.valueOf(state.getComparatorOutput(client.world, pos)) : "none"));
        lines.add(F3DataLine.of("Emits Redstone", String.valueOf(state.emitsRedstonePower())));
        lines.add(F3DataLine.of("Opaque", String.valueOf(state.isOpaque())));
        lines.add(F3DataLine.of("Solid Block", String.valueOf(state.isSolidBlock(client.world, pos))));
        lines.add(F3DataLine.of("Air", String.valueOf(state.isAir())));
        lines.add(F3DataLine.of("Replaceable", String.valueOf(state.isReplaceable())));
        lines.add(F3DataLine.of("Tool Required", String.valueOf(state.isToolRequired())));
        lines.add(F3DataLine.of("Burnable", String.valueOf(state.isBurnable())));
        lines.add(F3DataLine.of("Random Ticks", String.valueOf(state.hasRandomTicks())));
        lines.add(F3DataLine.of("Side Solid Up", String.valueOf(state.isSideSolidFullSquare(client.world, pos, Direction.UP))));
        lines.add(F3DataLine.of("Side Solid Down", String.valueOf(state.isSideSolidFullSquare(client.world, pos, Direction.DOWN))));
        lines.add(F3DataLine.of("Side Solid North", String.valueOf(state.isSideSolidFullSquare(client.world, pos, Direction.NORTH))));
        lines.add(F3DataLine.of("Side Solid South", String.valueOf(state.isSideSolidFullSquare(client.world, pos, Direction.SOUTH))));
        lines.add(F3DataLine.of("Side Solid East", String.valueOf(state.isSideSolidFullSquare(client.world, pos, Direction.EAST))));
        lines.add(F3DataLine.of("Side Solid West", String.valueOf(state.isSideSolidFullSquare(client.world, pos, Direction.WEST))));
        lines.add(F3DataLine.of("Waterlogged/Fluid", fluid.isEmpty() ? "none" : Registries.FLUID.getId(fluid.getFluid()).toString()));
        lines.add(F3DataLine.of("Fluid Level", fluid.isEmpty() ? "none" : String.valueOf(fluid.getLevel())));
        lines.add(F3DataLine.of("Fluid Still", fluid.isEmpty() ? "none" : String.valueOf(fluid.isStill())));
        lines.add(F3DataLine.of("Fluid Height", fluid.isEmpty() ? "none" : formatNumber(fluid.getHeight())));
        VoxelShape collisionShape = state.getCollisionShape(client.world, pos);
        VoxelShape outlineShape = state.getOutlineShape(client.world, pos);
        lines.add(F3DataLine.of("Collision Empty", String.valueOf(collisionShape.isEmpty())));
        lines.add(F3DataLine.of("Collision Boxes", String.valueOf(collisionShape.getBoundingBoxes().size())));
        lines.add(F3DataLine.of("Collision Height", shapeHeight(collisionShape)));
        lines.add(F3DataLine.of("Outline Empty", String.valueOf(outlineShape.isEmpty())));
        lines.add(F3DataLine.of("Outline Boxes", String.valueOf(outlineShape.getBoundingBoxes().size())));
        lines.add(F3DataLine.of("Outline Height", shapeHeight(outlineShape)));
        lines.add(F3DataLine.of("Slipperiness", formatNumber(state.getBlock().getSlipperiness())));
        lines.add(F3DataLine.of("Velocity Mult", formatNumber(state.getBlock().getVelocityMultiplier())));
        lines.add(F3DataLine.of("Jump Mult", formatNumber(state.getBlock().getJumpVelocityMultiplier())));
        ItemStack tool = client.player.getMainHandStack();
        boolean toolSuitable = !state.isToolRequired() || tool.isSuitableFor(state);
        lines.add(F3DataLine.state("Current Tool", tool.isEmpty() ? "empty hand" : tool.getName().getString(), toolSuitable ? "good" : "review", toolSuitable ? 0xFF2DA700 : 0xFFE3B735, "Shows whether the current item can harvest this block according to Minecraft's client-visible state."));
        lines.add(F3DataLine.of("Tool Suitable", String.valueOf(toolSuitable)));
        lines.add(F3DataLine.of("Mining Speed", tool.isEmpty() ? "1.00" : formatNumber(tool.getMiningSpeedMultiplier(state))));
        lines.add(F3DataLine.of("Relative Mining Speed Est", miningScore(state, tool, client.world, pos)));
        BlockEntity blockEntity = client.world.getBlockEntity(pos);
        lines.add(F3DataLine.of("Block Entity", blockEntity == null ? "none" : Registries.BLOCK_ENTITY_TYPE.getId(blockEntity.getType()).toString()));
        lines.add(F3DataLine.of("Has Block Entity", String.valueOf(blockEntity != null)));
        if (blockEntity != null) {
            lines.add(F3DataLine.of("Block Entity Class", blockEntity.getClass().getName()));
        }
        addBlockStateSummary(lines, state);
        if (!state.getProperties().isEmpty()) {
            lines.add(new F3DataLine("", "", "divider", 0xFFE3B735, ""));
            lines.add(F3DataLine.state("State Properties", state.getProperties().size() + " values", "header", 0xFFE3B735, "All block state properties with no cap."));
        }
        for (Property<?> property : state.getProperties()) {
            lines.add(F3DataLine.state("State Value", property.getName() + " = " + String.valueOf(state.get(property)), "state", 0xFFB8C4D2, "Single block state value rendered as one vertical row."));
        }
        if (mode.developerDataEnabled()) {
            lines.add(new F3DataLine("", "", "divider", 0xFFE6EDF5, ""));
            lines.add(F3DataLine.state("Block Developer", "complete", "header", 0xFFE6EDF5, "Developer block class and translation data."));
            lines.add(F3DataLine.of("Class", state.getBlock().getClass().getName()));
            lines.add(F3DataLine.of("Translation Key", state.getBlock().getTranslationKey()));
        }
        F3TargetType type = blockEntity == null ? F3TargetType.BLOCK : F3TargetType.CONTAINER;
        return new F3TargetSnapshot(
                type,
                state.getBlock().getName().getString(),
                blockEntity == null ? "Target block" : "Target block entity",
                id,
                modOwner(id),
                pos.toShortString(),
                blockDanger(state, fluid),
                type.color(),
                lines,
                blockTags(state),
                List.of("copy_block_id", "copy_block_state", "copy_position", "where_from", "what_can_i_do")
        );
    }

    private static F3TargetSnapshot inspectFluid(MinecraftClient client, BlockPos pos, FluidState fluid, F3Mode mode) {
        String id = Registries.FLUID.getId(fluid.getFluid()).toString();
        List<F3DataLine> lines = new ArrayList<>();
        lines.add(F3DataLine.state("Position", pos.toShortString(), "world", 0xFFE3B735, "Fluid block position."));
        lines.add(F3DataLine.of("Biome", client.world.getBiome(pos).getKey().map(key -> key.getValue().toString()).orElse("unknown")));
        lines.add(F3DataLine.of("Level", String.valueOf(fluid.getLevel())));
        lines.add(F3DataLine.of("Still", String.valueOf(fluid.isStill())));
        lines.add(F3DataLine.of("Empty", String.valueOf(fluid.isEmpty())));
        lines.add(F3DataLine.of("Height", formatNumber(fluid.getHeight())));
        lines.add(F3DataLine.of("Velocity", formatNumber(fluid.getVelocity(client.world, pos).x) + ", " + formatNumber(fluid.getVelocity(client.world, pos).y) + ", " + formatNumber(fluid.getVelocity(client.world, pos).z)));
        lines.add(F3DataLine.of("Flow Speed", formatNumber(Math.sqrt(fluid.getVelocity(client.world, pos).x * fluid.getVelocity(client.world, pos).x + fluid.getVelocity(client.world, pos).z * fluid.getVelocity(client.world, pos).z))));
        lines.add(F3DataLine.of("Block State", fluid.getBlockState().toString()));
        if (mode.developerDataEnabled()) {
            lines.add(F3DataLine.of("Class", fluid.getFluid().getClass().getName()));
        }
        return new F3TargetSnapshot(
                F3TargetType.FLUID,
                id,
                fluid.isStill() ? "Still fluid" : "Flowing fluid",
                id,
                modOwner(id),
                pos.toShortString(),
                id.contains("lava") ? "High" : "Low",
                F3TargetType.FLUID.color(),
                lines,
                List.of(),
                List.of("copy_fluid_id", "copy_position", "where_from")
        );
    }

    private static F3TargetSnapshot inspectEntity(MinecraftClient client, Entity entity, F3Mode mode) {
        if (entity instanceof ItemEntity itemEntity) {
            return inspectItemEntity(itemEntity, mode);
        }
        String id = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
        boolean player = entity instanceof PlayerEntity;
        List<F3DataLine> lines = new ArrayList<>();
        lines.add(F3DataLine.state("Distance", formatNumber(client.player.distanceTo(entity)) + " blocks", "live", 0xFF7FC8C2, "Distance from your player to this entity."));
        lines.add(F3DataLine.of("Type", id));
        lines.add(F3DataLine.of("Spawn Group", entity.getType().getSpawnGroup().getName()));
        lines.add(F3DataLine.of("Position", formatNumber(entity.getX()) + ", " + formatNumber(entity.getY()) + ", " + formatNumber(entity.getZ())));
        lines.add(F3DataLine.of("Block Pos", entity.getBlockPos().toShortString()));
        lines.add(F3DataLine.of("Chunk Pos", entity.getChunkPos().x + ", " + entity.getChunkPos().z));
        lines.add(F3DataLine.of("Velocity", formatNumber(entity.getVelocity().x) + ", " + formatNumber(entity.getVelocity().y) + ", " + formatNumber(entity.getVelocity().z)));
        lines.add(F3DataLine.of("Horizontal Speed", formatNumber(Math.sqrt(entity.getVelocity().x * entity.getVelocity().x + entity.getVelocity().z * entity.getVelocity().z))));
        lines.add(F3DataLine.of("Vertical Speed", formatNumber(entity.getVelocity().y)));
        lines.add(F3DataLine.of("Speed Magnitude", formatNumber(Math.sqrt(entity.getVelocity().x * entity.getVelocity().x + entity.getVelocity().y * entity.getVelocity().y + entity.getVelocity().z * entity.getVelocity().z))));
        lines.add(F3DataLine.of("Hitbox", formatNumber(entity.getWidth()) + " x " + formatNumber(entity.getHeight())));
        lines.add(F3DataLine.of("Eye Height", formatNumber(entity.getStandingEyeHeight())));
        lines.add(F3DataLine.of("Yaw/Pitch", formatNumber(entity.getYaw()) + " / " + formatNumber(entity.getPitch())));
        lines.add(F3DataLine.of("Age", entity.age + " ticks"));
        lines.add(F3DataLine.of("On Ground", String.valueOf(entity.isOnGround())));
        lines.add(F3DataLine.of("Touching Water", String.valueOf(entity.isTouchingWater())));
        lines.add(F3DataLine.of("In Lava", String.valueOf(entity.isInLava())));
        lines.add(F3DataLine.of("Submerged", String.valueOf(entity.isSubmergedInWater())));
        lines.add(F3DataLine.of("Sneaking", String.valueOf(entity.isSneaking())));
        lines.add(F3DataLine.of("Sprinting", String.valueOf(entity.isSprinting())));
        lines.add(F3DataLine.of("Swimming", String.valueOf(entity.isSwimming())));
        lines.add(F3DataLine.of("Glowing", String.valueOf(entity.isGlowing())));
        lines.add(F3DataLine.of("Invisible", String.valueOf(entity.isInvisible())));
        lines.add(F3DataLine.of("Invulnerable", String.valueOf(entity.isInvulnerable())));
        lines.add(F3DataLine.of("Silent", String.valueOf(entity.isSilent())));
        lines.add(F3DataLine.of("Pose", entity.getPose().toString()));
        lines.add(F3DataLine.of("Fire", entity.getFireTicks() + " ticks"));
        lines.add(F3DataLine.of("Frozen", entity.getFrozenTicks() + " ticks"));
        lines.add(F3DataLine.of("Air", entity.getAir() + "/" + entity.getMaxAir()));
        lines.add(F3DataLine.of("Fall Distance", formatNumber(entity.fallDistance)));
        lines.add(F3DataLine.of("No Gravity", String.valueOf(entity.hasNoGravity())));
            lines.add(F3DataLine.of("Vehicle", entity.hasVehicle() ? Registries.ENTITY_TYPE.getId(entity.getVehicle().getType()).toString() : "none"));
        lines.add(F3DataLine.of("Passengers", String.valueOf(entity.getPassengerList().size())));
        lines.add(F3DataLine.of("Custom Name", entity.hasCustomName() ? entity.getCustomName().getString() : "none"));
        if (entity instanceof LivingEntity living) {
            lines.add(new F3DataLine("", "", "divider", 0xFF2DA700, ""));
            lines.add(F3DataLine.state("Living Vitals", "client-visible", "header", healthColor(living), "Health, armor, attributes, effects, and combat data."));
            lines.add(F3DataLine.state("Health", formatNumber(living.getHealth()) + "/" + formatNumber(living.getMaxHealth()), "health", healthColor(living), "Entity health visible to the client."));
            lines.add(F3DataLine.of("Health Ratio", formatNumber(living.getMaxHealth() <= 0.0F ? 0.0D : living.getHealth() / living.getMaxHealth())));
            lines.add(F3DataLine.of("Absorption", formatNumber(living.getAbsorptionAmount())));
            lines.add(F3DataLine.of("Armor", String.valueOf(living.getArmor())));
            addAttribute(lines, living, EntityAttributes.GENERIC_MAX_HEALTH, "Max Health Attr");
            addAttribute(lines, living, EntityAttributes.GENERIC_ARMOR, "Armor Attr");
            addAttribute(lines, living, EntityAttributes.GENERIC_ARMOR_TOUGHNESS, "Armor Toughness");
            addAttribute(lines, living, EntityAttributes.GENERIC_ATTACK_DAMAGE, "Attack Damage");
            addAttribute(lines, living, EntityAttributes.GENERIC_ATTACK_KNOCKBACK, "Attack Knockback");
            addAttribute(lines, living, EntityAttributes.GENERIC_MOVEMENT_SPEED, "Movement Speed");
            addAttribute(lines, living, EntityAttributes.GENERIC_FOLLOW_RANGE, "Follow Range");
            addAttribute(lines, living, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, "Knockback Resist");
            lines.add(F3DataLine.of("Effects", String.valueOf(living.getStatusEffects().size())));
            living.getStatusEffects().forEach(effect -> lines.add(F3DataLine.of("Effect", effect.getEffectType().getName().getString() + " amp " + effect.getAmplifier() + " | " + effect.getDuration() + "t")));
        }
        if (entity instanceof MobEntity mob) {
            lines.add(new F3DataLine("", "", "divider", 0xFFE06A21, ""));
            lines.add(F3DataLine.state("Mob Brain", "client-visible", "header", 0xFFE06A21, "AI and target state visible to the client."));
            lines.add(F3DataLine.of("AI Disabled", String.valueOf(mob.isAiDisabled())));
            lines.add(F3DataLine.of("Can Pick Up Loot", String.valueOf(mob.canPickUpLoot())));
            lines.add(F3DataLine.of("Persistent", String.valueOf(mob.isPersistent())));
            lines.add(F3DataLine.of("Target", mob.getTarget() == null ? "none" : mob.getTarget().getName().getString()));
            lines.add(F3DataLine.of("Navigation Idle", String.valueOf(mob.getNavigation().isIdle())));
            var path = mob.getNavigation().getCurrentPath();
            lines.add(F3DataLine.of("Path Active", String.valueOf(path != null && !mob.getNavigation().isIdle())));
            lines.add(F3DataLine.of("Path Length", path == null ? "none" : String.valueOf(path.getLength())));
            lines.add(F3DataLine.of("Path Node", path == null ? "none" : path.getCurrentNodeIndex() + "/" + path.getLength()));
            lines.add(F3DataLine.of("Navigation Type", mob.getNavigation().getClass().getSimpleName()));
            lines.add(F3DataLine.of("Move Control", mob.getMoveControl().getClass().getSimpleName()));
            lines.add(F3DataLine.of("Look Control", mob.getLookControl().getClass().getSimpleName()));
            lines.add(F3DataLine.of("Brain", mob.getBrain().getClass().getSimpleName()));
            lines.add(F3DataLine.of("Goal Visibility", "server-side hidden"));
            lines.add(F3DataLine.of("Intent", mobIntent(client, mob)));
            double threatScore = mobThreatScore(client, mob);
            lines.add(F3DataLine.of("Threat Score", formatNumber(threatScore)));
            lines.add(F3DataLine.of("Threat Level", threatLabel(threatScore)));
        }
        if (mode.developerDataEnabled()) {
            lines.add(new F3DataLine("", "", "divider", 0xFFE6EDF5, ""));
            lines.add(F3DataLine.state("Entity Developer", "complete", "header", 0xFFE6EDF5, "Developer entity identifiers and bounds."));
            lines.add(F3DataLine.of("UUID", entity.getUuidAsString()));
            lines.add(F3DataLine.of("Class", entity.getClass().getName()));
            lines.add(F3DataLine.of("Id", String.valueOf(entity.getId())));
            lines.add(F3DataLine.of("Bounding Min", formatNumber(entity.getBoundingBox().minX) + ", " + formatNumber(entity.getBoundingBox().minY) + ", " + formatNumber(entity.getBoundingBox().minZ)));
            lines.add(F3DataLine.of("Bounding Max", formatNumber(entity.getBoundingBox().maxX) + ", " + formatNumber(entity.getBoundingBox().maxY) + ", " + formatNumber(entity.getBoundingBox().maxZ)));
        }
        return new F3TargetSnapshot(
                player ? F3TargetType.PLAYER : F3TargetType.ENTITY,
                entity.getName().getString(),
                player ? "Target player" : "Target entity",
                id,
                modOwner(id),
                entity.getBlockPos().toShortString(),
                entityDanger(client, entity),
                player ? F3TargetType.PLAYER.color() : F3TargetType.ENTITY.color(),
                lines,
                List.of(),
                List.of("copy_entity_id", "copy_entity_report", "where_from", "copy_ktl_target")
        );
    }

    private static F3TargetSnapshot inspectItemEntity(ItemEntity itemEntity, F3Mode mode) {
        ItemStack stack = itemEntity.getStack();
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        List<F3DataLine> lines = new ArrayList<>();
        lines.add(F3DataLine.of("Count", String.valueOf(stack.getCount())));
        lines.add(F3DataLine.of("Age", String.valueOf(itemEntity.getItemAge())));
        lines.add(F3DataLine.of("Position", itemEntity.getBlockPos().toShortString()));
        lines.add(F3DataLine.of("Velocity", formatNumber(itemEntity.getVelocity().x) + ", " + formatNumber(itemEntity.getVelocity().y) + ", " + formatNumber(itemEntity.getVelocity().z)));
        lines.add(F3DataLine.of("Speed Magnitude", formatNumber(Math.sqrt(itemEntity.getVelocity().x * itemEntity.getVelocity().x + itemEntity.getVelocity().y * itemEntity.getVelocity().y + itemEntity.getVelocity().z * itemEntity.getVelocity().z))));
        lines.add(F3DataLine.of("On Ground", String.valueOf(itemEntity.isOnGround())));
        lines.add(F3DataLine.of("Durability", durabilityText(stack)));
        lines.add(F3DataLine.of("Rarity", stack.getRarity().toString()));
        lines.add(F3DataLine.of("Enchantments", String.valueOf(EnchantmentHelper.get(stack).size())));
        lines.add(F3DataLine.of("NBT Present", String.valueOf(stack.hasNbt())));
        if (mode.developerDataEnabled()) {
            lines.add(F3DataLine.of("Class", itemEntity.getClass().getName()));
            lines.add(F3DataLine.of("Stack NBT", stack.hasNbt() ? stack.getNbt().toString() : "none"));
            lines.add(F3DataLine.of("Owner", itemEntity.getOwner() == null ? "unknown" : itemEntity.getOwner().toString()));
        }
        return new F3TargetSnapshot(
                F3TargetType.ITEM,
                stack.getName().getString(),
                "Dropped item",
                id,
                modOwner(id),
                itemEntity.getBlockPos().toShortString(),
                "Low",
                F3TargetType.ITEM.color(),
                lines,
                itemTags(stack),
                List.of("copy_item_id", "copy_position", "where_from")
        );
    }

    private static void addBlockStateSummary(List<F3DataLine> lines, BlockState state) {
        addStateSummary(lines, state, "facing", "Facing Dir");
        addStateSummary(lines, state, "axis", "Axis");
        addStateSummary(lines, state, "half", "Half");
        addStateSummary(lines, state, "shape", "Shape Detail");
        addStateSummary(lines, state, "type", "Type Detail");
        addStateSummary(lines, state, "powered", "Powered State");
        addStateSummary(lines, state, "power", "Power Level");
        addStateSummary(lines, state, "open", "Open State");
        addStateSummary(lines, state, "waterlogged", "Waterlogged State");
        addStateSummary(lines, state, "extended", "Extended State");
        addStateSummary(lines, state, "enabled", "Enabled State");
        addStateSummary(lines, state, "locked", "Locked State");
        addStateSummary(lines, state, "triggered", "Triggered State");
        addStateSummary(lines, state, "mode", "Mode State");
    }

    private static void addStateSummary(List<F3DataLine> lines, BlockState state, String propertyName, String label) {
        String value = propertyValue(state, propertyName);
        if (!value.equals("unknown")) {
            lines.add(F3DataLine.of(label, value));
        }
    }

    private static String propertyValue(BlockState state, String propertyName) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(propertyName)) {
                return String.valueOf(state.get(property));
            }
        }
        return "unknown";
    }

    private static String mobIntent(MinecraftClient client, MobEntity mob) {
        if (mob.getTarget() != null) {
            if (client != null && client.player != null && mob.getTarget() == client.player) {
                return "Aggro Player";
            }
            return "Aggro Target";
        }
        var path = mob.getNavigation().getCurrentPath();
        if (path != null && !mob.getNavigation().isIdle()) {
            return "Pathing";
        }
        if (!mob.getNavigation().isIdle()) {
            return "Navigating";
        }
        if (mob.getVelocity().horizontalLength() > 0.03D) {
            return "Roaming";
        }
        if (mob.isTouchingWater()) {
            return "Swimming";
        }
        return "Idle";
    }

    private static double mobThreatScore(MinecraftClient client, MobEntity mob) {
        double distance = client == null || client.player == null ? 32.0D : client.player.distanceTo(mob);
        double distanceScore = clampNumber(1.0D - distance / 20.0D, 0.0D, 1.0D);
        double attackScore = 0.0D;
        EntityAttributeInstance attack = mob.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        if (attack != null) {
            attackScore = clampNumber(attack.getValue() / 20.0D, 0.0D, 1.0D);
        }
        double speedScore = clampNumber(mob.getVelocity().horizontalLength() / 0.35D, 0.0D, 1.0D);
        double targetScore = mob.getTarget() == null ? 0.0D : mob.getTarget() == (client == null ? null : client.player) ? 1.0D : 0.65D;
        double pathScore = mob.getNavigation().isIdle() ? 0.0D : 0.45D;
        return clampNumber(distanceScore * 0.35D + attackScore * 0.25D + speedScore * 0.10D + targetScore * 0.20D + pathScore * 0.10D, 0.0D, 1.0D);
    }

    private static String threatLabel(double threatScore) {
        if (threatScore >= 0.78D) {
            return "Immediate";
        }
        if (threatScore >= 0.52D) {
            return "Threat";
        }
        if (threatScore >= 0.25D) {
            return "Watching";
        }
        return "Safe";
    }

    private static void addAttribute(List<F3DataLine> lines, LivingEntity entity, net.minecraft.entity.attribute.EntityAttribute attribute, String label) {
        EntityAttributeInstance instance = entity.getAttributeInstance(attribute);
        if (instance != null) {
            lines.add(F3DataLine.of(label, formatNumber(instance.getValue())));
        }
    }

    private static List<String> blockTags(BlockState state) {
        return state.streamTags()
                .map(TagKey::id)
                .map(Object::toString)
                .sorted()
                .toList();
    }

    private static List<String> itemTags(ItemStack stack) {
        return stack.streamTags()
                .map(TagKey::id)
                .map(Object::toString)
                .sorted()
                .toList();
    }

    private static String blockBreakScore(BlockState state, World world, BlockPos pos) {
        double hardness = state.getHardness(world, pos);
        double resistance = state.getBlock().getBlastResistance();
        if (hardness < 0.0D) {
            return "unbreakable";
        }
        double score = clampNumber((hardness / 50.0D) + (resistance / 1200.0D), 0.0D, 1.0D);
        return formatNumber(score);
    }

    private static String miningScore(BlockState state, ItemStack tool, World world, BlockPos pos) {
        double hardness = state.getHardness(world, pos);
        if (hardness < 0.0D) {
            return "unbreakable";
        }
        double speed = tool.isEmpty() ? 1.0D : tool.getMiningSpeedMultiplier(state);
        double score = hardness <= 0.0D ? speed : speed / Math.max(1.0D, hardness);
        return formatNumber(score);
    }

    private static String shapeHeight(VoxelShape shape) {
        return shape == null || shape.isEmpty() ? "none" : formatNumber(shape.getMax(Direction.Axis.Y));
    }

    private static double clampNumber(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String modOwner(String registryId) {
        if (registryId == null || !registryId.contains(":")) {
            return "Unknown";
        }
        String namespace = registryId.substring(0, registryId.indexOf(':'));
        if ("minecraft".equals(namespace)) {
            return "Minecraft";
        }
        Optional<String> name = FabricLoader.getInstance().getModContainer(namespace)
                .map(container -> container.getMetadata().getName());
        return name.orElse(namespace);
    }

    private static String durabilityText(ItemStack stack) {
        if (!stack.isDamageable()) {
            return "not damageable";
        }
        return (stack.getMaxDamage() - stack.getDamage()) + "/" + stack.getMaxDamage();
    }

    private static String blockDanger(BlockState state, FluidState fluid) {
        String stateText = state.toString().toLowerCase(Locale.ROOT);
        String fluidText = fluid.isEmpty() ? "" : Registries.FLUID.getId(fluid.getFluid()).toString();
        if (stateText.contains("fire") || stateText.contains("lava") || fluidText.contains("lava")) {
            return "High";
        }
        if (state.getLuminance() <= 0 && !state.isAir()) {
            return "Normal";
        }
        return "Low";
    }

    private static int healthColor(LivingEntity entity) {
        double ratio = entity.getMaxHealth() <= 0 ? 0.0D : entity.getHealth() / entity.getMaxHealth();
        if (ratio > 0.65D) return 0xFF2DA700;
        if (ratio > 0.35D) return 0xFFE3B735;
        return 0xFFA7003A;
    }

    private static String entityDanger(MinecraftClient client, Entity entity) {
        double distance = client.player == null ? 999.0D : client.player.distanceTo(entity);
        if (!(entity instanceof LivingEntity living)) {
            return "Low";
        }
        double damage = 0.0D;
        EntityAttributeInstance damageAttribute = living.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        if (damageAttribute != null) {
            damage = damageAttribute.getValue();
        }
        if (damage >= 8.0D && distance < 8.0D) {
            return "High";
        }
        if (damage > 0.0D && distance < 16.0D) {
            return "Medium";
        }
        return "Low";
    }

    private static String formatNumber(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
