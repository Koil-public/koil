package com.spirit.mixin.client.gui.revamp.multiplayer;

import com.spirit.koil.api.design.DesignLoader;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DirectConnectScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DirectConnectScreen.class)
public abstract class MixinDirectConnectScreen extends Screen {
    @Shadow private TextFieldWidget addressField;
    @Shadow protected abstract void onAddressFieldChanged();
    @Shadow private ButtonWidget selectServerButton;
    @Shadow protected abstract void saveAndClose();
    @Shadow @Final private BooleanConsumer callback;
    @Shadow @Final private ServerInfo serverEntry;

    protected MixinDirectConnectScreen(Text title) {
        super(title);
    }

    /**
     * @author SpiritXIV
     * @reason a :)
     */
    @Overwrite
    protected void init() {
        this.addressField = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, 116, 200, 20, Text.translatable("addServer.enterIp"));
        this.addressField.setMaxLength(128);
        assert this.client != null;
        this.addressField.setText(this.client.options.lastServer);
        this.addressField.setChangedListener((text) -> this.onAddressFieldChanged());
        this.addSelectableChild(this.addressField);
        this.selectServerButton = this.addDrawableChild(ButtonWidget.builder(Text.translatable("selectServer.select"), (button) -> this.saveAndClose()).dimensions(this.width / 2 - 100, this.height / 4 + 76 + 12, 200, 20).build());
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Join from Clipboard"), (button) -> this.joinFromClipboard()).dimensions(this.width / 2 - 100, this.height / 4 + 100 + 12, 200, 20).build());
        this.addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, (button) -> this.callback.accept(false)).dimensions(this.width / 2 - 100, this.height / 4 + 134 + 12, 200, 20).build());
        this.setInitialFocus(this.addressField);
        this.onAddressFieldChanged();
    }

    private void joinFromClipboard() {
        this.serverEntry.address = MinecraftClient.getInstance().keyboard.getClipboard();
        this.callback.accept(true);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo info) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.getTextureManager().bindTexture(DesignLoader.getLoadingTexture());
        context.drawTexture(DesignLoader.getLoadingTexture(), 0, 0, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), 0.0F, 0.0F, 319, 192, 319, 192);
    }
}
