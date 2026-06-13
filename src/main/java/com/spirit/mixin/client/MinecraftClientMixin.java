package com.spirit.mixin.client;

import com.spirit.client.gui.ide.FileExplorerScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static com.spirit.Client.KOIL_UTIL_POPUP_KEY_INT;
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Shadow @Final private Window window;

    @Shadow
    public static MinecraftClient getInstance() {
        return null;
    }

    @Inject(method = "getFramerateLimit", at = @At(value = "HEAD"), cancellable = true)
    public void titleFramerateLimit(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(this.window.getFramerateLimit());
    }

    @Inject(method = "handleInputEvents", at = @At("HEAD"))
    private void toggleKoilPopup(CallbackInfo ci) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (client.currentScreen == null && GLFW.glfwGetKey(this.window.getHandle(), KOIL_UTIL_POPUP_KEY_INT) == GLFW.GLFW_PRESS) {
            if (client == null) {
                return;
            }


            client.setScreen(FileExplorerScreen.openAtLastVisitedPath());
            //ApplicationBar.togglePopup();
        }
    }
}
