package com.spirit.koil.api.util.file.media;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Locale;

public final class ManagedFrameTexture implements AutoCloseable {
    private final Identifier textureId;
    private final NativeImageBackedTexture texture;
    private final int width;
    private final int height;
    private boolean closed;

    public ManagedFrameTexture(String namespace, String uniqueKey, int width, int height) throws IOException {
        MinecraftClient client = readyClient();
        if (client == null) {
            throw new IOException("Minecraft texture manager is not available.");
        }
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.textureId = new Identifier(namespace, sanitizeKey(uniqueKey));
        this.texture = new NativeImageBackedTexture(this.width, this.height, true);
        client.getTextureManager().registerTexture(this.textureId, this.texture);
    }

    public Identifier textureId() {
        return this.textureId;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public void updateRgbaFrame(byte[] rgbaBytes) {
        if (this.closed || rgbaBytes == null || rgbaBytes.length < this.width * this.height * 4) {
            return;
        }
        NativeImage image = this.texture.getImage();
        if (image == null) {
            return;
        }

        int index = 0;
        for (int y = 0; y < this.height; y++) {
            for (int x = 0; x < this.width; x++) {
                int red = rgbaBytes[index++] & 0xFF;
                int green = rgbaBytes[index++] & 0xFF;
                int blue = rgbaBytes[index++] & 0xFF;
                int alpha = rgbaBytes[index++] & 0xFF;
                int abgr = (alpha << 24) | (blue << 16) | (green << 8) | red;
                image.setColor(x, y, abgr);
            }
        }
        this.texture.upload();
    }

    public void updateBufferedImage(BufferedImage frame) {
        if (this.closed || frame == null) {
            return;
        }
        NativeImage image = this.texture.getImage();
        if (image == null) {
            return;
        }

        for (int y = 0; y < this.height; y++) {
            for (int x = 0; x < this.width; x++) {
                int argb = frame.getRGB(Math.min(x, frame.getWidth() - 1), Math.min(y, frame.getHeight() - 1));
                int alpha = (argb >> 24) & 0xFF;
                int red = (argb >> 16) & 0xFF;
                int green = (argb >> 8) & 0xFF;
                int blue = argb & 0xFF;
                int abgr = (alpha << 24) | (blue << 16) | (green << 8) | red;
                image.setColor(x, y, abgr);
            }
        }
        this.texture.upload();
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        MinecraftClient client = readyClient();
        if (client != null) {
            client.getTextureManager().destroyTexture(this.textureId);
        }
        this.texture.close();
    }

    private static MinecraftClient readyClient() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.getTextureManager() != null ? client : null;
    }

    private static String sanitizeKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
    }
}
