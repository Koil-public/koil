package com.spirit.koil.api.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

public final class LocalWorldDiscovery {
    private static final int MAX_SCAN_ROOTS = 256;
    private static final int MAX_WORLDS_PER_ROOT = 160;
    private static final int MAX_LAUNCHER_DIRECTORIES = 320;

    private LocalWorldDiscovery() {
    }

    public static List<DiscoveredWorld> discover(boolean includeIncompatible) {
        Map<String, SourceMod> currentMods = currentMods();
        String currentVersion = currentMinecraftVersion();
        List<DiscoveredWorld> worlds = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (Path savesRoot : savesRoots()) {
            if (savesRoot == null || !Files.isDirectory(savesRoot)) {
                continue;
            }
            try (var stream = Files.list(savesRoot)) {
                stream.filter(Files::isDirectory)
                        .limit(MAX_WORLDS_PER_ROOT)
                        .forEach(worldPath -> {
                            Path normalized = worldPath.toAbsolutePath().normalize();
                            if (!seen.add(normalized) || !Files.isRegularFile(normalized.resolve("level.dat"))) {
                                return;
                            }
                            DiscoveredWorld world = inspectWorld(normalized, savesRoot.toAbsolutePath().normalize(), currentVersion, currentMods);
                            if (world != null && (includeIncompatible || world.versionCompatible())) {
                                worlds.add(world);
                            }
                        });
            } catch (Exception ignored) {
            }
        }
        worlds.sort(Comparator.comparingLong(DiscoveredWorld::lastPlayed).reversed().thenComparing(DiscoveredWorld::displayName, String.CASE_INSENSITIVE_ORDER));
        return worlds;
    }

    public static List<Path> savesRoots() {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        addSavesRoot(roots, gameDir.resolve("saves"));
        Path instanceRoot = gameDir.getParent();
        if (instanceRoot != null) {
            addSavesRoot(roots, instanceRoot.resolve("saves"));
            addSavesRoot(roots, instanceRoot.resolve(".minecraft").resolve("saves"));
            scanLauncherSiblings(roots, instanceRoot.getParent());
        }
        addDefaultMinecraftRoots(roots);
        addLauncherRoots(roots);
        return roots.stream().limit(MAX_SCAN_ROOTS).toList();
    }

    public static Path createCurrentInstanceLink(DiscoveredWorld world) throws Exception {
        Path currentSaves = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize().resolve("saves");
        Files.createDirectories(currentSaves);
        String baseName = "koil-external-" + sanitize(world.displayName().isBlank() ? world.folderName() : world.displayName());
        Path link = currentSaves.resolve(baseName);
        int index = 1;
        while (Files.exists(link) && !sameTarget(link, world.worldPath())) {
            link = currentSaves.resolve(baseName + "-" + index++);
        }
        if (!Files.exists(link)) {
            Files.createSymbolicLink(link, world.worldPath());
        }
        return link;
    }

