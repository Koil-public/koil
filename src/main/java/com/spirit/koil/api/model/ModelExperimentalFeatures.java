package com.spirit.koil.api.model;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.model.provider.colibri.ColibriConfigurationStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Persistent experimental feature values backed by the real model configuration. */
public final class ModelExperimentalFeatures {
    private static volatile Snapshot cached = load();

    private ModelExperimentalFeatures() {}

    public static Snapshot snapshot() { return cached; }

    public static synchronized boolean toggle(Feature feature) {
        Snapshot current = cached;
        boolean enabled = !current.enabled(feature);
        cached = current.with(feature, enabled);
        save(cached);
        return enabled;
    }

    public static synchronized void set(Feature feature, boolean enabled) {
        cached = cached.with(feature, enabled);
        save(cached);
    }

    public static synchronized Snapshot reload() {
        cached = load();
        return cached;
    }

    private static Snapshot load() {
        Path path = ColibriConfigurationStore.DEFAULT_PATH;
        try {
            if (!Files.isRegularFile(path)) return Snapshot.disabled();
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            return new Snapshot(
                    bool(root, "persistentConversationHistory"),
                    bool(root, "persistentAssociativeMemory"),
                    bool(root, "gigatokenEnabled"),
                    bool(root, "expertPrefetchExperimentEnabled"),
                    bool(root, "completionModeEnabled"),
                    bool(root, "noFailEnabled")
            );
        } catch (Exception ignored) {
            return Snapshot.disabled();
        }
    }

    private static void save(Snapshot settings) {
        Path path = ColibriConfigurationStore.DEFAULT_PATH;
        try {
            JsonObject root = Files.isRegularFile(path)
                    ? JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject()
                    : new JsonObject();
            root.addProperty("persistentConversationHistory", settings.persistentConversationHistory());
            root.addProperty("persistentAssociativeMemory", settings.persistentAssociativeMemory());
            root.addProperty("gigatokenEnabled", settings.gigatokenEnabled());
            root.addProperty("expertPrefetchExperimentEnabled", settings.expertPrefetchEnabled());
            root.addProperty("completionModeEnabled", settings.completionModeEnabled());
            root.addProperty("noFailEnabled", settings.noFailEnabled());
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, "model-experiments-", ".tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root), StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not save model experimental settings: " + exception.getMessage(), exception);
        }
    }

    private static boolean bool(JsonObject root, String key) {
        return root.has(key) && !root.get(key).isJsonNull() && root.get(key).getAsBoolean();
    }

    public enum Feature {
        PERSISTENT_CONVERSATION_HISTORY,
        PERSISTENT_ASSOCIATIVE_MEMORY,
        GIGATOKEN,
        EXPERT_PREFETCH,
        COMPLETION_MODE,
        NO_FAIL
    }

    public record Snapshot(
            boolean persistentConversationHistory,
            boolean persistentAssociativeMemory,
            boolean gigatokenEnabled,
            boolean expertPrefetchEnabled,
            boolean completionModeEnabled,
            boolean noFailEnabled
    ) {
        public static Snapshot disabled() { return new Snapshot(false, false, false, false, false, false); }
        public boolean enabled(Feature feature) {
            return switch (feature) {
                case PERSISTENT_CONVERSATION_HISTORY -> persistentConversationHistory;
                case PERSISTENT_ASSOCIATIVE_MEMORY -> persistentAssociativeMemory;
                case GIGATOKEN -> gigatokenEnabled;
                case EXPERT_PREFETCH -> expertPrefetchEnabled;
                case COMPLETION_MODE -> completionModeEnabled;
                case NO_FAIL -> noFailEnabled;
            };
        }
        private Snapshot with(Feature feature, boolean value) {
            return new Snapshot(
                    feature == Feature.PERSISTENT_CONVERSATION_HISTORY ? value : persistentConversationHistory,
                    feature == Feature.PERSISTENT_ASSOCIATIVE_MEMORY ? value : persistentAssociativeMemory,
                    feature == Feature.GIGATOKEN ? value : gigatokenEnabled,
                    feature == Feature.EXPERT_PREFETCH ? value : expertPrefetchEnabled,
                    feature == Feature.COMPLETION_MODE ? value : completionModeEnabled,
                    feature == Feature.NO_FAIL ? value : noFailEnabled
            );
        }
    }
}
