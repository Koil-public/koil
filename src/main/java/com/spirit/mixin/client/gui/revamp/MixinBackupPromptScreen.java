package com.spirit.mixin.client.gui.revamp;

import com.spirit.koil.api.design.DesignLoader;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.MultilineText;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.BackupPromptScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.*;
import java.util.Objects;

import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
@Mixin(BackupPromptScreen.class)
public class MixinBackupPromptScreen extends Screen {
    private static final int KOIL_WARNING_LEFT = 38;
    private static final int KOIL_WARNING_TITLE_Y = 70;
    private static final int KOIL_WARNING_BODY_Y = 90;
    private static final int KOIL_WARNING_LINE_HEIGHT = 12;
    private static final int KOIL_WARNING_FOOTER_HEIGHT = 102;
    private static final int KOIL_WARNING_BUTTON_WIDTH = 150;
    private static final int KOIL_WARNING_BUTTON_HEIGHT = 20;
    private static final int KOIL_WARNING_BUTTON_GAP = 10;
    @Shadow private MultilineText wrappedText;
    @Shadow @Final private Text subtitle;
    @Shadow @Final protected BackupPromptScreen.Callback callback;
    @Shadow private CheckboxWidget eraseCacheCheckbox;
    @Shadow @Final private Screen parent;
    @Shadow @Final private boolean showEraseCacheCheckbox;

    public MixinBackupPromptScreen(Text title) {
        super(title);
    }

    /**
     * @author SpiritXIV
     * @reason fuck it we ball
     */
    @Overwrite
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            int x = KOIL_WARNING_LEFT;
            context.drawText(this.textRenderer, this.title, x, KOIL_WARNING_TITLE_Y, new Color(uiColorBasicTitleText, true).getRGB(), true);
            this.wrappedText.draw(context, x, KOIL_WARNING_BODY_Y, KOIL_WARNING_LINE_HEIGHT, new Color(uiColorBasicDescriptionText, true).getRGB());
            super.render(context, mouseX, mouseY, delta);
        } else {
            this.renderBackground(context);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 50, 16777215);
            this.wrappedText.drawCenterWithShadow(context, this.width / 2, 70);
            super.render(context, mouseX, mouseY, delta);
        }
    }

    /**
     * @author SpiritXIV
     * @reason fuck it we ball
     */
    @Overwrite
    protected void init() {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            super.init();
            int x = KOIL_WARNING_LEFT;
            int wrapWidth = Math.max(120, this.width - (x * 2));
            this.wrappedText = MultilineText.create(this.textRenderer, this.subtitle, wrapWidth);
            int bodyHeight = this.wrappedText.count() * KOIL_WARNING_LINE_HEIGHT;
            int footerTop = Math.max(KOIL_WARNING_BODY_Y + bodyHeight + 16, this.height - KOIL_WARNING_FOOTER_HEIGHT + 16);
            int firstButtonY = Math.min(this.height - 56, footerTop);
            int availableButtonWidth = (KOIL_WARNING_BUTTON_WIDTH * 2) + KOIL_WARNING_BUTTON_GAP;
            boolean stackButtons = this.width < x + availableButtonWidth + 24;
            int firstButtonX = stackButtons ? x : x;
            int secondButtonX = stackButtons ? x : x + KOIL_WARNING_BUTTON_WIDTH + KOIL_WARNING_BUTTON_GAP;
            int secondButtonY = stackButtons ? firstButtonY + KOIL_WARNING_BUTTON_HEIGHT + 4 : firstButtonY;
            this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectWorld.backupJoinConfirmButton"), (button) -> this.callback.proceed(true, this.eraseCacheCheckbox.isChecked())).dimensions(firstButtonX, firstButtonY, KOIL_WARNING_BUTTON_WIDTH, KOIL_WARNING_BUTTON_HEIGHT).build());
            this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectWorld.backupJoinSkipButton"), (button) -> this.callback.proceed(false, this.eraseCacheCheckbox.isChecked())).dimensions(secondButtonX, secondButtonY, KOIL_WARNING_BUTTON_WIDTH, KOIL_WARNING_BUTTON_HEIGHT).build());
            this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, (button) -> {
                assert this.client != null;
                this.client.setScreen(this.parent);
            }).dimensions(x, Math.min(this.height - 28, secondButtonY + KOIL_WARNING_BUTTON_HEIGHT + 8), stackButtons ? KOIL_WARNING_BUTTON_WIDTH : 310, KOIL_WARNING_BUTTON_HEIGHT).build());
            this.eraseCacheCheckbox = new CheckboxWidget(x, Math.max(KOIL_WARNING_BODY_Y + bodyHeight + 4, firstButtonY - 24), Math.min(220, this.width - x - 12), 20, Text.translatable("selectWorld.backupEraseCache"), false);
            if (this.showEraseCacheCheckbox) {
                this.addDrawableChild(this.eraseCacheCheckbox);
            }
        } else {
            super.init();
            this.wrappedText = MultilineText.create(this.textRenderer, this.subtitle, this.width - 50);
            int var10000 = this.wrappedText.count() + 1;
            Objects.requireNonNull(this.textRenderer);
            int i = var10000 * 9;
            this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectWorld.backupJoinConfirmButton"), (button) -> this.callback.proceed(true, this.eraseCacheCheckbox.isChecked())).dimensions(this.width / 2 - 155, 100 + i, 150, 20).build());
            this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectWorld.backupJoinSkipButton"), (button) -> this.callback.proceed(false, this.eraseCacheCheckbox.isChecked())).dimensions(this.width / 2 - 155 + 160, 100 + i, 150, 20).build());
            this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, (button) -> this.client.setScreen(this.parent)).dimensions(this.width / 2 - 155 + 80, 124 + i, 150, 20).build());
            this.eraseCacheCheckbox = new CheckboxWidget(this.width / 2 - 155 + 80, 76 + i, 150, 20, Text.translatable("selectWorld.backupEraseCache"), false);
            if (this.showEraseCacheCheckbox) {
                this.addDrawableChild(this.eraseCacheCheckbox);
            }
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo info) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            MinecraftClient client = MinecraftClient.getInstance();
            context.drawTexture(DesignLoader.getLoadingTexture(), 0, 0, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), 0.0F, 0.0F, 319, 192, 319, 192);

            context.getMatrices().push();
            context.getMatrices().scale(2F, 2F, 1.0F);
            context.drawText(this.textRenderer, Text.literal("[!] Warning."), 20, 8, new Color(uiColorWarningPromptText, true).getRGB(), true);
            context.getMatrices().pop();

            context.fill(0, 6, this.width, this.height - KOIL_WARNING_FOOTER_HEIGHT, new Color(uiColorContentBase, true).getRGB());
            context.fill(0, 8, this.width, 10, new Color(uiColorContentStripeLeft, true).getRGB());
            context.fill(0, this.height - KOIL_WARNING_FOOTER_HEIGHT - 2, this.width, this.height - KOIL_WARNING_FOOTER_HEIGHT - 4, new Color(uiColorContentStripeRight, true).getRGB());
        }
    }
}
