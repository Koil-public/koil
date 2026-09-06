package com.spirit.koil.api.model.catalog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.util.file.KoilInstancePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class LocalModelSelectionStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private LocalModelSelectionStore() {
    }

    public static LocalModelSelection load() {
        return load(defaultPath());
    }

    public static Path defaultPath() {
        return KoilInstancePaths.modelRoot().resolve("model-selection.json");
    }

    static LocalModelSelection load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return LocalModelSelection.none();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            String providerId = string(root, "providerId");
            Path legacyModelFile = path(root, "modelFile");
            Path modelPath = path(root, "modelPath");
            if (modelPath == null) modelPath = legacyModelFile;
            String runtimeId = string(root, "runtimeId");
            if (runtimeId.isBlank()) {
                runtimeId = "llama_cpp".equals(providerId) ? "llama.cpp-b10173" : providerId;
            }
            Path installationRoot = path(root, "installationRoot");
            if (installationRoot == null && modelPath != null) {
                installationRoot = Files.isDirectory(modelPath) ? modelPath : modelPath.getParent();
            }
            return new LocalModelSelection(
                    string(root, "catalogId"),
                    providerId,
                    runtimeId,
                    fallback(string(root, "architectureId"), "legacy/unknown"),
                    string(root, "modelId"),
                    path(root, "runtimeExecutable"),
                    installationRoot,
                    modelPath,
                    fallback(string(root, "tokenizerId"), "legacy/embedded"),
                    string(root, "runtimeProfileId"),
                    integer(root, "contextTokens", 32_768)
            );
        } catch (Exception exception) {
            return LocalModelSelection.none();
        }
    }

    public static void save(LocalModelSelection selection) {
        save(defaultPath(), selection);
    }

    public static void clear() {
        clear(defaultPath());
    }

    static void clear(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to clear local model selection: " + exception.getMessage(), exception);
        }
    }

    static void save(Path path, LocalModelSelection selection) {
        if (path == null || selection == null || !selection.complete()) {
            throw new IllegalArgumentException("a complete model selection is required");
        }
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 2);
        root.addProperty("catalogId", selection.catalogId());
        root.addProperty("providerId", selection.providerId());
        root.addProperty("runtimeId", selection.runtimeId());
        root.addProperty("architectureId", selection.architectureId());
        root.addProperty("modelId", selection.modelId());
        root.addProperty("runtimeExecutable", selection.runtimeExecutable().toString());
        root.addProperty("installationRoot", value(selection.installationRoot()));
        root.addProperty("modelPath", selection.modelPath().toString());
        root.addProperty("modelFile", selection.modelPath().toString());
        root.addProperty("tokenizerId", selection.tokenizerId());
        root.addProperty("runtimeProfileId", selection.runtimeProfileId());
        root.addProperty("contextTokens", selection.contextTokens());
        try {
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, "model-selection-", ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save local model selection: " + exception.getMessage(), exception);
        }
    }

    private static String string(JsonObject root, String key) {
        try {
            return root.has(key) ? root.get(key).getAsString().trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int integer(JsonObject root, String key, int fallback) {
        try {
            return root.has(key) ? root.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Path path(JsonObject root, String key) {
        String value = string(root, key);
        return value.isEmpty() ? null : Path.of(value);
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String value(Path path) {
        return path == null ? "" : path.toString();
    }
}
