package com.spirit.mixin.client.content;

import com.spirit.koil.api.registry.client.ContentWorldTransitionCoordinator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.server.integrated.IntegratedServerLoader;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preloads selected-world Content assets before integrated server startup. */
@Mixin(IntegratedServerLoader.class)
public abstract class MixinIntegratedServerLoaderContentPreload {
    @Shadow @Final private MinecraftClient client;

    @Inject(
            method = "start(Lnet/minecraft/client/gui/screen/Screen;Ljava/lang/String;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void koil$preloadSelectedWorldContent(
            Screen parent,
            String worldFolder,
            CallbackInfo callback
    ) {
        if (ContentWorldTransitionCoordinator.interceptWorldStart(
                this.client,
                (IntegratedServerLoader) (Object) this,
                parent,
                worldFolder
        )) {
            callback.cancel();
        }
    }
}
