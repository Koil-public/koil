package com.spirit.mixin.client.gui.revamp.option;

import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.screen.option.SkinOptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.entity.PlayerModelPart;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.awt.*;

import static com.spirit.koil.api.design.uiColorVal.*;

@Environment(EnvType.CLIENT)
@Mixin(SkinOptionsScreen.class)
public class MixinSkinOptionsScreen extends GameOptionsScreen {
    public MixinSkinOptionsScreen(Screen parent, GameOptions gameOptions, Text title) {
        super(parent, gameOptions, title);
    }


    /**
     * @author SpiritXIV
     * @reason to add a button
     */
    @Overwrite
    public void init() {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {

            int i = 0;
            PlayerModelPart[] var2 = PlayerModelPart.values();
            int var3 = var2.length;

            for (int var4 = 0; var4 < var3; ++var4) {
                PlayerModelPart playerModelPart = var2[var4];
                this.addDrawableChild(CyclingButtonWidget.onOffBuilder(this.gameOptions.isPlayerModelPartEnabled(playerModelPart)).build(this.width / 2 - 155 + i % 2 * 160, this.height / 6 + 24 * (i >> 1), 150, 20, playerModelPart.getOptionName(), (button, enabled) -> {
                    this.gameOptions.togglePlayerModelPart(playerModelPart, enabled);
                }));
                ++i;
            }

            this.addDrawableChild(this.gameOptions.getMainArm().createWidget(this.gameOptions, this.width / 2 - 155 + i % 2 * 160, this.height / 6 + 24 * (i >> 1), 150));
            ++i;
            if (i % 2 == 1) {
                ++i;
            }

            this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, (button) -> this.client.setScreen(this.parent)).dimensions(this.width / 2 - 100, this.height / 6 + 31 * (i >> 1), 200, 20).build());
        } else {
            int i = 0;
            PlayerModelPart[] var2 = PlayerModelPart.values();
            int var3 = var2.length;

            for(int var4 = 0; var4 < var3; ++var4) {
                PlayerModelPart playerModelPart = var2[var4];
                this.addDrawableChild(CyclingButtonWidget.onOffBuilder(this.gameOptions.isPlayerModelPartEnabled(playerModelPart)).build(this.width / 2 - 155 + i % 2 * 160, this.height / 6 + 24 * (i >> 1), 150, 20, playerModelPart.getOptionName(), (button, enabled) -> {
                    this.gameOptions.togglePlayerModelPart(playerModelPart, enabled);
                }));
                ++i;
            }

            this.addDrawableChild(this.gameOptions.getMainArm().createWidget(this.gameOptions, this.width / 2 - 155 + i % 2 * 160, this.height / 6 + 24 * (i >> 1), 150));
            ++i;
            if (i % 2 == 1) {
                ++i;
            }

            this.addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, (button) -> this.client.setScreen(this.parent)).dimensions(this.width / 2 - 100, this.height / 6 + 24 * (i >> 1), 200, 20).build());
        }
    }

    /**
     * @author SpiritXIV
     * @reason to make it look better
     */
    @Overwrite
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {

            MinecraftClient client = MinecraftClient.getInstance();
            KoilVanillaScreenChrome.renderOptionsShell(context, client, this.width, this.height);


            context.getMatrices().push();
            context.getMatrices().scale(1.5F, 1.5F, 1.0F);
            context.drawText(this.textRenderer, Text.literal("Options"), 25, 3, new Color(uiColorHeaderTitleText, true).getRGB(), true);
            context.getMatrices().pop();
            context.drawText(this.textRenderer, this.title, 37, 18, new Color(uiColorHeaderSubTitleText, true).getRGB(), true);
            super.render(context, mouseX, mouseY, delta);
        } else {
            this.renderBackground(context);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 16777215);
            super.render(context, mouseX, mouseY, delta);
        }
    }
}
