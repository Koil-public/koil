package com.spirit.koil.api.util.file.media.image;

import java.io.File;
import java.util.Locale;
import java.util.Set;

public final class ImageFormatSupport {
    private static final Set<String> SUPPORTED_IMAGE_EXTENSIONS = Set.of(
            "png", "apng",
            "jpg", "jpeg", "jpe", "jfif",
            "gif", "bmp", "dib",
            "tiff", "tif",
            "webp",
            "ico",
            "svg",
            "dds", "tga", "psd", "kra",
            "pbm", "pgm", "ppm", "pnm", "wbmp"
    );

    private ImageFormatSupport() {
    }

    public static boolean isSupportedImageFile(File file) {
        return file != null && file.isFile() && isSupportedExtension(extensionOf(file.getName()));
    }

    public static boolean isSupportedExtension(String extension) {
        return extension != null && !extension.isBlank() && SUPPORTED_IMAGE_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex >= fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
