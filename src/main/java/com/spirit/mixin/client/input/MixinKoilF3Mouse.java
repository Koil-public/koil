package com.spirit.mixin.client.input;

import com.spirit.client.gui.main.KoilUpdateToast;
import com.spirit.koil.api.f3.F3Controller;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class MixinKoilF3Mouse {
    @Shadow private double x;
    @Shadow private double y;

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void koil$scrollF3Overlay(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (F3Controller.handleOverlayMouseScroll(vertical)) {
            ci.cancel();
        }
    }

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void koil$clickUpdateToast(long window, int button, int action, int mods, CallbackInfo ci) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || action != GLFW.GLFW_PRESS) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null || client.getWindow().getHandle() != window) {
            return;
        }
        double scaledX = this.x * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
        double scaledY = this.y * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
        if (KoilUpdateToast.handleClick(client, scaledX, scaledY)) {
            ci.cancel();
        }
    }
}
