package com.spirit.mixin.client.gui.revamp.option;

import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.widget.OptionListWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.awt.*;

import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
@Mixin(GameOptionsScreen.class)
public class MixinGameOptionsScreen extends Screen {

    protected MixinGameOptionsScreen(Text title) {
        super(title);
    }

    /**
     * @author SpiritXIV
     * @reason to make it look better
     */
    @Overwrite
    public void render(DrawContext context, OptionListWidget optionButtons, int mouseX, int mouseY, float tickDelta) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            MinecraftClient client = MinecraftClient.getInstance();
            KoilVanillaScreenChrome.renderOptionsShell(context, client, this.width, this.height);


            context.getMatrices().push();
            context.getMatrices().scale(1.5F, 1.5F, 1.0F);
            context.drawText(this.textRenderer, Text.literal("Options"), 25, 3, new Color(uiColorHeaderTitleText, true).getRGB(), true);
            context.getMatrices().pop();
            context.drawText(this.textRenderer, this.title, 37, 18, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
            optionButtons.render(context, mouseX, mouseY, tickDelta);

            super.render(context, mouseX, mouseY, tickDelta);
        } else {
            this.renderBackground(context);
            optionButtons.render(context, mouseX, mouseY, tickDelta);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 16777215);
            super.render(context, mouseX, mouseY, tickDelta);
        }
    }
}
