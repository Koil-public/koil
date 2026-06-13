package com.spirit.koil.api.util.file.media.image;

import com.luciad.imageio.webp.WebPImageReaderSpi;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.spirit.Main.SUBLOGGER;

public final class ImageDecoder {
    private static final Map<String, String> FAILURE_CACHE = new ConcurrentHashMap<>();

    static {
        ImageIO.scanForPlugins();
        try {
            IIORegistry.getDefaultInstance().registerServiceProvider(new WebPImageReaderSpi());
        } catch (Throwable throwable) {
            logFailureOnce("webp-provider", "Unable to register explicit WebP ImageIO provider: " + throwable.getMessage());
        }
    }

    private ImageDecoder() {
    }

    public static BufferedImage decodeFile(File file) throws IOException {
        if (file == null || !file.exists()) {
            return null;
        }
        try (InputStream stream = Files.newInputStream(file.toPath())) {
            return decodeStream(stream, file.getAbsolutePath());
        }
    }

    public static BufferedImage decodeBytes(byte[] bytes, String sourceDescription) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (InputStream stream = new ByteArrayInputStream(bytes)) {
            return decodeStream(stream, sourceDescription);
        }
    }

    public static NativeImageBackedTexture createTexture(File file) throws IOException {
        BufferedImage image = decodeFile(file);
        return image == null ? null : createTexture(image);
    }

    public static NativeImageBackedTexture createTexture(byte[] bytes, String sourceDescription) throws IOException {
        BufferedImage image = decodeBytes(bytes, sourceDescription);
        return image == null ? null : createTexture(image);
    }

    public static NativeImageBackedTexture createTexture(BufferedImage image) {
        return image == null ? null : new NativeImageBackedTexture(toNativeImage(image));
    }

    public static BufferedImage scaleToFit(BufferedImage originalImage, int maxWidth, int maxHeight) {
        if (originalImage == null) {
            return null;
        }
        int originalWidth = Math.max(1, originalImage.getWidth());
        int originalHeight = Math.max(1, originalImage.getHeight());
        if (maxWidth <= 0 || maxHeight <= 0) {
            return originalImage;
        }

        double scale = Math.min((double) maxWidth / originalWidth, (double) maxHeight / originalHeight);
        if (scale >= 1.0D) {
            return originalImage;
        }

        int newWidth = Math.max(1, (int) (originalWidth * scale));
        int newHeight = Math.max(1, (int) (originalHeight * scale));
        BufferedImage scaledImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaledImage.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED);
        graphics.drawImage(originalImage, 0, 0, newWidth, newHeight, null);
        graphics.dispose();
        return scaledImage;
    }

    private static BufferedImage decodeStream(InputStream stream, String sourceDescription) throws IOException {
        byte[] bytes = readAllBytes(stream);
        try (ImageInputStream probeStream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (probeStream == null) {
                throw new IOException("Unable to open image stream for " + sourceDescription);
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(probeStream);
            if (!readers.hasNext()) {
                String extension = ImageFormatSupport.extensionOf(sourceDescription);
                throw new IOException("No ImageIO reader available for " + (extension.isBlank() ? "image data" : "." + extension) + " from " + sourceDescription);
            }

            IOException lastFailure = null;
            while (readers.hasNext()) {
                ImageReader reader = readers.next();
                try (ImageInputStream imageStream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                    reader.setInput(imageStream, true, true);
                    BufferedImage image = reader.read(0);
                    if (image != null) {
                        return image;
                    }
                } catch (IOException exception) {
                    lastFailure = exception;
                } finally {
                    reader.dispose();
                }
            }

            if (lastFailure != null) {
                throw new IOException("Failed to decode image from " + sourceDescription + ": " + lastFailure.getMessage(), lastFailure);
            }
            throw new IOException("Image decoder returned no image for " + sourceDescription);
        } catch (IOException exception) {
            logFailureOnce(sourceDescription, exception.getMessage());
            throw exception;
        }
    }

    private static byte[] readAllBytes(InputStream stream) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static NativeImage toNativeImage(BufferedImage bufferedImage) {
        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, bufferedImage.getWidth(), bufferedImage.getHeight(), true);
        for (int x = 0; x < bufferedImage.getWidth(); x++) {
            for (int y = 0; y < bufferedImage.getHeight(); y++) {
                int argb = bufferedImage.getRGB(x, y);
                int alpha = (argb >> 24) & 0xFF;
                int red = (argb >> 16) & 0xFF;
                int green = (argb >> 8) & 0xFF;
                int blue = argb & 0xFF;
                int abgr = (alpha << 24) | (blue << 16) | (green << 8) | red;
                nativeImage.setColor(x, y, abgr);
            }
        }
        return nativeImage;
    }

    private static void logFailureOnce(String sourceDescription, String message) {
        if (sourceDescription == null || message == null) {
            return;
        }
        String previous = FAILURE_CACHE.put(sourceDescription, message);
        if (!message.equals(previous)) {
            SUBLOGGER.logW("Image Decoder", sourceDescription + ": " + message);
        }
    }
}
