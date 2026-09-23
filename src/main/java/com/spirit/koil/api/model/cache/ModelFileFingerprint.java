package com.spirit.koil.api.model.cache;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Persistent SHA-256 identity for large local model artifacts. */
public final class ModelFileFingerprint {
    private ModelFileFingerprint() {}

    public static String sha256(Path modelFile, Path cacheDirectory) {
        if (modelFile == null || !Files.isRegularFile(modelFile)) return "missing";
        try {
            Path normalized = modelFile.toAbsolutePath().normalize();
            long size = Files.size(normalized);
            long modified = Files.getLastModifiedTime(normalized).toMillis();
            Path metadata = cacheDirectory.resolve("model-fingerprint.json");
            if (Files.isRegularFile(metadata)) {
                try {
                    JsonObject root = JsonParser.parseString(Files.readString(metadata, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (normalized.toString().equals(string(root, "path"))
                            && size == number(root, "size")
                            && modified == number(root, "modified")) {
                        String hash = string(root, "sha256");
                        if (hash.matches("[0-9a-f]{64}")) return hash;
                    }
                } catch (RuntimeException ignored) {
                }
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            try (InputStream input = Files.newInputStream(normalized)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            Files.createDirectories(cacheDirectory);
            JsonObject root = new JsonObject();
            root.addProperty("path", normalized.toString());
            root.addProperty("size", size);
            root.addProperty("modified", modified);
            root.addProperty("sha256", hash);
            Path temp = Files.createTempFile(cacheDirectory, "model-fingerprint-", ".tmp");
            Files.writeString(temp, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, metadata, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception unsupported) {
                Files.move(temp, metadata, StandardCopyOption.REPLACE_EXISTING);
            }
            return hash;
        } catch (Exception exception) {
            return "unavailable:" + exception.getClass().getSimpleName();
        }
    }

    private static String string(JsonObject root, String key) {
        return root != null && root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsString() : "";
    }

    private static long number(JsonObject root, String key) {
        return root != null && root.has(key) && !root.get(key).isJsonNull() ? root.get(key).getAsLong() : -1L;
    }
}
