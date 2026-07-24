package com.spirit.koil.api.registry.client;

import com.spirit.mixin.client.content.MinecraftClientContentReloadAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ReloadableResourceManagerImpl;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.ResourceReload;
import net.minecraft.util.Unit;
import net.minecraft.util.Util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Version-local, non-modal client resource reload used only to unload an empty
 * Content pack set after disconnect. Active-world loads retain Minecraft's
 * serialized reload path so startup/world-entry systems cannot replace a
 * resource manager while its models are still baking.
 */
final class ContentClientResourceReload {
    private ContentClientResourceReload() {
    }

    static CompletableFuture<Void> start(MinecraftClient client) {
        if (!(client.getResourceManager() instanceof ReloadableResourceManagerImpl resourceManager)) {
            return client.reloadResources();
        }
        ResourcePackManager packManager = client.getResourcePackManager();
        ResourceReload reload;
        try {
            reload = resourceManager.reload(
                    Util.getMainWorkerExecutor(),
                    client,
                    CompletableFuture.completedFuture(Unit.INSTANCE),
                    packManager.createResourcePacks()
            );
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        CompletableFuture<Void> base = reload.whenComplete().thenRunAsync(() -> {
            if (client.worldRenderer != null) {
                client.worldRenderer.reload();
            }
        }, client);
        MinecraftClientContentReloadAccessor accessor =
                (MinecraftClientContentReloadAccessor) (Object) client;
        accessor.koil$setContentResourceReloadFuture(base);
        return base.handleAsync((ignored, failure) -> {
            if (accessor.koil$getContentResourceReloadFuture() == base) {
                accessor.koil$setContentResourceReloadFuture(null);
            }
            if (failure != null) {
                throw new CompletionException(failure);
            }
            return null;
        }, client);
    }
}
