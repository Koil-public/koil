package com.spirit.mixin.client.gui.revamp.realms;

import com.google.gson.JsonElement;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.realms.gui.screen.RealmsMainScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(RealmsMainScreen.class)
public abstract class MixinRealmsMainScreen extends Screen {
    protected MixinRealmsMainScreen(Text title) {
        super(title);
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/realms/gui/screen/RealmsMainScreen;renderBackground(Lnet/minecraft/client/gui/DrawContext;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void koil$renderRealmsBackground(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!koil$isUiRedesignEnabled()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        KoilScreenBackgrounds.render(context, client, this.width, this.height);
        context.fill(0, 0, this.width, this.height, KoilScreenBackgrounds.overlayColor(client));
    }

    private boolean koil$isUiRedesignEnabled() {
        try {
            JsonElement element = JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign");
            return element != null && element.isJsonPrimitive() && element.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }
}