    private static DiscoveredWorld inspectWorld(Path worldPath, Path savesRoot, String currentVersion, Map<String, SourceMod> currentMods) {
        try {
            NbtCompound root = NbtIo.readCompressed(worldPath.resolve("level.dat").toFile());
            NbtCompound data = root == null ? null : root.getCompound("Data");
            if (data == null || data.isEmpty()) {
                return null;
            }
            String folderName = worldPath.getFileName() == null ? "world" : worldPath.getFileName().toString();
            String displayName = string(data, "LevelName", folderName);
            long lastPlayed = data.getLong("LastPlayed");
            int dataVersion = data.getInt("DataVersion");
            String versionName = "";
            if (data.contains("Version")) {
                NbtCompound version = data.getCompound("Version");
                versionName = string(version, "Name", "");
            }
            boolean compatible = versionName.isBlank() || versionName.equalsIgnoreCase(currentVersion);
            Path instanceRoot = savesRoot.getParent() == null ? savesRoot : savesRoot.getParent();
            Map<String, SourceMod> sourceMods = sourceMods(instanceRoot);
            ModComparison comparison = compareMods(currentMods, sourceMods);
            return new DiscoveredWorld(
                    displayName,
                    folderName,
                    worldPath,
                    savesRoot,
                    instanceRoot,
                    instanceRootName(instanceRoot),
                    versionName.isBlank() ? "Unknown" : versionName,
                    currentVersion,
                    dataVersion,
                    lastPlayed,
                    gameModeName(data.getInt("GameType")),
                    data.getBoolean("hardcore"),
                    data.getBoolean("allowCommands"),
                    Files.isRegularFile(worldPath.resolve("icon.png")) ? worldPath.resolve("icon.png") : null,
                    compatible,
                    sourceMods.size(),
                    comparison
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void addSavesRoot(Set<Path> roots, Path root) {
        if (root != null && Files.isDirectory(root)) {
            roots.add(root.toAbsolutePath().normalize());
        }
    }

    private static void scanLauncherSiblings(Set<Path> roots, Path parent) {
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        try (var stream = Files.list(parent)) {
            stream.filter(Files::isDirectory)
                    .limit(MAX_SCAN_ROOTS)
                    .forEach(candidate -> {
                        addSavesRoot(roots, candidate.resolve("saves"));
                        addSavesRoot(roots, candidate.resolve(".minecraft").resolve("saves"));
                    });
        } catch (Exception ignored) {
        }
    }

    private static void addDefaultMinecraftRoots(Set<Path> roots) {
        String homeValue = System.getProperty("user.home", "");
        if (homeValue.isBlank()) {
            return;
        }
        Path home = Path.of(homeValue);
        addSavesRoot(roots, home.resolve(".minecraft").resolve("saves"));
        addSavesRoot(roots, home.resolve("Library").resolve("Application Support").resolve("minecraft").resolve("saves"));
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            addSavesRoot(roots, Path.of(appData).resolve(".minecraft").resolve("saves"));
        }
    }

    private static void addLauncherRoots(Set<Path> roots) {
        String homeValue = System.getProperty("user.home", "");
        if (homeValue.isBlank()) {
            return;
        }
        Path home = Path.of(homeValue);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<Path> candidates = new ArrayList<>();

        addIfDirectory(candidates, home.resolve(".minecraft"));
        addIfDirectory(candidates, home.resolve("curseforge").resolve("minecraft").resolve("Instances"));
        addIfDirectory(candidates, home.resolve("CurseForge").resolve("minecraft").resolve("Instances"));
        addIfDirectory(candidates, home.resolve("PrismLauncher").resolve("instances"));
        addIfDirectory(candidates, home.resolve(".local").resolve("share").resolve("PrismLauncher").resolve("instances"));
        addIfDirectory(candidates, home.resolve("MultiMC").resolve("instances"));
        addIfDirectory(candidates, home.resolve(".local").resolve("share").resolve("MultiMC").resolve("instances"));
        addIfDirectory(candidates, home.resolve("ATLauncher").resolve("instances"));
        addIfDirectory(candidates, home.resolve("GDLauncher").resolve("instances"));
        addIfDirectory(candidates, home.resolve(".gdlauncher").resolve("instances"));
        addIfDirectory(candidates, home.resolve(".technic").resolve("modpacks"));
        addIfDirectory(candidates, home.resolve("ModrinthApp").resolve("profiles"));

        if (os.contains("mac")) {
            Path support = home.resolve("Library").resolve("Application Support");
            addIfDirectory(candidates, support.resolve("minecraft"));
            addIfDirectory(candidates, support.resolve("PrismLauncher").resolve("instances"));
            addIfDirectory(candidates, support.resolve("MultiMC").resolve("instances"));
            addIfDirectory(candidates, support.resolve("CurseForge").resolve("minecraft").resolve("Instances"));
            addIfDirectory(candidates, support.resolve("com.modrinth.theseus").resolve("profiles"));
            addIfDirectory(candidates, support.resolve("ModrinthApp").resolve("profiles"));
            addIfDirectory(candidates, support.resolve("GDLauncher").resolve("instances"));
            addIfDirectory(candidates, support.resolve("ATLauncher").resolve("instances"));
        }

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            Path app = Path.of(appData);
            addIfDirectory(candidates, app.resolve(".minecraft"));
            addIfDirectory(candidates, app.resolve("PrismLauncher").resolve("instances"));
            addIfDirectory(candidates, app.resolve("MultiMC").resolve("instances"));
            addIfDirectory(candidates, app.resolve("CurseForge").resolve("minecraft").resolve("Instances"));
            addIfDirectory(candidates, app.resolve("com.modrinth.theseus").resolve("profiles"));
            addIfDirectory(candidates, app.resolve("ModrinthApp").resolve("profiles"));
            addIfDirectory(candidates, app.resolve("GDLauncher").resolve("instances"));
            addIfDirectory(candidates, app.resolve("ATLauncher").resolve("instances"));
            addIfDirectory(candidates, app.resolve(".technic").resolve("modpacks"));
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            Path local = Path.of(localAppData);
            addIfDirectory(candidates, local.resolve("Packages").resolve("Microsoft.4297127D64EC6_8wekyb3d8bbwe").resolve("LocalCache").resolve("Local").resolve("games").resolve("com.mojang").resolve("minecraftWorlds"));
            addIfDirectory(candidates, local.resolve("ModrinthApp").resolve("profiles"));
            addIfDirectory(candidates, local.resolve("com.modrinth.theseus").resolve("profiles"));
        }

        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.isBlank()) {
            Path xdg = Path.of(xdgDataHome);
            addIfDirectory(candidates, xdg.resolve("PrismLauncher").resolve("instances"));
            addIfDirectory(candidates, xdg.resolve("MultiMC").resolve("instances"));
            addIfDirectory(candidates, xdg.resolve("ModrinthApp").resolve("profiles"));
            addIfDirectory(candidates, xdg.resolve("com.modrinth.theseus").resolve("profiles"));
        }

        for (Path candidate : candidates) {
            addSavesRoot(roots, candidate.resolve("saves"));
            addSavesRoot(roots, candidate.resolve(".minecraft").resolve("saves"));
            scanForNestedSavesRoots(roots, candidate, 4);
        }
    }

