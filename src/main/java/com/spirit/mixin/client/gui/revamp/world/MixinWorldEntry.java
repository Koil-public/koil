package com.spirit.mixin.client.gui.revamp.world;

import com.spirit.client.gui.browser.ContentBrowserListRowRenderer;
import com.spirit.koil.api.design.KoilScreenBackgrounds;
import com.spirit.koil.api.util.file.image.ExternalImageLoader;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.io.IOException;

import static com.spirit.Main.uiImageDirectory;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTextureWithColorVariant;

@Environment(EnvType.CLIENT)
@Mixin(WorldListWidget.WorldEntry.class)
public abstract class MixinWorldEntry {
    @Unique private static final Identifier KOIL_FALLBACK_WORLD_ICON = loadExternalPngTextureWithColorVariant(uiImageDirectory, "image.png");

    @Shadow @Final private LevelSummary level;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void koil$renderWorldRow(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null
                || !(client.currentScreen instanceof SelectWorldScreen)
                || !KoilScreenBackgrounds.uiRedesignEnabled()) {
            return;
        }

        String title = level.getDisplayName().isBlank() ? level.getName() : level.getDisplayName();
        Text details = level.getDetails();
        String detailsText = details == null ? "" : details.getString();
        String subLabel = detailsText.isBlank() ? level.getName() : level.getName() + "  |  " + detailsText;
        ContentBrowserListRowRenderer.renderModMenuStyleItem(context, client.textRenderer, koil$resolveWorldIcon(), KOIL_FALLBACK_WORLD_ICON, x, y, entryWidth, title, subLabel);

        ci.cancel();
    }

    @Unique
    private Identifier koil$resolveWorldIcon() {
        try {
            File iconFile = this.level.getIconPath().toFile();
            if (!iconFile.exists()) {
                return KOIL_FALLBACK_WORLD_ICON;
            }
            Identifier icon = ExternalImageLoader.registerDynamicTexture("koil", "world_icon/list_" + koil$sanitizeTextureKey(this.level.getName()), iconFile);
            return icon != null ? icon : KOIL_FALLBACK_WORLD_ICON;
        } catch (IOException | RuntimeException ignored) {
            return KOIL_FALLBACK_WORLD_ICON;
        }
    }

    @Unique
    private String koil$sanitizeTextureKey(String value) {
        if (value == null || value.isBlank()) {
            return "world";
        }
        return value.toLowerCase().replaceAll("[^a-z0-9_./-]", "_");
    }
}
