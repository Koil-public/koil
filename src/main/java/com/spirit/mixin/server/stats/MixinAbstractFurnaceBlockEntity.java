package com.spirit.mixin.server.stats;

import com.spirit.koil.api.stats.global.GlobalActivityApi;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
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

import java.util.IdentityHashMap;
import java.util.Map;

@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class MixinAbstractFurnaceBlockEntity {
    @Unique private static final Map<AbstractFurnaceBlockEntity, Object[]> koil$furnaceSnapshots = new IdentityHashMap<>();

    @Inject(method = "tick", at = @At("HEAD"))
    private static void koil$captureFurnaceBefore(World world, BlockPos pos, BlockState state, AbstractFurnaceBlockEntity blockEntity, CallbackInfo info) {
        if (world == null || world.isClient || blockEntity == null) {
            return;
        }

        koil$furnaceSnapshots.put(blockEntity, new Object[]{blockEntity.getStack(0).copy(), blockEntity.getStack(1).copy(), blockEntity.getStack(2).copy()});
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private static void koil$captureFurnaceAfter(World world, BlockPos pos, BlockState state, AbstractFurnaceBlockEntity blockEntity, CallbackInfo info) {
        if (world == null || world.isClient || blockEntity == null) {
            return;
        }

        Object[] snapshot = koil$furnaceSnapshots.remove(blockEntity);

        if (snapshot == null || snapshot.length < 3) {
            return;
        }

        MinecraftServer server = world.getServer();

        if (server == null) {
            return;
        }

        ItemStack beforeInput = (ItemStack) snapshot[0];
        ItemStack beforeFuel = (ItemStack) snapshot[1];
        ItemStack beforeOutput = (ItemStack) snapshot[2];
        ItemStack afterInput = blockEntity.getStack(0);
        ItemStack afterFuel = blockEntity.getStack(1);
        ItemStack afterOutput = blockEntity.getStack(2);
        String processorName = koil$processorName(state, pos);
        int inputConsumed = koil$decrease(beforeInput, afterInput);
        int fuelConsumed = koil$decrease(beforeFuel, afterFuel);
        int outputProduced = koil$increase(beforeOutput, afterOutput);

        if (inputConsumed > 0) {
            GlobalActivityApi.recordProcessingItem(server, processorName, "smelt_input", beforeInput, inputConsumed);
        }

        if (fuelConsumed > 0) {
            GlobalActivityApi.recordProcessingItem(server, processorName, "fuel_burned", beforeFuel, fuelConsumed);
        }

        if (outputProduced > 0) {
            GlobalActivityApi.recordProcessingItem(server, processorName, "smelt_output", afterOutput, outputProduced);
        }

        if (fuelConsumed > 0 && outputProduced > 0) {
            GlobalActivityApi.recordProcessingPair(server, processorName, "fuel_supported_output", beforeFuel, afterOutput, Math.min(fuelConsumed, outputProduced));
        }
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
    private static int koil$increase(ItemStack before, ItemStack after) {
        if (after == null || after.isEmpty()) {
            return 0;
        }

        if (before == null || before.isEmpty()) {
            return Math.max(0, after.getCount());
        }

        if (!ItemStack.areItemsEqual(before, after)) {
            return Math.max(0, after.getCount());
        }

        return Math.max(0, after.getCount() - before.getCount());
    }

    @Unique
    private static String koil$processorName(BlockState state, BlockPos pos) {
        String blockName = "furnace";

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
