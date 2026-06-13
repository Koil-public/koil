package com.spirit.koil.api.util.file.image;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Base64;

import static com.spirit.Main.*;

public class ServerIconExtractor {
    public static void extractAndSaveServerIcons() {
        File serverDat = new File("./servers.dat");
        SUBLOGGER.logI("File-Management thread", "Finding servers.dat file in Minecraft instance");

        if (!serverDat.exists()) {
            SUBLOGGER.logW("File-Management thread", "Unable to Find servers.dat file in Minecraft instance | ~instance");
            return;
        } else {
            SUBLOGGER.logI("File-Management thread", "Found servers.dat file in Minecraft instance");
        }

        try (FileInputStream fis = new FileInputStream(serverDat);
            DataInputStream dis = new DataInputStream(fis)) {
            NbtCompound rootTag = NbtIo.read(dis);
            NbtList servers = rootTag.getList("servers", 10);
            for (int i = 0; i < servers.size(); i++) {
                NbtCompound server = servers.getCompound(i);
                String serverIp = server.getString("ip");
                String iconBase64 = server.getString("icon");

                if (iconBase64 != null && !iconBase64.isEmpty()) {
                    byte[] iconBytes = Base64.getDecoder().decode(iconBase64.substring(iconBase64.indexOf(",") + 1));
                    File outputFile = new File(uiImageServerIconDirectory + serverIp + ".png");

                    outputFile.getParentFile().mkdirs();
                    java.nio.file.Files.write(outputFile.toPath(), iconBytes);
                    SUBLOGGER.logI("File-Management thread", "Saved server icon for " + serverIp + " to " + outputFile.getPath());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
