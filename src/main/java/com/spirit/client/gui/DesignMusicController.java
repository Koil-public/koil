package com.spirit.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.Main;
import com.spirit.client.gui.main.KoilMessageToast;
import com.spirit.koil.api.util.file.audio.AudioManager;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;

import java.io.File;
import java.time.LocalDate;

@Environment(EnvType.CLIENT)
public final class DesignMusicController {
    private static final String CONFIG_PATH = "./koil/sys/config.json";

    private DesignMusicController() {
    }

    public static boolean isUiRedesignEnabled() {
        return readBoolean("uiRedesign", false);
    }

    public static boolean isDesignMusicEnabled() {
        return readBoolean("designMusic", false);
    }

    public static void applyConfiguredMusic(boolean showToast) {
        if (isUiRedesignEnabled() && isDesignMusicEnabled()) {
            startDesignMusic(showToast);
        } else {
            stopDesignMusic();
        }
    }

    public static void applyDesignMusicState(boolean enabled, boolean showToast) {
        if (enabled && isUiRedesignEnabled()) {
            startDesignMusic(showToast);
        } else {
            stopDesignMusic();
        }
    }

    public static void startDesignMusic(boolean showToast) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }

        File musicFile = resolveMusicFile(client, showToast);
        if (musicFile == null) {
            return;
        }
        if (AudioManager.isCurrentAudioFile(musicFile) && AudioManager.isAudioPlaying()) {
            return;
        }

        AudioManager.playAudio(musicFile, true, readFloat("musicVolume", 1.0F));
        Main.musicToastShown = true;
    }

    public static void stopDesignMusic() {
        Main.musicToastShown = false;
        AudioManager.stopAllAudio();
    }

    public static void stopDesignMusicOnly() {
        File musicFile = resolveMusicFile(MinecraftClient.getInstance(), false);
        if (musicFile != null && AudioManager.isCurrentAudioFile(musicFile)) {
            Main.musicToastShown = false;
            AudioManager.stopAllAudio();
        }
    }

    private static File resolveMusicFile(MinecraftClient client, boolean showToast) {
        if (client.options != null) {
            client.options.getSoundVolumeOption(SoundCategory.MUSIC).setValue(0.0);
            client.options.write();
        }

        String basePath = Main.uiAudioDirectory + "title_screen_music";
        String musicConfigPath = Main.uiFilesDirectory + "music.json";
        String backgroundMusic = readString("backgroundMusic", "default");
        JsonElement mappingsElement = JSONFileEditor.getValueFromJson(musicConfigPath, "backgroundMusic");
        if (mappingsElement == null || !mappingsElement.isJsonArray()) {
            return createMusicFile(client, basePath, ".ogg", "Day (unreleased)", "Bashful", showToast);
        }

        JsonArray mappings = mappingsElement.getAsJsonArray();
        int currentMonth = LocalDate.now().getMonthValue();
        boolean holidayDesign = readBoolean("holidayDesign", false);

        for (JsonElement element : mappings) {
            JsonObject musicEntry = element.getAsJsonObject();
            int month = musicEntry.has("month") ? musicEntry.get("month").getAsInt() : -1;
            boolean holidayCheck = musicEntry.has("holidayCheck") && musicEntry.get("holidayCheck").getAsBoolean();
            if (holidayCheck && month == currentMonth && holidayDesign) {
                return createMusicFile(client, basePath, musicEntry, showToast);
            }

            JsonArray tags = musicEntry.getAsJsonArray("tags");
            for (JsonElement tagElement : tags) {
                if (tagElement.getAsString().equalsIgnoreCase(backgroundMusic)) {
                    return createMusicFile(client, basePath, musicEntry, showToast);
                }
            }
        }

        for (JsonElement element : mappings) {
            JsonObject musicEntry = element.getAsJsonObject();
            int month = musicEntry.has("month") ? musicEntry.get("month").getAsInt() : -1;
            if (month == currentMonth) {
                return createMusicFile(client, basePath, musicEntry, showToast);
            }
        }

        return createMusicFile(client, basePath, ".ogg", "Day (unreleased)", "Bashful", showToast);
    }

    private static File createMusicFile(MinecraftClient client, String basePath, JsonObject musicEntry, boolean showToast) {
        return createMusicFile(
                client,
                basePath,
                musicEntry.get("fileSuffix").getAsString(),
                musicEntry.get("title").getAsString(),
                musicEntry.get("artist").getAsString(),
                showToast
        );
    }

    private static File createMusicFile(MinecraftClient client, String basePath, String suffix, String title, String artist, boolean showToast) {
        if (showToast && !Main.musicToastShown) {
            Main.musicToastShown = true;
            KoilMessageToast.add(client.getToastManager(), KoilMessageToast.Type.MUSIC, Text.of(title), Text.of("By: " + artist));
        }
        return new File(basePath + suffix);
    }

    private static boolean readBoolean(String key, boolean fallback) {
        try {
            JsonElement element = JSONFileEditor.getValueFromJson(CONFIG_PATH, key);
            return element != null && element.isJsonPrimitive() ? element.getAsBoolean() : fallback;
        } catch (Exception e) {
            Main.SUBLOGGER.logW("Design music", "Failed to read " + key + ": " + e.getMessage());
            return fallback;
        }
    }

    private static float readFloat(String key, float fallback) {
        try {
            JsonElement element = JSONFileEditor.getValueFromJson(CONFIG_PATH, key);
            return element != null && element.isJsonPrimitive() ? element.getAsFloat() : fallback;
        } catch (Exception e) {
            Main.SUBLOGGER.logW("Design music", "Failed to read " + key + ": " + e.getMessage());
            return fallback;
        }
    }

    private static String readString(String key, String fallback) {
        try {
            JsonElement element = JSONFileEditor.getValueFromJson(CONFIG_PATH, key);
            return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
        } catch (Exception e) {
            Main.SUBLOGGER.logW("Design music", "Failed to read " + key + ": " + e.getMessage());
            return fallback;
        }
    }
}
