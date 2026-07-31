package com.spirit.koil.api.model.provider.colibri;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class ColibriConfigurationStore {
    public static final Path DEFAULT_PATH = Path.of("koil/sys/model/local-model.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ColibriConfigurationStore() {
    }

    public static ColibriConfiguration loadOrCreate() {
        return loadOrCreate(DEFAULT_PATH);
    }

    public static ColibriConfiguration loadOrCreate(Path path) {
        if (path == null) {
            return ColibriConfiguration.disabled();
        }
        try {
            if (!Files.isRegularFile(path)) {
                ColibriConfiguration created = withGeneratedKey(ColibriConfiguration.disabled());
                save(path, created);
                return created;
            }
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            ColibriConfiguration loaded = fromJson(root);
            if (loaded.apiKey().isBlank()) {
                loaded = withGeneratedKey(loaded);
                save(path, loaded);
            }
            return loaded;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load local model configuration: " + exception.getMessage(), exception);
        }
    }

    public static void save(Path path, ColibriConfiguration configuration) {
        if (path == null || configuration == null) {
            throw new IllegalArgumentException("configuration path and value are required");
        }
        try {
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, "local-model-", ".tmp");
            Files.writeString(temp, GSON.toJson(toJson(configuration)), StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save local model configuration: " + exception.getMessage(), exception);
        }
    }

    private static ColibriConfiguration fromJson(JsonObject root) {
        return new ColibriConfiguration(
                bool(root, "enabled", false),
                path(root, "executable"),
                path(root, "modelDirectory"),
                string(root, "modelId", "glm-5.2-colibri"),
                string(root, "host", "127.0.0.1"),
                integer(root, "port", 0),
                string(root, "apiKey", ""),
                integer(root, "maximumQueueDepth", 8),
                Duration.ofSeconds(integer(root, "queueTimeoutSeconds", 300)),
                Duration.ofSeconds(integer(root, "startupTimeoutSeconds", 600)),
                Duration.ofSeconds(integer(root, "requestTimeoutSeconds", 1800)),
                integer(root, "kvSlots", 1),
                integer(root, "maximumRestartAttempts", 1),
                Duration.ofSeconds(integer(root, "restartBackoffSeconds", 5))
        );
    }

    private static JsonObject toJson(ColibriConfiguration configuration) {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", configuration.enabled());
        root.addProperty("executable", value(configuration.executable()));
        root.addProperty("modelDirectory", value(configuration.modelDirectory()));
        root.addProperty("modelId", configuration.modelId());
        root.addProperty("host", configuration.host());
        root.addProperty("port", configuration.port());
        root.addProperty("apiKey", configuration.apiKey());
        root.addProperty("maximumQueueDepth", configuration.maximumQueueDepth());
        root.addProperty("queueTimeoutSeconds", configuration.queueTimeout().toSeconds());
        root.addProperty("startupTimeoutSeconds", configuration.startupTimeout().toSeconds());
        root.addProperty("requestTimeoutSeconds", configuration.requestTimeout().toSeconds());
        root.addProperty("kvSlots", configuration.kvSlots());
        root.addProperty("maximumRestartAttempts", configuration.maximumRestartAttempts());
        root.addProperty("restartBackoffSeconds", configuration.restartBackoff().toSeconds());
        root.addProperty("persistentConversationHistory", false);
        root.addProperty("persistentAssociativeMemory", false);
        root.addProperty("gigatokenEnabled", false);
        root.addProperty("expertPrefetchExperimentEnabled", false);
        return root;
    }

    private static ColibriConfiguration withGeneratedKey(ColibriConfiguration value) {
        byte[] bytes = new byte[32];
        ThreadLocalRandom.current().nextBytes(bytes);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new ColibriConfiguration(
                value.enabled(),
                value.executable(),
                value.modelDirectory(),
                value.modelId(),
                value.host(),
                value.port(),
                key,
                value.maximumQueueDepth(),
                value.queueTimeout(),
                value.startupTimeout(),
                value.requestTimeout(),
                value.kvSlots(),
                value.maximumRestartAttempts(),
                value.restartBackoff()
        );
    }

    private static void restrictPermissions(Path path) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            );
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private static boolean bool(JsonObject root, String key, boolean fallback) {
        return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsBoolean() : fallback;
    }

    private static int integer(JsonObject root, String key, int fallback) {
        try {
            return root.has(key) ? root.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String string(JsonObject root, String key, String fallback) {
        try {
            return root.has(key) ? root.get(key).getAsString() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Path path(JsonObject root, String key) {
        String value = string(root, key, "").trim();
        return value.isEmpty() ? null : Path.of(value);
    }

    private static String value(Path path) {
        return path == null ? "" : path.toString();
    }
}
