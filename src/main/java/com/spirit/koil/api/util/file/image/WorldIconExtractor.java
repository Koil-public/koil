package com.spirit.koil.api.util.file.image;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static com.spirit.Main.SUBLOGGER;
import static com.spirit.Main.uiImageDirectory;

public class WorldIconExtractor {
    public static void copyWorldIcons() {
        File savesFolder = new File("./saves");
        SUBLOGGER.logI("File-Management thread", "Finding world icons for menu");

        // Ensure the destination folder exists
        File koilSystemFolder = new File(uiImageDirectory + "world");
        if (!koilSystemFolder.exists()) {
            koilSystemFolder.mkdirs();
        }

        File[] saves = savesFolder.listFiles();
        if (saves != null) {
            for (File save : saves) {
                if (save.isDirectory()) {
                    File worldIcon = new File(save, "icon.png");

                    if (worldIcon.exists()) {
                        String sanitizedWorldName = save.getName() + ".png";
                        File targetFile = new File(koilSystemFolder, sanitizedWorldName);

                        try {
                            Files.copy(worldIcon.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            SUBLOGGER.logI("File-Management thread", "Found and Copied icon for world: " + save.getName());
                        } catch (IOException e) {
                            SUBLOGGER.logE("File-Management thread", "Failed to copy icon for world: " + save.getName());
                            e.printStackTrace();
                        }
                    } else {
                        SUBLOGGER.logW("File-Management thread", "No icon.png found for world: " + save.getName());
                    }
                }
            }
        }
    }
}
