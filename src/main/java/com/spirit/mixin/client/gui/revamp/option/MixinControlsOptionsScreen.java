package com.spirit.mixin.client.gui.revamp.option;

import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.ControlsOptionsScreen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.spirit.koil.api.design.uiColorVal.uiColorHeaderSubTitleText;
import static com.spirit.koil.api.design.uiColorVal.uiColorHeaderTitleText;

@Environment(EnvType.CLIENT)
@Mixin(ControlsOptionsScreen.class)
public class MixinControlsOptionsScreen extends GameOptionsScreen {
    public MixinControlsOptionsScreen(Screen parent, GameOptions gameOptions, Text title) {
        super(parent, gameOptions, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void koil$removeOptionsMovedToKeybinds(CallbackInfo ci) {
        String sneak = Text.translatable("key.sneak").getString().toLowerCase(Locale.ROOT);
        String sprint = Text.translatable("key.sprint").getString().toLowerCase(Locale.ROOT);
        String autoJump = Text.translatable("options.autoJump").getString().toLowerCase(Locale.ROOT);
        String operator = Text.translatable("options.operatorItemsTab").getString().toLowerCase(Locale.ROOT);
        List<Element> snapshot = new ArrayList<>(this.children());
        for (Element child : snapshot) {
            if (!(child instanceof ClickableWidget widget)) {
                continue;
            }
            String label = widget.getMessage().getString().toLowerCase(Locale.ROOT);
            if (label.contains(sneak) || label.contains(sprint) || label.contains(autoJump) || label.contains(operator)) {
                this.remove(widget);
            }
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
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 16777215);
            super.render(context, mouseX, mouseY, delta);
        }
    }
}
