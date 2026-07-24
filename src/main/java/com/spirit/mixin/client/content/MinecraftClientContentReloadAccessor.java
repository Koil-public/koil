package com.spirit.mixin.client.content;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.CompletableFuture;

/** Version-local access to Minecraft's current client resource reload lifecycle. */
@Mixin(MinecraftClient.class)
public interface MinecraftClientContentReloadAccessor {
    @Accessor("resourceReloadFuture")
    CompletableFuture<Void> koil$getContentResourceReloadFuture();

    @Accessor("resourceReloadFuture")
    void koil$setContentResourceReloadFuture(CompletableFuture<Void> future);
}
