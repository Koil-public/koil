package com.spirit.mixin.client.gui.revamp;

import com.spirit.koil.api.util.file.json.JSONFileEditor;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.awt.*;

import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
@Mixin(ConfirmLinkScreen.class)
public abstract class MixinConfirmLinkScreen extends ConfirmScreen {
    @Shadow @Final private static Text COPY;
    @Shadow public abstract void copyToClipboard();
    @Shadow @Final private boolean drawWarning;
    @Shadow @Final private static Text WARNING;
    public MixinConfirmLinkScreen(BooleanConsumer callback, Text title, Text message) {
        super(callback, title, message);
    }

    /**
     * @author SpiritXIV
     * @reason im so tired
     */
    @Overwrite
    protected void addButtons(int y) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            this.addDrawableChild(ButtonWidget.builder(this.COPY, (button) -> {
                this.copyToClipboard();
                this.callback.accept(false);
            }).dimensions(38, 155, 230, 20).build());
        } else {
            this.addDrawableChild(ButtonWidget.builder(this.yesText, (button) -> {
                this.callback.accept(true);
            }).dimensions(this.width / 2 - 50 - 105, y, 100, 20).build());
            this.addDrawableChild(ButtonWidget.builder(COPY, (button) -> {
                this.copyToClipboard();
                this.callback.accept(false);
            }).dimensions(this.width / 2 - 50, y, 100, 20).build());
            this.addDrawableChild(ButtonWidget.builder(this.noText, (button) -> {
                this.callback.accept(false);
            }).dimensions(this.width / 2 - 50 + 105, y, 100, 20).build());
        }
    }

    /**
     * @author SpiritXIV
     * @reason no dup text
     */
    @Overwrite
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            super.render(context, mouseX, mouseY, delta);
            if (this.drawWarning) {
                context.drawText(this.textRenderer, WARNING, 38, 110, new Color(uiColorBasicDescriptionText, true).getRGB(), true);
            }
        } else {
            super.render(context, mouseX, mouseY, delta);
            if (this.drawWarning) {
                context.drawCenteredTextWithShadow(this.textRenderer, WARNING, this.width / 2, 110, 16764108);
            }
        }
    }

}
