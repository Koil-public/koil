package com.spirit.koil.api.util.file.image;

import com.spirit.koil.api.util.file.media.image.ImageTexture;
import com.spirit.koil.api.util.file.media.image.ImageTextureService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import java.io.IOException;

public class ExternalImageRenderer {
    private static MinecraftClient getReadyClient() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.getTextureManager() != null ? client : null;
    }

    public static Identifier registerImage(String path) {
        try {
            ImageTexture texture = ImageTextureService.loadTexture(new java.io.File(path), "koil", "external_image/" + path);
            return texture == null ? null : texture.textureId();
        } catch (IOException e) {
            return null;
        }
    }

    public static Identifier registerScaledImage(String path, int maxWidth, int maxHeight) {
        try {
            ImageTexture texture = ImageTextureService.loadScaledTexture(new java.io.File(path), "koil", "external_image/" + path, maxWidth, maxHeight);
            return texture == null ? null : texture.textureId();
        } catch (IOException e) {
            return null;
        }
    }

    public static void drawImage(DrawContext context, Identifier textureId, int x, int y, int width, int height) {
        MinecraftClient client = getReadyClient();
        if (textureId != null && client != null) {
            client.getTextureManager().bindTexture(textureId);
            context.drawTexture(textureId, x, y, 0, 0, width, height, width, height);
        }
    }
}