    private static void addIfDirectory(List<Path> paths, Path path) {
        if (path != null && Files.isDirectory(path)) {
            paths.add(path.toAbsolutePath().normalize());
        }
    }

    private static void scanForNestedSavesRoots(Set<Path> roots, Path root, int maxDepth) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        Set<Path> visited = new HashSet<>();
        Deque<ScanNode> queue = new ArrayDeque<>();
        queue.add(new ScanNode(root.toAbsolutePath().normalize(), 0));
        int visitedCount = 0;
        while (!queue.isEmpty() && visitedCount < MAX_LAUNCHER_DIRECTORIES && roots.size() < MAX_SCAN_ROOTS) {
            ScanNode node = queue.removeFirst();
            Path path = node.path();
            if (!visited.add(path) || !Files.isDirectory(path)) {
                continue;
            }
            visitedCount++;
            String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
            if ("saves".equals(name)) {
                addSavesRoot(roots, path);
                continue;
            }
            if (node.depth() >= maxDepth || shouldSkipScanDirectory(name)) {
                continue;
            }
            try (var stream = Files.list(path)) {
                stream.filter(Files::isDirectory)
                        .limit(120)
                        .forEach(child -> queue.addLast(new ScanNode(child.toAbsolutePath().normalize(), node.depth() + 1)));
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean shouldSkipScanDirectory(String name) {
        return name.equals("mods")
                || name.equals("resourcepacks")
                || name.equals("shaderpacks")
                || name.equals("screenshots")
                || name.equals("logs")
                || name.equals("crash-reports")
                || name.equals("assets")
                || name.equals("libraries")
                || name.equals("versions")
                || name.equals("cache")
                || name.equals("caches")
                || name.equals("backups")
                || name.equals("downloads")
                || name.equals("instances.json");
    }

    private static Map<String, SourceMod> currentMods() {
        Map<String, SourceMod> mods = new LinkedHashMap<>();
        FabricLoader.getInstance().getAllMods().forEach(container -> {
            String id = container.getMetadata().getId();
            mods.put(id, new SourceMod(id, container.getMetadata().getName(), container.getMetadata().getVersion().getFriendlyString()));
        });
        return mods;
    }

    private static Map<String, SourceMod> sourceMods(Path instanceRoot) {
        Map<String, SourceMod> mods = new LinkedHashMap<>();
        List<Path> modRoots = List.of(instanceRoot.resolve("mods"), instanceRoot.resolve(".minecraft").resolve("mods"));
        for (Path modRoot : modRoots) {
            if (!Files.isDirectory(modRoot)) {
                continue;
            }
            try (var stream = Files.list(modRoot)) {
                stream.filter(path -> path.getFileName() != null && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .limit(500)
                        .forEach(path -> {
                            SourceMod mod = readFabricMod(path);
                            if (mod != null) {
                                mods.putIfAbsent(mod.id(), mod);
                            }
                        });
            } catch (Exception ignored) {
            }
        }
        return mods;
    }

    private static SourceMod readFabricMod(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entry = jar.getEntry("fabric.mod.json");
            if (entry == null) {
                String fileName = jarPath.getFileName() == null ? "unknown" : jarPath.getFileName().toString();
                return new SourceMod(fileName, fileName, "");
            }
            try (var reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                String id = json.has("id") ? json.get("id").getAsString() : jarPath.getFileName().toString();
                String name = json.has("name") ? json.get("name").getAsString() : id;
                String version = json.has("version") ? json.get("version").getAsString() : "";
                return new SourceMod(id, name, version);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static ModComparison compareMods(Map<String, SourceMod> current, Map<String, SourceMod> source) {
        int matching = 0;
        int missing = 0;
        int extra = 0;
        int versionMismatch = 0;
        for (Map.Entry<String, SourceMod> entry : source.entrySet()) {
            SourceMod currentMod = current.get(entry.getKey());
            if (currentMod == null) {
                missing++;
            } else {
                matching++;
                String sourceVersion = entry.getValue().version();
                if (!sourceVersion.isBlank() && !currentMod.version().isBlank() && !sourceVersion.equalsIgnoreCase(currentMod.version())) {
                    versionMismatch++;
                }
            }
        }
        for (String id : current.keySet()) {
            if (!source.containsKey(id)) {
                extra++;
            }
        }
        return new ModComparison(matching, missing, extra, versionMismatch);
    }

    private static String currentMinecraftVersion() {
        return SharedConstants.getGameVersion().getName();
    }

    private static String string(NbtCompound compound, String key, String fallback) {
        return compound != null && compound.contains(key) ? compound.getString(key) : fallback;
    }

    private static String gameModeName(int gameType) {
        return switch (gameType) {
            case 0 -> "Survival";
            case 1 -> "Creative";
            case 2 -> "Adventure";
            case 3 -> "Spectator";
            default -> "Unknown";
        };
    }

    private static String instanceRootName(Path instanceRoot) {
        Path name = instanceRoot == null ? null : instanceRoot.getFileName();
        return name == null ? "Unknown Instance" : name.toString();
    }

    private static boolean sameTarget(Path link, Path target) {
        try {
            return Files.isSymbolicLink(link) && Files.readSymbolicLink(link).toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String sanitize(String value) {
        String cleaned = value == null ? "world" : value.replaceAll("[^A-Za-z0-9._-]+", "-");
        return cleaned.isBlank() ? "world" : cleaned;
    }

    public record DiscoveredWorld(String displayName, String folderName, Path worldPath, Path savesRoot, Path instanceRoot,
                                  String instanceName, String worldVersion, String currentVersion, int dataVersion,
                                  long lastPlayed, String gameMode, boolean hardcore, boolean commandsAllowed,
                                  Path iconPath, boolean versionCompatible, int sourceModCount, ModComparison modComparison) {
    }

    public record SourceMod(String id, String name, String version) {
    }

    public record ModComparison(int matching, int missing, int extra, int versionMismatch) {
        public String summary() {
            return matching + " matching | " + missing + " missing | " + versionMismatch + " version mismatch | " + extra + " current-only";
        }
    }

    private record ScanNode(Path path, int depth) {
    }
}
