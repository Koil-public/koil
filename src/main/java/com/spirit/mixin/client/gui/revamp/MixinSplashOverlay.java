package com.spirit.mixin.client.gui.revamp;

import com.spirit.koil.api.design.DesignLoader;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.SplashOverlay;
import net.minecraft.client.util.Window;
import net.minecraft.resource.ResourceReload;
import net.minecraft.util.math.ColorHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.*;
import java.util.function.IntSupplier;

import static com.spirit.Main.*;
import static com.spirit.koil.api.design.uiColorVal.uiColorSplashLoadingBar;

@Mixin(SplashOverlay.class)
public abstract class MixinSplashOverlay {
    private static final int MOJANG_RED = ColorHelper.Argb.getArgb(255, 239, 50, 61);
    private static final int MONOCHROME_BLACK = ColorHelper.Argb.getArgb(255, 0, 0, 0);
    private static final IntSupplier BRAND_ARGB = () -> MinecraftClient.getInstance().options.getMonochromeLogo().getValue() ? MONOCHROME_BLACK : MOJANG_RED;
    private boolean titleSet = false;

    @Shadow @Final private ResourceReload reload;
    @Shadow @Final private MinecraftClient client;
    private float previousProgress = 0.0f;
    private long lastUpdateTime;
    private int dotCount;

    @Inject(method = "<init>*", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (!titleSet && client != null && client.getWindow() != null) {
                    Window window = client.getWindow();
                    window.setTitle("Loading...");
                    titleSet = true;
                }
            });
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                this.lastUpdateTime = System.currentTimeMillis();
                this.dotCount = 0;
            }
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    public void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) return;
            int width = client.getWindow().getScaledWidth();
            int height = client.getWindow().getScaledHeight();
            float progress = this.reload.getProgress();
            int alpha = (int) (255 * (1.0f - progress));

            if (progress >= 1.0f) {
                alpha = 0;
            }

            context.fillGradient(0, 0, width, height - 5, (alpha << 24) | BRAND_ARGB.getAsInt(), (alpha << 24) | BRAND_ARGB.getAsInt());
            context.drawTexture(DesignLoader.getLoadingTexture(), 0, 0, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), 0.0F, 0.0F, 319, 192, 319, 192);

            client.getTextureManager().bindTexture(LOGO_TEXTURE);
            context.drawTexture(LOGO_TEXTURE, width - 50, height - 50, 0, 0, 35, 35, 35, 35);
            client.getTextureManager().bindTexture(AUTOMATION_TEXTURE);
            context.drawTexture(AUTOMATION_TEXTURE, width - 100, height - 50, 0, 0, 35, 35, 35, 35);
            client.getTextureManager().bindTexture(MOJANG_LOGO);
            context.drawTexture(MOJANG_LOGO, width - 150, height - 50, 0, 0, 35, 35, 35, 35);
            //client.getTextureManager().bindTexture(MOJANG_LOGO);
            //context.drawTexture(MOJANG_LOGO, width - 200, height - 50, 0, 0, 35, 35, 35, 35);

            // Update dot animation
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastUpdateTime >= 500) {
                lastUpdateTime = currentTime;
                dotCount = (dotCount + 1) % 4;
            }

            int loadingImageWidth = 184 + (dotCount == 0 ? 0 : (dotCount == 1 ? 15 : (dotCount == 2 ? 30 : 44)));
            int loadingImageHeight = 35;

            client.getTextureManager().bindTexture(LOADING_TEXT_TEXTURE);
            context.drawTexture(LOADING_TEXT_TEXTURE, 10, height - 30, 0, 0, loadingImageWidth / 2, loadingImageHeight / 2, 228 / 2, loadingImageHeight / 2);
        }
    }

    @Inject(method = "renderProgressBar", at = @At("HEAD"), cancellable = true)
    private void renderProgressBar(DrawContext context, int minX, int minY, int maxX, int maxY, float opacity, CallbackInfo ci) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            float progress = this.reload.getProgress();
            int width = this.client.getWindow().getScaledWidth();
            int height = this.client.getWindow().getScaledHeight();

            float interpolatedProgress = this.previousProgress + (progress - this.previousProgress) * this.client.getTickDelta();
            this.previousProgress = interpolatedProgress;

            int filledWidth = (int) (width * interpolatedProgress);
            int barX = 0;
            int barY = height - 5;
            int barHeight = 5;
            context.fill(barX, barY, barX + filledWidth, barY + barHeight, 255, new Color(uiColorSplashLoadingBar, true).getRGB());

            if (progress >= 1.0f) {
                this.client.setOverlay(null);
            }

            ci.cancel();
        }
    }
}
