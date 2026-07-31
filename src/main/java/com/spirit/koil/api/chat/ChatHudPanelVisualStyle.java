package com.spirit.koil.api.chat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Shared visual contract for panels that should read as part of vanilla chat.
 * Placement and sizing remain owned by {@link ChatHudPanelRegistry}.
 */
public final class ChatHudPanelVisualStyle {
    private ChatHudPanelVisualStyle() {
    }

    public static int background(MinecraftClient client) {
        if (client == null || client.options == null) {
            return 0x80000000;
        }
        double opacity = client.options.getTextBackgroundOpacity().getValue();
        int alpha = Math.max(0, Math.min(255, (int) Math.round(255.0D * opacity)));
        return alpha << 24;
    }

    public static int buttonBackground(MinecraftClient client) {
        int background = background(client);
        int alpha = Math.max(72, Math.min(230, alpha(background) + 28));
        return withAlpha(0x00181818, alpha);
    }

    public static int subtleDivider(MinecraftClient client) {
        int alpha = Math.max(32, Math.min(112, alpha(background(client)) + 24));
        return withAlpha(0x00FFFFFF, alpha);
    }

    public static int messageBar(int color, MinecraftClient client) {
        int alpha = Math.max(112, Math.min(255, alpha(background(client)) + 64));
        return withAlpha(color, alpha);
    }

    public static void drawSurface(
            DrawContext drawContext,
            ChatHudPanelBounds bounds,
            MinecraftClient client,
            int messageBarColor
    ) {
        drawContext.fill(
                bounds.x(),
                bounds.y(),
                bounds.x() + bounds.width(),
                bounds.y() + bounds.height(),
                background(client)
        );
        if ((messageBarColor & 0x00FFFFFF) != 0) {
            drawContext.fill(
                    bounds.x(),
                    bounds.y(),
                    bounds.x() + 2,
                    bounds.y() + bounds.height(),
                    messageBar(messageBarColor, client)
            );
        }
    }

    public static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    public static int alpha(int color) {
        return color >>> 24;
    }
}
