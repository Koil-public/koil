package com.spirit.mixin.server.content;

import com.spirit.koil.api.registry.InactiveContentInventoryPurge;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bounded final safety sweep for direct-list writes made by cheat-mode mods. */
@Mixin(ServerPlayerEntity.class)
public abstract class MixinServerPlayerEntityContentPurge {
    @Inject(method = "tick", at = @At("TAIL"))
    private void koil$purgeInactiveContent(CallbackInfo callback) {
        InactiveContentInventoryPurge.purge((ServerPlayerEntity) (Object) this);
    }
}
