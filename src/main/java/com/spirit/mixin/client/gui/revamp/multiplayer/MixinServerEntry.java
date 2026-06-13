package com.spirit.mixin.client.gui.revamp.multiplayer;

import com.spirit.client.gui.browser.ContentBrowserListRowRenderer;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.multiplayer.KoilServerAddressMaskAccess;
import com.spirit.koil.api.util.file.image.ExternalImageLoader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerServerListWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.util.Locale;

import static com.spirit.Main.uiImageDirectory;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTextureWithColorVariant;

@Environment(EnvType.CLIENT)
@Mixin(MultiplayerServerListWidget.ServerEntry.class)
public class MixinServerEntry {
    @Unique private static final Identifier KOIL_FALLBACK_SERVER_ICON = loadExternalPngTextureWithColorVariant(uiImageDirectory, "image.png");

    @Shadow @Final private MinecraftClient client;
    @Shadow @Final private ServerInfo server;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void koil$renderAlignedServerRow(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta, CallbackInfo ci) {
        if (!KoilScreenBackgrounds.uiRedesignEnabled()) {
            return;
        }

        ContentBrowserListRowRenderer.renderModMenuStyleItem(context, this.client.textRenderer, koil$serverIcon(), KOIL_FALLBACK_SERVER_ICON, x, y, entryWidth, this.server.name, koil$serverDescription());
        ci.cancel();
    }

    @Unique
    private Identifier koil$serverIcon() {
        byte[] favicon = this.server.getFavicon();
        if (favicon != null && favicon.length > 0) {
            try {
                Identifier icon = ExternalImageLoader.registerDynamicTexture("koil", "server_icon/list_" + koil$sanitizeTextureKey(this.server.address), favicon);
                if (icon != null) {
                    return icon;
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return KOIL_FALLBACK_SERVER_ICON;
    }

    @Unique
    private String koil$serverDescription() {
        String address = koil$displayAddress(this.server.address);
        Text version = this.server.version;
        if (version != null && !version.getString().isBlank()) {
            return address + "  |  " + version.getString();
        }
        if (this.server.label != null && !this.server.label.getString().isBlank()) {
            return address + "  |  " + this.server.label.getString();
        }
        return address;
    }

    @Unique
    private String koil$displayAddress(String address) {
        if (this.client.currentScreen instanceof KoilServerAddressMaskAccess maskAccess) {
            return maskAccess.koil$displayServerAddress(address);
        }
        return address == null || address.isBlank() ? "No address" : address;
    }

    @Unique
    private String koil$sanitizeTextureKey(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }
}
