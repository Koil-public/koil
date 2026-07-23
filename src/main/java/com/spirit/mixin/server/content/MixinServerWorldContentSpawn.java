package com.spirit.mixin.server.content;

import com.spirit.koil.api.registry.ContentVisibilityPolicy;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents new inactive Content item entities from entering a foreign world. */
@Mixin(ServerWorld.class)
public abstract class MixinServerWorldContentSpawn {
    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void koil$rejectInactiveContentItemEntity(
            Entity entity,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (entity instanceof ItemEntity itemEntity
                && !itemEntity.getStack().isEmpty()
                && !ContentVisibilityPolicy.mayCreate(itemEntity.getStack().getItem())) {
            callback.setReturnValue(false);
        }
    }
}
