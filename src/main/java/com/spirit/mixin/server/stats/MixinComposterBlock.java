package com.spirit.mixin.server.stats;

import com.spirit.koil.api.stats.global.GlobalActivityApi;
import net.minecraft.block.BlockState;
import net.minecraft.block.ComposterBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;

@Mixin(ComposterBlock.class)
public abstract class MixinComposterBlock {
    @Unique private static final Map<String, Object[]> koil$composterSnapshots = new LinkedHashMap<>();

    @Inject(method = "onUse", at = @At("HEAD"))
    private void koil$captureComposterBefore(BlockState state, World world, BlockPos pos, net.minecraft.entity.player.PlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> info) {
        if (world == null || world.isClient || pos == null || player == null || hand == null) {
            return;
        }

        koil$composterSnapshots.put(koil$key(world, pos, player.getUuid(), hand), new Object[]{state, player.getStackInHand(hand).copy()});
    }

    @Inject(method = "onUse", at = @At("TAIL"))
    private void koil$captureComposterAfter(BlockState state, World world, BlockPos pos, net.minecraft.entity.player.PlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> info) {
        if (world == null || world.isClient || pos == null || player == null || hand == null || !(player instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }

        Object[] snapshot = koil$composterSnapshots.remove(koil$key(world, pos, player.getUuid(), hand));

        if (snapshot == null || snapshot.length < 2) {
            return;
        }

        MinecraftServer server = world.getServer();

        if (server == null) {
            return;
        }

        BlockState beforeState = (BlockState) snapshot[0];
        ItemStack beforeStack = (ItemStack) snapshot[1];
        ItemStack afterStack = player.getStackInHand(hand);
        int beforeLevel = koil$level(beforeState);
        int afterLevel = koil$level(world.getBlockState(pos));
        int consumed = koil$decrease(beforeStack, afterStack);

        if (consumed <= 0 && afterLevel <= beforeLevel) {
            return;
        }

        if (consumed > 0 && !beforeStack.isEmpty()) {
            GlobalActivityApi.recordProcessingItem(server, "minecraft:composter @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ(), "compost_input", beforeStack, consumed);
        }

        if (afterLevel > beforeLevel) {
            GlobalActivityApi.recordProcessingActivity(server, "minecraft:composter @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ(), "compost_level_gain/minecraft/composter", afterLevel - beforeLevel);
        }
    }

    @Unique
    private static int koil$level(BlockState state) {
        try {
            return state.get(ComposterBlock.LEVEL);
        } catch (Exception ignored) {
            return 0;
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
    private static String koil$key(World world, BlockPos pos, UUID uuid, Hand hand) {
        Identifier worldId = world.getRegistryKey().getValue();
        return worldId + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ":" + uuid + ":" + hand.name();
    }
}
