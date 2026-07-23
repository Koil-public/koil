package com.spirit.koil.api.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.minecraft.client.MinecraftClient;

public final class RichChatSettings {
    private static final String CONFIG_PATH = "./koil/sys/config.json";
    public static final String ENABLED = "richChatEnabled";
    public static final String MEDIA = "richChatMedia";
    public static final String IMAGES = "richChatImages";
    public static final String AUDIO = "richChatAudio";
    public static final String VIDEO = "richChatVideo";
    public static final String GIFS = "richChatGifs";
    public static final String FILES = "richChatFiles";
    public static final String LATEX = "richChatLatex";
    public static final String EFFECTS = "richChatEffects";
    public static final String TIMESTAMPS = "richChatTimestamps";
    public static final String INDICATORS = "richChatIndicators";

    public static String optionTranslationKey(String settingKey) {
        return "koil.chat.option." + (settingKey == null ? "" : settingKey);
    }

    private RichChatSettings() {
    }

    public static boolean enabled() { return get(ENABLED, true); }
    public static boolean mediaEnabled() { return enabled() && get(MEDIA, true); }
    public static boolean imagesEnabled() { return mediaEnabled() && get(IMAGES, true); }
    public static boolean audioEnabled() { return mediaEnabled() && get(AUDIO, true); }
    public static boolean videoEnabled() { return mediaEnabled() && get(VIDEO, true); }
    public static boolean gifsEnabled() { return mediaEnabled() && get(GIFS, true); }
    public static boolean filesEnabled() { return mediaEnabled() && get(FILES, true); }
    public static boolean latexEnabled() { return enabled() && get(LATEX, true); }
    public static boolean effectsEnabled() { return enabled() && get(EFFECTS, true); }
    public static boolean timestampsEnabled() { return enabled() && get(TIMESTAMPS, true); }
    public static boolean indicatorsEnabled() { return enabled() && get(INDICATORS, true); }

    public static boolean chatColorsEnabled() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client == null || client.options == null || Boolean.TRUE.equals(client.options.getChatColors().getValue());
    }

    public static boolean get(String key, boolean fallback) {
        try {
            JsonElement value = JSONFileEditor.getValueFromJson(CONFIG_PATH, key);
            return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public static void set(String key, boolean value) {
        try {
            JSONFileEditor.updateValueInJson(CONFIG_PATH, key, new JsonPrimitive(value));
        } catch (java.io.IOException ignored) {
            // Settings remain optional; a read-only/broken config must not
            // crash Minecraft's options screen.
        }
    }
}
