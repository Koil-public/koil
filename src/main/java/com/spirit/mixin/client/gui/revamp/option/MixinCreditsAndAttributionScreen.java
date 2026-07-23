package com.spirit.mixin.client.gui.revamp.option;

import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.CreditsAndAttributionScreen;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Environment(EnvType.CLIENT)
@Mixin(CreditsAndAttributionScreen.class)
public class MixinCreditsAndAttributionScreen extends Screen {
    protected MixinCreditsAndAttributionScreen(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void koil$removeVanillaCreditsTitle(CallbackInfo ci) {
        if (!JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            return;
        }
        for (Element child : List.copyOf(this.children())) {
            if (child instanceof TextWidget widget && widget.getMessage().equals(this.title)) {
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

            KoilVanillaScreenChrome.renderTitle(context, this.textRenderer, Text.literal("Options"), this.title);
            super.render(context, mouseX, mouseY, delta);
        } else {
            this.renderBackground(context);
            super.render(context, mouseX, mouseY, delta);
        }
    }
}
