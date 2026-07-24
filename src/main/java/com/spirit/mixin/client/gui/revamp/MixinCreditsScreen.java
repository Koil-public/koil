package com.spirit.mixin.client.gui.revamp;

import com.spirit.Main;
import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.CreditsScreen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(CreditsScreen.class)
public class MixinCreditsScreen {
    @Unique
    private static final int KOIL_CREDITS_WIDTH = 180;
    @Unique
    private static final int KOIL_LOGO_SIZE = 64;
    @Unique
    private static final int KOIL_LINE_HEIGHT = 12;
    @Unique
    private static final Text KOIL_SEPARATOR = Text.literal("============").formatted(Formatting.WHITE);

    @Shadow
    private float time;

    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void koil$renderCreditsBackground(DrawContext context, CallbackInfo ci) {
        if (!JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        KoilVanillaScreenChrome.renderCreditsShell(context, client, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());
        ci.cancel();
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;enableBlend()V",
                    shift = At.Shift.BEFORE
            )
    )
    private void koil$renderTeamCredits(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null) {
            return;
        }
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        int columnLeft = koil$creditsColumnLeft(screenWidth + 20);
        int columnCenter = columnLeft + KOIL_CREDITS_WIDTH / 2;
        int contentY = screenHeight + 50;

        context.getMatrices().push();
        context.getMatrices().translate(0.0F, -this.time, 0.0F);
        context.drawTexture(
                Main.LOGO_TEXTURE,
                columnCenter - KOIL_LOGO_SIZE / 2,
                contentY,
                0,
                0,
                KOIL_LOGO_SIZE,
                KOIL_LOGO_SIZE,
                KOIL_LOGO_SIZE,
                KOIL_LOGO_SIZE
        );

        int textY = contentY + 101;
        textY = koil$drawCenteredCredit(context, client.textRenderer, KOIL_SEPARATOR, columnCenter, textY);
        textY = koil$drawCenteredCredit(context, client.textRenderer, Text.literal("Koil Mod Team").formatted(Formatting.YELLOW), columnCenter, textY);
        textY = koil$drawCenteredCredit(context, client.textRenderer, KOIL_SEPARATOR, columnCenter, textY);
        textY += KOIL_LINE_HEIGHT * 2;
        textY = koil$drawCredit(context, client.textRenderer, Text.literal("Studio Head of Koil").formatted(Formatting.GRAY), columnLeft, textY);
        textY = koil$drawCredit(context, client.textRenderer, Text.literal("SpiritXIV").formatted(Formatting.WHITE), columnLeft, textY);
        textY += KOIL_LINE_HEIGHT * 2;
        textY = koil$drawCredit(context, client.textRenderer, Text.literal("Backend Developer").formatted(Formatting.GRAY), columnLeft, textY);
        textY = koil$drawCredit(context, client.textRenderer, Text.literal("eeverest").formatted(Formatting.WHITE), columnLeft, textY);
        textY += KOIL_LINE_HEIGHT * 2;
        textY = koil$drawCredit(context, client.textRenderer, Text.literal("Asset Artist:").formatted(Formatting.GRAY), columnLeft, textY);
        textY = koil$drawCredit(context, client.textRenderer, Text.literal("Computer User").formatted(Formatting.WHITE), columnLeft, textY);
        textY += KOIL_LINE_HEIGHT * 2;
        textY = koil$drawCredit(context, client.textRenderer, Text.literal("Music and Sound Design Artist:").formatted(Formatting.GRAY), columnLeft, textY);
        textY = koil$drawCredit(context, client.textRenderer, Text.literal("Bashful").formatted(Formatting.WHITE), columnLeft, textY);
        textY += KOIL_LINE_HEIGHT * 2;
        textY = koil$drawCredit(context, client.textRenderer, Text.literal("Additional Help and Support from:").formatted(Formatting.GRAY), columnLeft, textY);
        textY = koil$drawCredit(context, client.textRenderer, Text.literal("KingZhara").formatted(Formatting.WHITE), columnLeft, textY);
        textY += KOIL_LINE_HEIGHT * 2;
        koil$drawCenteredCredit(context, client.textRenderer, KOIL_SEPARATOR, columnCenter, textY);
        context.getMatrices().pop();
    }

    @Unique
    private static int koil$creditsColumnLeft(int screenWidth) {
        int vanillaLeft = screenWidth / 2 - 128;
        int vanillaRight = screenWidth / 2 + 128;
        int margin = 12;
        if (screenWidth - vanillaRight >= KOIL_CREDITS_WIDTH + margin * 2) {
            return vanillaRight + margin;
        }
        if (vanillaLeft >= KOIL_CREDITS_WIDTH + margin * 2) {
            return vanillaLeft - margin - KOIL_CREDITS_WIDTH;
        }
        return Math.max(8, Math.min(screenWidth - KOIL_CREDITS_WIDTH - 8, screenWidth / 2 + 72));
    }

    @Unique
    private static int koil$drawCenteredCredit(DrawContext context, TextRenderer renderer, Text text, int centerX, int y) {
        context.drawCenteredTextWithShadow(renderer, text, centerX, y, 0xFFFFFF);
        return y + KOIL_LINE_HEIGHT;
    }

    @Unique
    private static int koil$drawCredit(DrawContext context, TextRenderer renderer, Text text, int x, int y) {
        context.drawTextWithShadow(renderer, text, x, y, 0xFFFFFF);
        return y + KOIL_LINE_HEIGHT;
    }
}
