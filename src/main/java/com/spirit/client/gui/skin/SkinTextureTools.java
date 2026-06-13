package com.spirit.client.gui.skin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class SkinTextureTools {
    private SkinTextureTools() {
    }

    public static NativeImage readSkin(Path path) throws Exception {
        try (InputStream stream = Files.newInputStream(path)) {
            NativeImage image = NativeImage.read(stream);
            return normalize(image);
        }
    }

    public static NativeImage normalize(NativeImage image) {
        if (image.getWidth() == 64 && image.getHeight() == 64) {
            return image;
        }
        if (image.getWidth() == 64 && image.getHeight() == 32) {
            NativeImage converted = new NativeImage(64, 64, true);
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 64; x++) {
                    converted.setColor(x, y, image.getColor(x, y));
                }
            }
            image.close();
            return converted;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        image.close();
        throw new IllegalArgumentException("Minecraft skins must be 64x64 or legacy 64x32, got " + width + "x" + height);
    }

    public static NativeImage copy(NativeImage source) {
        NativeImage copy = new NativeImage(source.getWidth(), source.getHeight(), true);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                copy.setColor(x, y, source.getColor(x, y));
            }
        }
        return copy;
    }

    public static int[] snapshot(NativeImage source) {
        int[] pixels = new int[source.getWidth() * source.getHeight()];
        int i = 0;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                pixels[i++] = source.getColor(x, y);
            }
        }
        return pixels;
    }

    public static void restore(NativeImage target, int[] pixels) {
        int i = 0;
        for (int y = 0; y < target.getHeight(); y++) {
            for (int x = 0; x < target.getWidth(); x++) {
                target.setColor(x, y, pixels[i++]);
            }
        }
    }

    public static void writeSkin(NativeImage image, Path path) throws Exception {
        Files.createDirectories(path.getParent());
        image.writeTo(path);
    }

    public static Identifier registerTexture(String key, NativeImage image) {
        String safe = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
        Identifier id = new Identifier("koil", "skin/" + safe);
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(image));
        return id;
    }


    public static int nativeToArgb(int color) {
        return (color & 0xFF000000) | ((color & 0x000000FF) << 16) | (color & 0x0000FF00) | ((color & 0x00FF0000) >> 16);
    }

    public static int argbToNative(int color) {
        return nativeToArgb(color);
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 255) << 24);
    }

    public static int lighten(int color, int amount) {
        int a = color & 0xFF000000;
        int r = Math.min(255, ((color >> 16) & 255) + amount);
        int g = Math.min(255, ((color >> 8) & 255) + amount);
        int b = Math.min(255, (color & 255) + amount);
        return a | (r << 16) | (g << 8) | b;
    }

    public static int darken(int color, int amount) {
        int a = color & 0xFF000000;
        int r = Math.max(0, ((color >> 16) & 255) - amount);
        int g = Math.max(0, ((color >> 8) & 255) - amount);
        int b = Math.max(0, (color & 255) - amount);
        return a | (r << 16) | (g << 8) | b;
    }
}
