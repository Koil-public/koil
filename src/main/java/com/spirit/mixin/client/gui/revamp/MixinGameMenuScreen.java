package com.spirit.mixin.client.gui.revamp;

import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(GameMenuScreen.class)
public class MixinGameMenuScreen {
    @Unique
    private boolean koil$titleRendered;

    @Inject(method = "render", at = @At("HEAD"))
    private void koil$beginGameMenuRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        this.koil$titleRendered = false;
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawCenteredTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V"
            ),
            require = 0
    )
    private void koil$renderTopBarGameMenuTitle(DrawContext context, TextRenderer textRenderer, Text text, int centerX, int y, int color) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            KoilVanillaScreenChrome.renderTitle(context, textRenderer, Text.literal("Game Menu"), null);
            this.koil$titleRendered = true;
            return;
        }
        context.drawCenteredTextWithShadow(textRenderer, text, centerX, y, color);
    }

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)I"
            ),
            require = 0
    )
    private int koil$suppressVanillaTopTitle(DrawContext context, TextRenderer textRenderer, Text text, int x, int y, int color) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean() && y <= 32) {
            if (!this.koil$titleRendered) {
                KoilVanillaScreenChrome.renderTitle(context, textRenderer, Text.literal("Game Menu"), null);
                this.koil$titleRendered = true;
            }
            return textRenderer.getWidth(text);
        }
        return context.drawTextWithShadow(textRenderer, text, x, y, color);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void koil$renderFallbackTopBarGameMenuTitle(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean() && !this.koil$titleRendered) {
            TextRenderer textRenderer = net.minecraft.client.MinecraftClient.getInstance().textRenderer;
            KoilVanillaScreenChrome.renderTitle(context, textRenderer, Text.literal("Game Menu"), null);
            this.koil$titleRendered = true;
        }
    }
}
