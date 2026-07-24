package com.spirit.mixin.client.content;

import com.spirit.koil.api.registry.client.ClientResourceReloadTracker;
import net.minecraft.resource.ReloadableResourceManagerImpl;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourceReload;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Observes all low-level client reloads so Content never overlaps one. */
@Mixin(ReloadableResourceManagerImpl.class)
public abstract class MixinReloadableResourceManagerContentReloadTracker {
    @Inject(method = "reload", at = @At("RETURN"))
    private void koil$trackClientResourceReload(
            Executor prepareExecutor,
            Executor applyExecutor,
            CompletableFuture<Unit> initialStage,
            List<ResourcePack> packs,
            CallbackInfoReturnable<ResourceReload> callback
    ) {
        ClientResourceReloadTracker.observe(
                (ReloadableResourceManagerImpl) (Object) this,
                callback.getReturnValue()
        );
    }
}
