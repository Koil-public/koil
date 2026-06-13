package com.spirit.koil.api.performance;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PerformanceRuntimeContextService {
    private PerformanceRuntimeContextService() {
    }

    public static PerformanceRuntimeContext capture(MinecraftClient client) {
        List<String> packs = enabledResourcePacks(client);
        List<String> optimizationConfigs = optimizationConfigNotes();
        String worldType = worldType(client);
        String worldName = worldName(client);
        String server = serverAddress(client);
        String dimension = dimension(client);
        String shaderState = shaderState();
        String suggested = suggestedProfile(worldType, packs, shaderState, FabricLoader.getInstance().getAllMods().size());
        List<String> notes = new ArrayList<>();
        if (FabricLoader.getInstance().getAllMods().size() > 180) {
            notes.add("Heavy modpack: high startup/load and memory pressure risk.");
        }
        if (packs.size() > 6) {
            notes.add("Many enabled resourcepacks: texture reload and VRAM pressure may be higher.");
        }
        if (!optimizationConfigs.isEmpty()) {
            notes.add("Optimization mod config files detected; Koil can recommend around them without requiring those mods.");
        }
        if (!"none detected".equals(shaderState)) {
            notes.add("Shader pipeline detected; render and VRAM recommendations should be reviewed before applying.");
        }
        return new PerformanceRuntimeContext(
                System.currentTimeMillis(),
                worldType,
                worldName,
                server,
                dimension,
                profileKey(worldType, worldName, server, dimension),
                suggested,
                packs.size(),
                packs,
                shaderState,
                optimizationConfigs,
                notes
        );
    }

    private static String worldType(MinecraftClient client) {
        if (client == null || client.world == null) {
            return "menu";
        }
        if (client.getCurrentServerEntry() != null) {
            return "server";
        }
        return "singleplayer";
    }

    private static String worldName(MinecraftClient client) {
        try {
            if (client == null || client.world == null) {
                return "none";
            }
            if (client.isInSingleplayer()) {
                return client.getServer() != null ? client.getServer().getSaveProperties().getLevelName() : "singleplayer";
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private static String serverAddress(MinecraftClient client) {
        try {
            return client != null && client.getCurrentServerEntry() != null ? client.getCurrentServerEntry().address : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String dimension(MinecraftClient client) {
        try {
            return client != null && client.world != null ? client.world.getRegistryKey().getValue().toString() : "none";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static List<String> enabledResourcePacks(MinecraftClient client) {
        try {
            if (client == null || client.getResourcePackManager() == null) {
                return List.of();
            }
            return client.getResourcePackManager().getEnabledProfiles().stream()
                    .map(ResourcePackProfile::getName)
                    .toList();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static String shaderState() {
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            return "Iris installed";
        }
        if (FabricLoader.getInstance().isModLoaded("oculus")) {
            return "Oculus installed";
        }
        Path shaderpacks = FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
        try {
            if (Files.exists(shaderpacks) && Files.list(shaderpacks).findAny().isPresent()) {
                return "shaderpacks folder populated";
            }
        } catch (Exception ignored) {
        }
        return "none detected";
    }

    private static List<String> optimizationConfigNotes() {
        Path config = FabricLoader.getInstance().getConfigDir();
        List<String> notes = new ArrayList<>();
        addIfExists(notes, config.resolve("sodium-options.json"), "Sodium config");
        addIfExists(notes, config.resolve("iris.properties"), "Iris config");
        addIfExists(notes, config.resolve("ferritecore.mixin.properties"), "FerriteCore config");
        addIfExists(notes, config.resolve("immediatelyfast.json"), "ImmediatelyFast config");
        addIfExists(notes, config.resolve("entityculling.json"), "EntityCulling config");
        addIfExists(notes, config.resolve("modernfix-mixins.properties"), "ModernFix config");
        addIfExists(notes, config.resolve("c2me.toml"), "C2ME config");
        return notes;
    }

    private static void addIfExists(List<String> notes, Path path, String label) {
        if (Files.exists(path)) {
            notes.add(label + ": " + path.getFileName());
        }
    }

    private static String suggestedProfile(String worldType, List<String> packs, String shaderState, int modCount) {
        if (!"none detected".equals(shaderState)) {
            return PerformanceProfileMode.SHADER_FRIENDLY.name().toLowerCase(Locale.ROOT);
        }
        if ("server".equals(worldType)) {
            return PerformanceProfileMode.SERVER_FRIENDLY.name().toLowerCase(Locale.ROOT);
        }
        if (modCount > 180 || packs.size() > 8) {
            return "heavy_modded";
        }
        return PerformanceProfileMode.BALANCED.name().toLowerCase(Locale.ROOT);
    }

    private static String profileKey(String worldType, String worldName, String server, String dimension) {
        String target = "server".equals(worldType) ? server : worldName;
        if (target == null || target.isBlank()) {
            target = "default";
        }
        return (worldType + "/" + target + "/" + dimension).replaceAll("[^a-zA-Z0-9._/-]", "_");
    }
}
