package com.spirit.koil.api.registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resolves the client-resource sources owned by the active world's Content.
 * Source datapacks are read in place and remain authoritative.
 */
public final class ActiveContentResourcePackSet {
    private static final int MAX_ZIP_ENTRIES = 50_000;

    private ActiveContentResourcePackSet() {
    }

    public static List<PackSource> resolve(
            WorldContentIndex index,
            WorldContentIndex.ActiveWorldSnapshot snapshot
    ) {
        if (index == null || snapshot == null || snapshot.worldPath().isBlank()) {
            return List.of();
        }
        WorldContentIndex.WorldEntry world = index.worlds().stream()
                .filter(candidate -> samePath(candidate.worldPath(), snapshot.worldPath()))
                .findFirst()
                .orElse(null);
        if (world == null) {
            return List.of();
        }

        LinkedHashSet<String> selectedPackIds = new LinkedHashSet<>();
        snapshot.definitions().forEach(definition -> selectedPackIds.add(definition.packId()));
        includeDependencies(world, selectedPackIds);

        Path datapacksRoot = Path.of(world.worldPath()).toAbsolutePath().normalize().resolve("datapacks");
        List<PackSource> sources = new ArrayList<>();
        for (WorldContentIndex.PackEntry pack : world.packs()) {
            if (!selectedPackIds.contains(pack.packId())) {
                continue;
            }
            Path source;
            try {
                source = Path.of(pack.sourcePath()).toAbsolutePath().normalize();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (!source.startsWith(datapacksRoot) || !Files.exists(source) || !containsClientAssets(source, pack.packed())) {
                continue;
            }
            sources.add(new PackSource(
                    "koil_content/" + shortHash(source.toString()),
                    pack.displayName().isBlank() ? pack.fileName() : pack.displayName(),
                    pack.packId(),
                    world.worldId(),
                    source,
                    pack.packed()
            ));
        }
        return List.copyOf(sources);
    }

    private static void includeDependencies(
            WorldContentIndex.WorldEntry world,
            LinkedHashSet<String> selectedPackIds
    ) {
        boolean changed;
        int passes = 0;
        do {
            changed = false;
            for (WorldContentIndex.PackEntry pack : world.packs()) {
                if (!selectedPackIds.contains(pack.packId())) {
                    continue;
                }
                for (String dependency : pack.dependencies()) {
                    WorldContentIndex.PackEntry resolved = findPack(world, dependency);
                    if (resolved != null && selectedPackIds.add(resolved.packId())) {
                        changed = true;
                    }
                }
            }
        } while (changed && ++passes < world.packs().size());
    }

    private static WorldContentIndex.PackEntry findPack(
            WorldContentIndex.WorldEntry world,
            String requested
    ) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        return world.packs().stream()
                .filter(pack -> requested.equals(pack.packId())
                        || requested.equals(pack.fileName())
                        || ("file/" + requested).equals(pack.packId()))
                .findFirst()
                .orElse(null);
    }

    private static boolean containsClientAssets(Path source, boolean packed) {
        if (!packed) {
            return Files.isDirectory(source.resolve("assets"));
        }
        try (ZipFile zip = new ZipFile(source.toFile())) {
            var entries = zip.entries();
            int visited = 0;
            while (entries.hasMoreElements() && visited++ < MAX_ZIP_ENTRIES) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (!entry.isDirectory() && name.startsWith("assets/") && name.length() > "assets/".length()) {
                    return true;
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private static boolean samePath(String left, String right) {
        try {
            return Path.of(left).toAbsolutePath().normalize().equals(Path.of(right).toAbsolutePath().normalize());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 0, 8);
        } catch (Exception ignored) {
            return Integer.toUnsignedString(value.hashCode(), 16);
        }
    }

    public record PackSource(
            String profileName,
            String displayName,
            String packId,
            String worldId,
            Path sourcePath,
            boolean packed
    ) {
        public PackSource {
            sourcePath = sourcePath.toAbsolutePath().normalize();
        }
    }
}
