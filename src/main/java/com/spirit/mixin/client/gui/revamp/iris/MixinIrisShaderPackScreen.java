package com.spirit.mixin.client.gui.revamp.iris;

import com.spirit.client.gui.shader.ShaderPackMenuScreen;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.gui.screen.ShaderPackScreen", remap = false)
public abstract class MixinIrisShaderPackScreen extends Screen {
    @Shadow(remap = false)
    @Final
    private Screen parent;

    protected MixinIrisShaderPackScreen(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"), cancellable = true, remap = false)
    private void koil$openKoilShaderManager(CallbackInfo ci) {
        if (!koil$useRedesign() || this.client == null) {
            return;
        }
        ci.cancel();
        this.client.setScreen(new ShaderPackMenuScreen(this.parent == null ? this : this.parent));
    }

    private boolean koil$useRedesign() {
        try {
            return JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }
}
