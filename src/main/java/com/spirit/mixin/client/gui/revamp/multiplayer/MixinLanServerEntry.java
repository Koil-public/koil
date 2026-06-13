package com.spirit.mixin.client.gui.revamp.multiplayer;

import com.spirit.client.gui.browser.ContentBrowserListRowRenderer;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.multiplayer.KoilServerAddressMaskAccess;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.network.LanServerInfo;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.spirit.Main.uiImageDirectory;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTextureWithColorVariant;

@Environment(EnvType.CLIENT)
@Mixin(MultiplayerServerListWidget.LanServerEntry.class)
public class MixinLanServerEntry {
    @Unique private static final Identifier KOIL_FALLBACK_SERVER_ICON = loadExternalPngTextureWithColorVariant(uiImageDirectory, "image.png");

    @Shadow @Final protected MinecraftClient client;
    @Shadow @Final protected LanServerInfo server;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void koil$renderAlignedLanServerRow(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta, CallbackInfo ci) {
        if (!KoilScreenBackgrounds.uiRedesignEnabled()) {
            return;
        }

        ContentBrowserListRowRenderer.renderModMenuStyleItem(context, this.client.textRenderer, koil$lanServerIcon(), KOIL_FALLBACK_SERVER_ICON, x, y, entryWidth, koil$lanTitle(), koil$lanDescription());
        ci.cancel();
    }

    @Unique
    private Identifier koil$lanServerIcon() {
        return KOIL_FALLBACK_SERVER_ICON;
    }

    @Unique
    private String koil$lanTitle() {
        String motd = this.server.getMotd();
        return motd == null || motd.isBlank() ? "LAN Server" : motd;
    }

    @Unique
    private String koil$lanDescription() {
        String address = this.server.getAddressPort();
        if (this.client.currentScreen instanceof KoilServerAddressMaskAccess maskAccess) {
            return maskAccess.koil$displayServerAddress(address);
        }
        return address == null || address.isBlank() ? "Local network" : address;
    }
}
