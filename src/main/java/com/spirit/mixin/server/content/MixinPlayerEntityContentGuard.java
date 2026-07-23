package com.spirit.mixin.server.content;

import com.spirit.koil.api.registry.ContentVisibilityPolicy;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Rejects inactive Content through the generic player give path. */
@Mixin(PlayerEntity.class)
public abstract class MixinPlayerEntityContentGuard {
    @Inject(method = "giveItemStack", at = @At("HEAD"), cancellable = true)
    private void koil$rejectInactiveContentGive(
            ItemStack stack,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (!stack.isEmpty() && !ContentVisibilityPolicy.mayCreate(stack.getItem())) {
            callback.setReturnValue(false);
        }
    }
}
