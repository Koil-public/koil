package com.spirit.koil.api.util.file.media.video;

import com.spirit.koil.api.util.file.media.MediaPerformanceProfile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class VideoPosterCache {
    private static final File CACHE_DIRECTORY = new File("koil/sys/cache/video-posters");

    private VideoPosterCache() {
    }

    static byte[] read(File sourceFile, int maxWidth, int maxHeight) {
        File cacheFile = cacheFile(sourceFile, maxWidth, maxHeight);
        if (!cacheFile.isFile()) {
            return null;
        }
        try {
            return Files.readAllBytes(cacheFile.toPath());
        } catch (IOException ignored) {
            return null;
        }
    }

    static void write(File sourceFile, int maxWidth, int maxHeight, byte[] bytes) {
        if (sourceFile == null || bytes == null || bytes.length == 0) {
            return;
        }
        try {
            ensureDirectory();
            Files.write(cacheFile(sourceFile, maxWidth, maxHeight).toPath(), bytes);
        } catch (IOException ignored) {
        }
    }

    static void purgeExpiredEntries() {
        File[] files = CACHE_DIRECTORY.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            if (now - file.lastModified() > MediaPerformanceProfile.VIDEO_POSTER_DISK_CACHE_TTL_MS) {
                try {
                    Files.deleteIfExists(file.toPath());
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static File cacheFile(File sourceFile, int maxWidth, int maxHeight) {
        String key = sourceFile.getAbsolutePath()
                + "::" + sourceFile.lastModified()
                + "::" + sourceFile.length()
                + "::" + maxWidth + "x" + maxHeight;
        return new File(CACHE_DIRECTORY, hash(key) + ".png");
    }

    private static void ensureDirectory() throws IOException {
        Path path = CACHE_DIRECTORY.toPath();
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
