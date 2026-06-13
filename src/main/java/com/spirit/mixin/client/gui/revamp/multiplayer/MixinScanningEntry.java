package com.spirit.mixin.client.gui.revamp.multiplayer;

import com.spirit.mixin.client.gui.revamp.accessor.MultiplayerServerListWidgetAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.LoadingDisplay;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiplayerServerListWidget.ScanningEntry.class)
public abstract class MixinScanningEntry {

    @Shadow @Final private MinecraftClient client;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta, CallbackInfo ci) {
        Text lanScanningText = MultiplayerServerListWidgetAccessor.getLanScanningText();
        int adjustedX = 100000;
        int var10000 = y + entryHeight / 2;
        TextRenderer textRenderer = this.client.textRenderer;
        int i = var10000 - 9 / 2;
        context.drawText(textRenderer, lanScanningText, adjustedX, i, 16777215, false);
        String string = LoadingDisplay.get(Util.getMeasuringTimeMs());
        context.drawText(textRenderer, string, adjustedX + textRenderer.getWidth(lanScanningText), i + 9, 8421504, false);
        ci.cancel();
    }
}