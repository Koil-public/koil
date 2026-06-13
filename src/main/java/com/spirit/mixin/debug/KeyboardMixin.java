package com.spirit.mixin.debug;

import com.spirit.Main;
import com.spirit.koil.api.design.uiColorVal;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.f3.F3Controller;
import com.spirit.koil.api.f3.F3LayoutState;
import com.spirit.koil.api.f3.F3Mode;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.glfw.GLFW;

import static com.spirit.Main.reloadDesign;
import static com.spirit.koil.api.design.DesignLoader.reloadLoadingTexture;

@Mixin(Keyboard.class)
public abstract class KeyboardMixin {
	@Shadow @Final private MinecraftClient client;
	boolean switchF3State;
	long debugCrashStartTime = -1L;

	@Shadow protected abstract void debugLog(String key, Object... args);

	private boolean reloadResources(int key) {
		if (this.debugCrashStartTime > 0L && this.debugCrashStartTime < Util.getMeasuringTimeMs() - 100L) {
			return true;
		} else if (key == 84) {
			uiColorVal.reload();
			reloadDesign();
			reloadLoadingTexture();
			this.debugLog("debug.reload_resourcepacks.message");
			this.client.reloadResources();
			return true;
		} else {
			return false;
		}
	}

	@Inject(at = @At("HEAD"), method = "Lnet/minecraft/client/Keyboard;onKey(JIIII)V", cancellable = true)
	public void onKey(long window, int key, int scancode, int action, int modifiers, CallbackInfo ci) {
		if (window == this.client.getWindow().getHandle()) {
			boolean koilF3Enabled = KoilScreenBackgrounds.uiRedesignEnabled() && !Main.vanillaF3Design() && !F3Controller.vanillaFallback();
			if (key == GLFW.GLFW_KEY_F3 && koilF3Enabled) {
				this.client.options.debugEnabled = false;
				if (action == GLFW.GLFW_RELEASE) {
					ci.cancel();
					return;
				}
			}
			if (key == GLFW.GLFW_KEY_F3 && action == GLFW.GLFW_PRESS && koilF3Enabled) {
				if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
					F3Controller.setOverlayMode(F3Mode.GRAPHS);
				} else if ((modifiers & GLFW.GLFW_MOD_ALT) != 0) {
					F3Controller.setOverlayMode(F3Mode.FULL);
				} else {
					F3Controller.toggleOverlay();
				}
				ci.cancel();
				return;
			}
			if (InputUtil.isKeyPressed(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_KEY_F3) && action == GLFW.GLFW_PRESS && koilF3Enabled) {
				this.client.options.debugEnabled = false;
				if (key == GLFW.GLFW_KEY_M) {
					F3Controller.cycleMode();
					ci.cancel();
					return;
				}
				if (key == GLFW.GLFW_KEY_F) {
					F3LayoutState.toggleFrozen();
					ci.cancel();
					return;
				}
				if (key == GLFW.GLFW_KEY_C) {
					F3Controller.copyTarget();
					ci.cancel();
					return;
				}
				if (key == GLFW.GLFW_KEY_R) {
					F3Controller.writeReport();
					ci.cancel();
					return;
				}
				if (key == GLFW.GLFW_KEY_V) {
					F3Controller.toggleVanillaFallback();
					ci.cancel();
					return;
				}
				if (key == GLFW.GLFW_KEY_UP) {
					F3Controller.scrollOverlay(-3);
					ci.cancel();
					return;
				}
				if (key == GLFW.GLFW_KEY_DOWN) {
					F3Controller.scrollOverlay(3);
					ci.cancel();
					return;
				}
			}
			boolean bl2;
			if (action != 0 && this.client.currentScreen != null) {
				bl2 = InputUtil.isKeyPressed(MinecraftClient.getInstance().getWindow().getHandle(), 292)
						&& this.reloadResources(key);
				this.switchF3State |= bl2;
			}
		}
	}
}
