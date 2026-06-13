package com.spirit.koil.api.util.file.image;

import com.google.gson.JsonElement;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.koil.api.util.file.media.image.ImageDecoder;
import com.spirit.koil.api.util.file.media.image.ImageFormatSupport;
import com.spirit.koil.api.util.file.media.image.ImageTexture;
import com.spirit.koil.api.util.file.media.image.ImageTextureService;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

import static com.spirit.Main.SUBLOGGER;

public class ExternalImageLoader {
    public static NativeImageBackedTexture loadImage(String path) throws IOException {
        return ImageDecoder.createTexture(new File(path));
    }

    public static NativeImageBackedTexture loadScaledImage(String path, int maxWidth, int maxHeight) throws IOException {
        File file = new File(path);
        BufferedImage image = ImageDecoder.decodeFile(file);
        if (image == null) {
            return null;
        }
        return ImageDecoder.createTexture(ImageDecoder.scaleToFit(image, maxWidth, maxHeight));
    }

    public static NativeImageBackedTexture loadImage(File file) throws IOException {
        return ImageDecoder.createTexture(file);
    }

    public static NativeImageBackedTexture loadImage(byte[] bytes, String sourceDescription) throws IOException {
        return ImageDecoder.createTexture(bytes, sourceDescription);
    }

    public static Identifier registerDynamicTexture(String textureIdPrefix, String uniqueKey, File file) throws IOException {
        ImageTexture texture = ImageTextureService.loadTexture(file, textureIdPrefix, uniqueKey);
        return texture == null ? null : texture.textureId();
    }

    public static Identifier registerDynamicTexture(String textureIdPrefix, String uniqueKey, byte[] bytes) throws IOException {
        ImageTexture texture = ImageTextureService.loadTexture(bytes, textureIdPrefix, uniqueKey, uniqueKey);
        return texture == null ? null : texture.textureId();
    }

    public static Identifier loadExternalPngTexture(String directoryPath, String textureName) {
        return loadExternalImageTexture(directoryPath, textureName, "external_image_png", null);
    }

    public static Identifier loadExternalPngTextureWithColorVariant(String directoryPath, String textureName) {
        if (wantsColoredFileIcons()) {
            Identifier colored = loadExternalImageTexture(directoryPath, coloredVariantName(textureName), "external_image_png", null);
            if (colored != null) {
                return colored;
            }
        }
        return loadExternalPngTexture(directoryPath, textureName);
    }

    public static Identifier loadExternalPngTexture(String directoryPath, String textureName, String uniqueTextureName) {
        return loadExternalImageTexture(directoryPath, textureName, "external_image_png", uniqueTextureName);
    }

    public static Identifier loadExternalWebPTexture(String directoryPath, String textureName) {
        return loadExternalImageTexture(directoryPath, textureName, "external_image_webp", null);
    }

    public static Identifier loadExternalJpegTexture(String directoryPath, String textureName) {
        return loadExternalImageTexture(directoryPath, textureName, "external_image_jpeg", null);
    }

    public static Identifier loadExternalTextureAuto(String directoryPath, String baseTextureName, String... extensions) {
        for (String extension : extensions) {
            String candidate = baseTextureName + "." + extension;
            Identifier texture = loadExternalImageTexture(directoryPath, candidate, "external_image_auto", null);
            if (texture != null) {
                return texture;
            }
        }
        return null;
    }

    public static boolean isSupportedImageFile(File file) {
        return ImageFormatSupport.isSupportedImageFile(file);
    }

    public static File ensureRenderablePng(File file) throws IOException {
        if (!isSupportedImageFile(file)) {
            return null;
        }
        if ("png".equals(ImageFormatSupport.extensionOf(file.getName()))) {
            return file;
        }

        BufferedImage image = ImageDecoder.decodeFile(file);
        if (image == null) {
            return null;
        }

        File siblingPng = new File(file.getParentFile(), stripExtension(file.getName()) + ".png");
        if (!siblingPng.exists() || siblingPng.lastModified() < file.lastModified()) {
            ImageIO.write(image, "png", siblingPng);
        }
        return siblingPng;
    }

    private static Identifier loadExternalImageTexture(String directoryPath, String textureName, String namespace, String uniqueTextureName) {
        try {
            File file = new File(Paths.get(directoryPath, textureName).toString());
            if (!file.exists()) {
                return null;
            }
            ImageTexture texture = ImageTextureService.loadTexture(file, namespace, uniqueTextureName != null ? uniqueTextureName : textureName);
            return texture == null ? null : texture.textureId();
        } catch (IOException exception) {
            SUBLOGGER.logE("Image Loader", "Failed to load external image texture " + textureName + ": " + exception.getMessage());
            return null;
        }
    }

    private static String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return fileName;
        }
        return fileName.substring(0, dotIndex);
    }

    private static String coloredVariantName(String textureName) {
        int dotIndex = textureName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return textureName + "_colored";
        }
        return textureName.substring(0, dotIndex) + "_colored" + textureName.substring(dotIndex);
    }

    private static boolean wantsColoredFileIcons() {
        try {
            JsonElement element = JSONFileEditor.getValueFromJson("./koil/sys/config.json", "wantsColoredFileIcons");
            return element != null && element.isJsonPrimitive() && element.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }
}
