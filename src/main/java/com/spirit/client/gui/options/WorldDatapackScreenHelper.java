package com.spirit.client.gui.options;

import com.spirit.koil.api.chat.RichChatCommandOutputBridge;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.VanillaDataPackProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.SaveProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WorldDatapackScreenHelper {
    private WorldDatapackScreenHelper() {
    }

    public static void open(Screen returnScreen) {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (minecraft == null) {
            return;
        }
        if (isRemoteServer(minecraft)) {
            if (minecraft.player != null && minecraft.player.hasPermissionLevel(2)) {
                RichChatCommandOutputBridge.rememberOutgoingChatCommand("datapack list");
                minecraft.player.networkHandler.sendChatCommand("datapack list");
                minecraft.player.sendMessage(Text.literal("Requested server datapack list. Remote server datapacks cannot be managed from a local pack file screen unless the server exposes those files."), false);
            }
            return;
        }
        Path datapacksFolder = getDatapacksFolder(minecraft);
        try {
            Files.createDirectories(datapacksFolder);
        } catch (IOException ignored) {
            return;
        }
        ResourcePackManager manager = VanillaDataPackProvider.createManager(datapacksFolder);
        manager.scanPacks();
        DataConfiguration dataConfiguration = getDataConfiguration(minecraft);
        if (dataConfiguration != null && dataConfiguration.dataPacks() != null) {
            manager.setEnabledProfiles(dataConfiguration.dataPacks().getEnabled());
        }
        minecraft.setScreen(new PackScreen(manager, ignored -> minecraft.setScreen(returnScreen), datapacksFolder, Text.literal("Data Packs")));
    }

    public static boolean canOpenDatapackManager() {
        MinecraftClient minecraft = MinecraftClient.getInstance();
        if (minecraft == null || minecraft.world == null) {
            return false;
        }
        if (!isRemoteServer(minecraft)) {
            return true;
        }
        return minecraft.player != null && minecraft.player.hasPermissionLevel(2);
    }

    private static boolean isRemoteServer(MinecraftClient minecraft) {
        return minecraft.world != null && minecraft.getServer() == null;
    }

    private static Path getDatapacksFolder(MinecraftClient minecraft) {
        MinecraftServer server = minecraft.getServer();
        if (server != null) {
            return server.getSavePath(WorldSavePath.DATAPACKS);
        }
        return FabricLoader.getInstance().getGameDir().resolve("datapacks");
    }

    private static DataConfiguration getDataConfiguration(MinecraftClient minecraft) {
        MinecraftServer server = minecraft.getServer();
        if (server == null) {
            return DataConfiguration.SAFE_MODE;
        }
        SaveProperties saveProperties = server.getSaveProperties();
        if (saveProperties == null) {
            return DataConfiguration.SAFE_MODE;
        }
        return saveProperties.getDataConfiguration();
    }
}
