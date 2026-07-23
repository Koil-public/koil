package com.spirit.mixin.server.content;

import com.spirit.koil.api.registry.ContentVisibilityPolicy;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rejects inactive Content through normal player-inventory insertion paths. */
@Mixin(PlayerInventory.class)
public abstract class MixinPlayerInventoryContentGuard {
    @Inject(
            method = "insertStack(Lnet/minecraft/item/ItemStack;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void koil$rejectInactiveContentInsert(
            ItemStack stack,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (!stack.isEmpty() && !ContentVisibilityPolicy.mayCreate(stack.getItem())) {
            callback.setReturnValue(false);
        }
    }

    @Inject(
            method = "insertStack(ILnet/minecraft/item/ItemStack;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void koil$rejectInactiveContentSlotInsert(
            int slot,
            ItemStack stack,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (!stack.isEmpty() && !ContentVisibilityPolicy.mayCreate(stack.getItem())) {
            callback.setReturnValue(false);
        }
    }
}
