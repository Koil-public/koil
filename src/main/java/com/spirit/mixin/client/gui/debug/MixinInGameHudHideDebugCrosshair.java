package com.spirit.mixin.client.gui.debug;

import com.spirit.client.gui.debug.F3OverlayRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public abstract class MixinInGameHudHideDebugCrosshair {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void koil$hideVanillaCrosshairForRedesignedF3(DrawContext context, CallbackInfo ci) {
        if (F3OverlayRenderer.shouldHideVanillaCrosshair(MinecraftClient.getInstance())) {
            ci.cancel();
        }
    }
}
