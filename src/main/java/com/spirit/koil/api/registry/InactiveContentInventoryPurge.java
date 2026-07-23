package com.spirit.koil.api.registry;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Bounded, allocation-free safety sweep for direct inventory writes that bypass
 * normal insertion APIs. It scans player-owned slots only and never scans chunks.
 */
public final class InactiveContentInventoryPurge {
    private InactiveContentInventoryPurge() {
    }

    public static int purge(ServerPlayerEntity player) {
        if (!ContentVisibilityPolicy.hasActiveWorld() || !ContentVisibilityPolicy.hasInactiveHolders()) {
            return 0;
        }

        int removed = purgePlayerInventory(player.getInventory());
        removed += purgeInventory(player.getEnderChestInventory());
        removed += purgeCursor(player.currentScreenHandler);

        if (removed > 0) {
            player.getInventory().markDirty();
            player.getEnderChestInventory().markDirty();
            player.playerScreenHandler.sendContentUpdates();
            if (player.currentScreenHandler != player.playerScreenHandler) {
                player.currentScreenHandler.sendContentUpdates();
            }
        }
        return removed;
    }

    private static int purgePlayerInventory(PlayerInventory inventory) {
        int removed = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (isInactive(stack)) {
                inventory.setStack(slot, ItemStack.EMPTY);
                removed++;
            }
        }
        return removed;
    }

    private static int purgeInventory(EnderChestInventory inventory) {
        int removed = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (isInactive(stack)) {
                inventory.setStack(slot, ItemStack.EMPTY);
                removed++;
            }
        }
        return removed;
    }

    private static int purgeCursor(ScreenHandler handler) {
        if (isInactive(handler.getCursorStack())) {
            handler.setCursorStack(ItemStack.EMPTY);
            return 1;
        }
        return 0;
    }

    private static boolean isInactive(ItemStack stack) {
        return !stack.isEmpty() && !ContentVisibilityPolicy.shouldExpose(stack.getItem());
    }
}
