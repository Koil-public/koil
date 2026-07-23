package com.spirit.mixin.server.content;

import com.spirit.koil.api.registry.ContentVisibilityPolicy;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents recovery of inactive saved item entities in a foreign world.
 *
 * <p>The tick hook is intentionally entity-local. It never scans worlds, chunks,
 * or entity collections: a loaded dropped item checks only its own stack during
 * the tick Minecraft was already going to run.</p>
 */
@Mixin(ItemEntity.class)
public abstract class MixinItemEntityContentGuard {
    @Shadow
    public abstract net.minecraft.item.ItemStack getStack();

    @Inject(method = "onPlayerCollision", at = @At("HEAD"), cancellable = true)
    private void koil$rejectInactiveContentPickup(PlayerEntity player, CallbackInfo callback) {
        if (!getStack().isEmpty() && !ContentVisibilityPolicy.mayCreate(getStack().getItem())) {
            callback.cancel();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void koil$discardInactiveContent(CallbackInfo callback) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (!self.getWorld().isClient
                && !getStack().isEmpty()
                && ContentVisibilityPolicy.hasActiveWorld()
                && !ContentVisibilityPolicy.shouldExpose(getStack().getItem())) {
            self.discard();
            callback.cancel();
        }
    }
}
