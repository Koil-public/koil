package com.spirit.mixin.client.gui.revamp;

import com.spirit.koil.api.design.DesignLoader;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.MultilineText;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.*;
import java.util.List;

import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
@Mixin(ConfirmScreen.class)
public abstract class MixinConfirmScreen extends Screen {
    private static final int KOIL_WARNING_LEFT = 38;
    private static final int KOIL_WARNING_TITLE_Y = 70;
    private static final int KOIL_WARNING_BODY_Y = 90;
    private static final int KOIL_WARNING_LINE_HEIGHT = 12;
    private static final int KOIL_WARNING_FOOTER_HEIGHT = 102;
    private static final int KOIL_WARNING_BUTTON_WIDTH = 150;
    private static final int KOIL_WARNING_BUTTON_HEIGHT = 20;
    private static final int KOIL_WARNING_BUTTON_GAP = 10;
    @Shadow private MultilineText messageSplit;
    @Shadow @Final private List<ButtonWidget> buttons;
    @Shadow @Final private Text message;
    @Shadow protected Text yesText;
    @Shadow protected Text noText;
    @Shadow @Final protected BooleanConsumer callback;
    @Shadow protected abstract int getMessageY();
    @Shadow protected abstract void addButton(ButtonWidget button);
    @Shadow protected abstract int getMessagesHeight();
    @Shadow protected abstract int getTitleY();

    public MixinConfirmScreen(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    protected void onInit(CallbackInfo ci) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            int wrapWidth = Math.max(120, this.width - (KOIL_WARNING_LEFT * 2));
            this.messageSplit = MultilineText.create(this.textRenderer, this.message, wrapWidth);
            int bodyHeight = this.messageSplit.count() * KOIL_WARNING_LINE_HEIGHT;
            int buttonY = Math.min(this.height - 56, Math.max(KOIL_WARNING_BODY_Y + bodyHeight + 18, this.height - KOIL_WARNING_FOOTER_HEIGHT + 16));
            boolean stackButtons = this.width < KOIL_WARNING_LEFT + (KOIL_WARNING_BUTTON_WIDTH * 2) + KOIL_WARNING_BUTTON_GAP + 24;
            int yesX = KOIL_WARNING_LEFT;
            int noX = stackButtons ? KOIL_WARNING_LEFT : KOIL_WARNING_LEFT + KOIL_WARNING_BUTTON_WIDTH + KOIL_WARNING_BUTTON_GAP;
            int noY = stackButtons ? buttonY + KOIL_WARNING_BUTTON_HEIGHT + 4 : buttonY;

            this.addButton(ButtonWidget.builder(this.yesText, (button) -> this.callback.accept(true))
                    .dimensions(yesX, buttonY, KOIL_WARNING_BUTTON_WIDTH, KOIL_WARNING_BUTTON_HEIGHT).build());
            this.addButton(ButtonWidget.builder(this.noText, (button) -> this.callback.accept(false))
                    .dimensions(noX, noY, KOIL_WARNING_BUTTON_WIDTH, KOIL_WARNING_BUTTON_HEIGHT).build());
        }
    }

    /**
     * @author SpiritXIV
     * @reason no dup text
     */
    @Overwrite
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            renderKoilWarningShell(context);
            context.drawText(this.textRenderer, this.title, KOIL_WARNING_LEFT, KOIL_WARNING_TITLE_Y, new Color(uiColorBasicTitleText, true).getRGB(), true);
            this.messageSplit.draw(context, KOIL_WARNING_LEFT, KOIL_WARNING_BODY_Y, KOIL_WARNING_LINE_HEIGHT, new Color(uiColorBasicDescriptionText, true).getRGB());
            super.render(context, mouseX, mouseY, delta);
        } else {
            this.renderBackground(context);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.getTitleY(), 16777215);
            this.messageSplit.drawCenterWithShadow(context, this.width / 2, this.getMessageY());
            super.render(context, mouseX, mouseY, delta);
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    public void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo info) {
        // The overwrite above owns the prompt shell. Rendering it here as well doubles the dark background.
    }

    private void renderKoilWarningShell(DrawContext context) {
        boolean inWorld = client != null && client.world != null;
        if (!inWorld) {
            this.renderBackground(context);
            context.drawTexture(DesignLoader.getLoadingTexture(), 0, 0, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), 0.0F, 0.0F, 319, 192, 319, 192);
        }

        context.getMatrices().push();
        context.getMatrices().scale(2F, 2F, 1.0F);
        context.drawText(this.textRenderer, Text.literal("[!] Warning."), 20, 8, new Color(uiColorWarningPromptText, true).getRGB(), true);
        context.getMatrices().pop();

        context.fill(0, 6, this.width, this.height - KOIL_WARNING_FOOTER_HEIGHT, new Color(uiColorContentBase, true).getRGB());
        context.fill(0, 8, this.width, 10, new Color(uiColorContentStripeLeft, true).getRGB());
        context.fill(0, this.height - KOIL_WARNING_FOOTER_HEIGHT - 2, this.width, this.height - KOIL_WARNING_FOOTER_HEIGHT - 4, new Color(uiColorContentStripeRight, true).getRGB());
    }

    /**
     * @author SpiritXIV
     * @reason no dup buttons
     */
    @Overwrite
    protected void addButtons(int y) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {

        } else {
            this.addButton(ButtonWidget.builder(this.yesText, (button) -> this.callback.accept(true)).dimensions(this.width / 2 - 155, y, 150, 20).build());
            this.addButton(ButtonWidget.builder(this.noText, (button) -> this.callback.accept(false)).dimensions(this.width / 2 - 155 + 160, y, 150, 20).build());
        }
    }
}
