package com.spirit.koil.api.design;

import com.google.gson.JsonElement;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import java.awt.*;

import static com.spirit.koil.api.design.uiColorVal.uiColorBackgroundOverlay;

public final class KoilScreenBackgrounds {
    private static final Identifier VANILLA_DIRT_BACKGROUND = new Identifier("textures/gui/options_background.png");

    private KoilScreenBackgrounds() {
    }

    public static void render(DrawContext context, MinecraftClient client, int width, int height) {
        if (!canRender(client)) {
            return;
        }
        renderForced(context, client, width, height);
    }

    public static void renderForced(DrawContext context, MinecraftClient client, int width, int height) {
        if (client == null) {
            return;
        }
        if (!uiRedesignEnabled()) {
            renderVanillaDirt(context, width, height);
            return;
        }
        context.drawTexture(DesignLoader.getLoadingTexture(), 0, 0, client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight(), 0.0F, 0.0F, 319, 192, 319, 192);
    }

    public static int overlayColor(MinecraftClient client) {
        if (client == null) {
            return 0x00000000;
        }
        if (!uiRedesignEnabled()) {
            return 0x00000000;
        }
        return new Color(uiColorBackgroundOverlay, true).getRGB();
    }

    public static boolean uiRedesignEnabled() {
        try {
            JsonElement element = JSONFileEditor.getValueFromJson("./koil/sys/config.json", "uiRedesign");
            return element != null && element.isJsonPrimitive() && element.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean canRender(MinecraftClient client) {
        return client != null && client.world == null;
    }

    private static void renderVanillaDirt(DrawContext context, int width, int height) {
        for (int x = 0; x < width; x += 32) {
            for (int y = 0; y < height; y += 32) {
                int tileWidth = Math.min(32, width - x);
                int tileHeight = Math.min(32, height - y);
                context.drawTexture(VANILLA_DIRT_BACKGROUND, x, y, 0, 0, tileWidth, tileHeight, 32, 32);
            }
        }
        context.fill(0, 0, width, height, 0xB8000000);
    }
}
