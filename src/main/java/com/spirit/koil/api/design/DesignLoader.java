package com.spirit.koil.api.design;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.koil.api.util.file.media.image.ImageTextureService;
import net.minecraft.util.Identifier;

import java.io.File;
import java.time.LocalDate;

import static com.spirit.Main.*;
import static com.spirit.koil.api.util.file.image.ExternalImageLoader.loadExternalPngTexture;
import static com.spirit.koil.api.util.file.jar.strings.ModIds.KOIL_ID;

public class DesignLoader {
    private static final Identifier FALLBACK_TEXTURE = new Identifier(KOIL_ID, "textures/gui/menus/options_background_fallback.png");

    private static Identifier TEXTURE;
    private static String LOADED_TEXTURE_NAME;
    private static String LOADED_TEXTURE_DIRECTORY;

    public static Identifier getLoadingTexture() {
        String textureName = createLoadingTexture();
        String textureDirectory = uiImageDirectory;

        if (TEXTURE == null
                || LOADED_TEXTURE_NAME == null
                || LOADED_TEXTURE_DIRECTORY == null
                || !LOADED_TEXTURE_NAME.equals(textureName)
                || !LOADED_TEXTURE_DIRECTORY.equals(textureDirectory)) {
            reloadLoadingTexture(textureDirectory, textureName);
        }

        return TEXTURE != null ? TEXTURE : FALLBACK_TEXTURE;
    }

    public static void reloadLoadingTexture() {
        String textureName = createLoadingTexture();
        String textureDirectory = uiImageDirectory;
        reloadLoadingTexture(textureDirectory, textureName);
    }

    private static void reloadLoadingTexture(String textureDirectory, String textureName) {
        Identifier externalTexture = loadExternalPngTexture(textureDirectory, textureName);
        TEXTURE = externalTexture != null ? externalTexture : FALLBACK_TEXTURE;
        LOADED_TEXTURE_NAME = textureName;
        LOADED_TEXTURE_DIRECTORY = textureDirectory;
    }

    public static String createLoadingTexture() {
        String baseName = "loading_screen";
        String configPath = "./koil/sys/config.json";
        String backgroundFilePath = uiFilesDirectory + "background.json";

        File loadingImageFile = new File(uiImageDirectory, baseName + ".png");
        ImageTextureService.markFilePersistent(loadingImageFile);

        boolean holidayDesign;
        String background;
        JsonArray mappings;
        try {
            holidayDesign = JSONFileEditor.getValueFromJson(configPath, "holidayDesign").getAsBoolean();
            background = JSONFileEditor.getValueFromJson(configPath, "background").getAsString();
            mappings = JSONFileEditor.getValueFromJson(backgroundFilePath, "background").getAsJsonArray();
        } catch (Exception e) {
            isHalloween = false;
            isChristmas = false;
            return baseName + ".png";
        }

        int currentMonth = LocalDate.now().getMonthValue();

        isHalloween = false;
        isChristmas = false;

        for (JsonElement element : mappings) {
            JsonObject entry = element.getAsJsonObject();
            JsonArray tags = entry.getAsJsonArray("tags");
            String fileSuffix = entry.get("fileSuffix").getAsString();
            boolean holidayCheck = entry.has("holidayCheck") && entry.get("holidayCheck").getAsBoolean();
            int month = entry.has("month") ? entry.get("month").getAsInt() : -1;

            if (holidayCheck && month == currentMonth && holidayDesign) {
                if (month == 10) isHalloween = true;
                if (month == 12) isChristmas = true;
                return baseName + fileSuffix + ".png";
            }

            for (JsonElement tag : tags) {
                if (tag.getAsString().equalsIgnoreCase(background)) {
                    return baseName + fileSuffix + ".png";
                }
            }
        }

        return baseName + ".png";
    }
}
