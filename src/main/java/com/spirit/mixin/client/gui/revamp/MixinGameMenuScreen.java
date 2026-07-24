package com.spirit.mixin.client.gui.revamp;

import com.spirit.koil.api.design.KoilVanillaScreenChrome;
import com.spirit.koil.api.registry.client.ContentWorldTransitionCoordinator;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Environment(EnvType.CLIENT)
@Mixin(GameMenuScreen.class)
public abstract class MixinGameMenuScreen extends Screen {
    protected MixinGameMenuScreen(Text title) {
        super(title);
    }

    @Inject(method = "disconnect", at = @At("HEAD"), cancellable = true)
    private void koil$unloadContentBeforeTitle(CallbackInfo callback) {
        if (ContentWorldTransitionCoordinator.interceptGameMenuLeave(this.client)) {
            callback.cancel();
        }
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void koil$removeVanillaGameMenuTitle(CallbackInfo ci) {
        if (!JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            return;
        }
        for (var child : List.copyOf(this.children())) {
            if (child instanceof TextWidget widget && widget.getMessage().equals(this.title)) {
                this.remove(widget);
            }
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void koil$renderTopBarGameMenuTitle(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean()) {
            KoilVanillaScreenChrome.renderTitle(context, this.textRenderer, Text.literal("Game Menu"), null);
        }
    }
}
