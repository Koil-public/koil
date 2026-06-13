package com.spirit.mixin.client;

import com.spirit.client.gui.skin.SkinRuntime;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayerEntity.class)
public abstract class KoilSkinPlayerMixin {
    @Inject(method = "getSkinTexture", at = @At("HEAD"), cancellable = true)
    private void koil$getSkinTexture(CallbackInfoReturnable<Identifier> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && (Object) this == client.player && SkinRuntime.getActiveSkinTexture() != null) {
            cir.setReturnValue(SkinRuntime.getActiveSkinTexture());
        }
    }

    @Inject(method = "getModel", at = @At("HEAD"), cancellable = true)
    private void koil$getModel(CallbackInfoReturnable<String> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && (Object) this == client.player && SkinRuntime.getActiveSkinTexture() != null) {
            cir.setReturnValue(SkinRuntime.isActiveSlim() ? "slim" : "default");
        }
    }
}
