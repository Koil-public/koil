package com.spirit.koil.api.model.voice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ModelVoiceSettingsStore {
    public static final Path PATH = Path.of("koil/sys/model/voice.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ModelVoiceSettingsStore() {
    }

    public static ModelVoiceSettings load() {
        if (!Files.isRegularFile(PATH)) {
            ModelVoiceSettings defaults = ModelVoiceSettings.defaults();
            save(defaults);
            return defaults;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            boolean enabled = root.has("enabled") && root.get("enabled").getAsBoolean();
            String voiceId = root.has("voiceId") ? root.get("voiceId").getAsString() : ModelVoiceSettings.DEFAULT_VOICE_ID;
            return new ModelVoiceSettings(enabled, voiceId);
        } catch (Exception failure) {
            return ModelVoiceSettings.defaults();
        }
    }

    public static void save(ModelVoiceSettings settings) {
        ModelVoiceSettings safe = settings == null ? ModelVoiceSettings.defaults() : settings;
        try {
            Path absolute = PATH.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("enabled", safe.enabled());
            root.addProperty("voiceId", safe.voiceId());
            root.addProperty("privacy", "Remote voices receive short completed generated phrases; voice is opt-in.");
            Path temporary = Files.createTempFile(absolute.getParent(), "model-voice-", ".tmp");
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to save model voice settings: " + failure.getMessage(), failure);
        }
    }
}
