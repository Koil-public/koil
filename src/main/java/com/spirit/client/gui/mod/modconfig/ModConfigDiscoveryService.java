package com.spirit.client.gui.mod.modconfig;

import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ModConfigDiscoveryService {
    private static final int MAX_SCAN_DEPTH = 4;

    private ModConfigDiscoveryService() {
    }

    public static File getConfigRoot() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            return new File(client.runDirectory, "config");
        }
        return new File("./config");
    }

    public static DiscoveredModConfigSet discover(ModContainer mod) {
        File configRoot = getConfigRoot();
        if (!configRoot.exists() || !configRoot.isDirectory()) {
            return new DiscoveredModConfigSet(mod, List.of());
        }

        String modId = normalize(mod.getMetadata().getId());
        String modName = normalize(mod.getMetadata().getName());
        Map<String, DiscoveredModConfigFile> matches = new LinkedHashMap<>();

        try (var paths = Files.walk(configRoot.toPath(), MAX_SCAN_DEPTH)) {
            paths.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(file -> ConfigFormat.fromFile(file) != ConfigFormat.UNKNOWN)
                    .filter(file -> matchesMod(modId, modName, configRoot, file))
                    .sorted(Comparator.comparingInt(file -> scoreMatch(modId, modName, configRoot, file)))
                    .forEach(file -> {
                        String relativePath = configRoot.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
                        matches.putIfAbsent(relativePath, new DiscoveredModConfigFile(file, relativePath, ConfigFormat.fromFile(file)));
                    });
        } catch (IOException ignored) {
        }

        return new DiscoveredModConfigSet(mod, new ArrayList<>(matches.values()));
    }

    private static boolean matchesMod(String modId, String modName, File configRoot, File file) {
        String relativePath = configRoot.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
        String normalizedRelative = normalize(relativePath);
        String normalizedFileName = normalize(file.getName());
        String topLevel = normalize(relativePath.contains("/") ? relativePath.substring(0, relativePath.indexOf('/')) : relativePath);

        return normalizedRelative.contains(modId)
                || normalizedFileName.contains(modId)
                || topLevel.equals(modId)
                || (!modName.isBlank() && (normalizedRelative.contains(modName) || normalizedFileName.contains(modName) || topLevel.equals(modName)));
    }

    private static int scoreMatch(String modId, String modName, File configRoot, File file) {
        String relativePath = configRoot.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
        String normalizedRelative = normalize(relativePath);
        String normalizedFileName = normalize(file.getName());
        String topLevel = normalize(relativePath.contains("/") ? relativePath.substring(0, relativePath.indexOf('/')) : relativePath);

        int score = 1000;
        if (topLevel.equals(modId)) score -= 400;
        if (topLevel.equals(modName)) score -= 350;
        if (normalizedFileName.startsWith(modId)) score -= 250;
        if (normalizedFileName.startsWith(modName)) score -= 200;
        if (normalizedRelative.contains("/" + modId + "/")) score -= 150;
        if (!modName.isBlank() && normalizedRelative.contains("/" + modName + "/")) score -= 120;
        score += normalizedRelative.length();
        return score;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }
}
