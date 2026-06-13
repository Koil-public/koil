package com.spirit.mixin.server.stats;

import com.spirit.koil.api.stats.global.GlobalActivityApi;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

@Mixin(BrewingStandBlockEntity.class)
public abstract class MixinBrewingStandBlockEntity {
    @Unique private static final Map<BrewingStandBlockEntity, ItemStack[]> koil$brewingSnapshots = new IdentityHashMap<>();

    @Inject(method = "tick", at = @At("HEAD"))
    private static void koil$captureBrewingBefore(World world, BlockPos pos, BlockState state, BrewingStandBlockEntity blockEntity, CallbackInfo info) {
        if (world == null || world.isClient || blockEntity == null) {
            return;
        }

        koil$brewingSnapshots.put(blockEntity, koil$snapshot(blockEntity));
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private static void koil$captureBrewingAfter(World world, BlockPos pos, BlockState state, BrewingStandBlockEntity blockEntity, CallbackInfo info) {
        if (world == null || world.isClient || blockEntity == null) {
            return;
        }

        ItemStack[] before = koil$brewingSnapshots.remove(blockEntity);

        if (before == null || before.length < 5) {
            return;
        }

        MinecraftServer server = world.getServer();

        if (server == null) {
            return;
        }

        ItemStack[] after = koil$snapshot(blockEntity);
        String processorName = koil$processorName(state, pos);
        int ingredientConsumed = koil$decrease(before[3], after[3]);
        int fuelConsumed = koil$decrease(before[4], after[4]);

        if (ingredientConsumed > 0) {
            GlobalActivityApi.recordProcessingItem(server, processorName, "brew_ingredient", before[3], ingredientConsumed);
        }

        if (fuelConsumed > 0) {
            GlobalActivityApi.recordProcessingItem(server, processorName, "brew_fuel", before[4], fuelConsumed);
        }

        for (int i = 0; i < 3; i++) {
            if (koil$changed(before[i], after[i]) && !after[i].isEmpty()) {
                GlobalActivityApi.recordProcessingItem(server, processorName, "brew_output", after[i], after[i].getCount());
            }
        }
    }

    @Unique
    private static ItemStack[] koil$snapshot(BrewingStandBlockEntity blockEntity) {
        ItemStack[] stacks = new ItemStack[5];

        for (int i = 0; i < stacks.length; i++) {
            stacks[i] = blockEntity.getStack(i).copy();
        }

        return stacks;
    }

    @Unique
    private static boolean koil$changed(ItemStack before, ItemStack after) {
        if (before == null) {
            before = ItemStack.EMPTY;
        }

        if (after == null) {
            after = ItemStack.EMPTY;
        }

        return !ItemStack.areItemsEqual(before, after) || before.getCount() != after.getCount();
    }

    @Unique
    private static int koil$decrease(ItemStack before, ItemStack after) {
        if (before == null || before.isEmpty()) {
            return 0;
        }

        if (after == null || after.isEmpty()) {
            return Math.max(0, before.getCount());
        }

        if (!ItemStack.areItemsEqual(before, after)) {
            return Math.max(0, before.getCount());
        }

        return Math.max(0, before.getCount() - after.getCount());
    }

    @Unique
    private static String koil$processorName(BlockState state, BlockPos pos) {
        String blockName = "minecraft:brewing_stand";

        try {
            Identifier id = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock());
            blockName = id.getNamespace() + ":" + id.getPath();
        } catch (Exception ignored) {
        }

        if (pos == null) {
            return blockName;
        }

        return blockName + " @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
