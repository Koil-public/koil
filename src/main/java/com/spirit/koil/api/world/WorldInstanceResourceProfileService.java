package com.spirit.koil.api.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.WorldSavePath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies a world-specific client resource profile for the lifetime of one
 * integrated-world connection. Old worlds receive vanilla Programmer Art and
 * externally linked worlds also inherit the source instance's selected packs.
 */
public final class WorldInstanceResourceProfileService {
    private static final int TEXTURE_UPDATE_DATA_VERSION = 1952;
    private static final int APPLY_DELAY_TICKS = 20;
    private static final Set<Path> MANAGED_LINKS = new LinkedHashSet<>();

    private static List<String> originalEnabledNames = List.of();
    private static int pendingTicks = -1;
    private static boolean active;
    private static boolean initialized;

    private WorldInstanceResourceProfileService() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client != null) {
                pendingTicks = APPLY_DELAY_TICKS;
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            pendingTicks = -1;
            if (client != null) {
                client.execute(() -> restore(client));
            }
        });
    }

    public static void tick(MinecraftClient client) {
        if (client == null || pendingTicks < 0) {
            return;
        }
        if (pendingTicks-- > 0) {
            return;
        }
        pendingTicks = -1;
        applyForJoinedWorld(client);
    }

    private static synchronized void applyForJoinedWorld(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        ResourcePackManager manager = client.getResourcePackManager();
        if (server == null || manager == null || active) {
            return;
        }
        try {
            Path sessionRoot = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
            Path realWorldRoot = sessionRoot.toRealPath();
            Path gameRoot = client.runDirectory.toPath().toAbsolutePath().normalize().toRealPath();
            boolean externalWorld = !realWorldRoot.startsWith(gameRoot);
            int dataVersion = readDataVersion(realWorldRoot);
            boolean legacyTextures = dataVersion > 0 && dataVersion < TEXTURE_UPDATE_DATA_VERSION;
            if (!externalWorld && !legacyTextures) {
                return;
            }

            originalEnabledNames = List.copyOf(manager.getEnabledNames());
            LinkedHashSet<String> desired = new LinkedHashSet<>(originalEnabledNames);
            if (legacyTextures) {
                desired.add("programmer_art");
            }
            if (externalWorld) {
                Path sourceInstanceRoot = sourceInstanceRoot(realWorldRoot);
                desired.addAll(linkSelectedSourcePacks(client, sourceInstanceRoot));
            }

            manager.scanPacks();
            desired.removeIf(name -> !manager.hasProfile(name));
            if (desired.equals(new LinkedHashSet<>(originalEnabledNames))) {
                cleanupManagedLinks();
                originalEnabledNames = List.of();
                return;
            }
            manager.setEnabledProfiles(desired);
            active = true;
            client.reloadResources();
        } catch (Exception ignored) {
            cleanupManagedLinks();
            originalEnabledNames = List.of();
            active = false;
        }
    }

    private static synchronized void restore(MinecraftClient client) {
        if (!active) {
            cleanupManagedLinks();
            return;
        }
        ResourcePackManager manager = client.getResourcePackManager();
        if (manager == null) {
            cleanupManagedLinks();
            originalEnabledNames = List.of();
            active = false;
            return;
        }
        List<String> restoreNames = originalEnabledNames;
        active = false;
        originalEnabledNames = List.of();
        manager.scanPacks();
        manager.setEnabledProfiles(restoreNames);
        client.reloadResources().whenComplete((ignored, failure) -> client.execute(() -> {
            cleanupManagedLinks();
            manager.scanPacks();
        }));
    }

    private static int readDataVersion(Path worldRoot) {
        try {
            NbtCompound root = NbtIo.readCompressed(worldRoot.resolve("level.dat").toFile());
            NbtCompound data = root == null ? null : root.getCompound("Data");
            return data == null ? 0 : data.getInt("DataVersion");
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static Path sourceInstanceRoot(Path worldRoot) {
        Path savesRoot = worldRoot == null ? null : worldRoot.getParent();
        return savesRoot == null || savesRoot.getParent() == null ? savesRoot : savesRoot.getParent();
    }

    private static Collection<String> linkSelectedSourcePacks(MinecraftClient client, Path sourceInstanceRoot) {
        if (client == null || sourceInstanceRoot == null) {
            return List.of();
        }
        Path options = firstRegularFile(
                sourceInstanceRoot.resolve("options.txt"),
                sourceInstanceRoot.resolve(".minecraft").resolve("options.txt")
        );
        if (options == null) {
            return List.of();
        }
        List<String> selected = readSelectedPacks(options);
        if (selected.isEmpty()) {
            return List.of();
        }
        Path currentPacks = client.runDirectory.toPath().toAbsolutePath().normalize().resolve("resourcepacks");
        Path sourcePacks = firstDirectory(
                sourceInstanceRoot.resolve("resourcepacks"),
                sourceInstanceRoot.resolve(".minecraft").resolve("resourcepacks")
        );
        LinkedHashSet<String> profileNames = new LinkedHashSet<>();
        try {
            Files.createDirectories(currentPacks);
        } catch (Exception ignored) {
            return List.of();
        }
        for (String selectedName : selected) {
            if (selectedName == null || selectedName.isBlank()) {
                continue;
            }
            if (!selectedName.startsWith("file/")) {
                profileNames.add(selectedName);
                continue;
            }
            if (sourcePacks == null) {
                continue;
            }
            String relativeName = selectedName.substring("file/".length());
            Path source = sourcePacks.resolve(relativeName).normalize();
            if (!source.startsWith(sourcePacks.normalize()) || !Files.exists(source)) {
                continue;
            }
            String sourceFileName = source.getFileName() == null ? "pack" : source.getFileName().toString();
            String linkedName = "koil-instance-" + shortHash(source.toAbsolutePath().normalize().toString()) + "-" + sourceFileName;
            Path link = currentPacks.resolve(linkedName);
            try {
                if (Files.notExists(link)) {
                    Files.createSymbolicLink(link, source.toAbsolutePath().normalize());
                    MANAGED_LINKS.add(link);
                } else if (Files.isSymbolicLink(link)) {
                    MANAGED_LINKS.add(link);
                }
                profileNames.add("file/" + linkedName);
            } catch (Exception ignored) {
            }
        }
        return profileNames;
    }

    private static List<String> readSelectedPacks(Path options) {
        try {
            for (String line : Files.readAllLines(options, StandardCharsets.UTF_8)) {
                if (!line.startsWith("resourcePacks:")) {
                    continue;
                }
                JsonElement parsed = JsonParser.parseString(line.substring("resourcePacks:".length()));
                if (!parsed.isJsonArray()) {
                    return List.of();
                }
                JsonArray array = parsed.getAsJsonArray();
                List<String> values = new ArrayList<>();
                for (JsonElement element : array) {
                    if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                        values.add(element.getAsString());
                    }
                }
                return List.copyOf(values);
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    private static Path firstRegularFile(Path... candidates) {
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static Path firstDirectory(Path... candidates) {
        for (Path candidate : candidates) {
            if (candidate != null && Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static String shortHash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8))).substring(0, 10);
        } catch (Exception ignored) {
            return Integer.toHexString(value == null ? 0 : value.hashCode());
        }
    }

    private static void cleanupManagedLinks() {
        for (Path link : List.copyOf(MANAGED_LINKS)) {
            try {
                if (Files.isSymbolicLink(link)) {
                    Files.deleteIfExists(link);
                }
            } catch (Exception ignored) {
            }
        }
        MANAGED_LINKS.clear();
    }
}
