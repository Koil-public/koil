package com.spirit.koil.api.registry.dev;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.spirit.koil.api.registry.WorldContentIndex;
import com.spirit.koil.api.registry.WorldDatapackScanner;
import net.minecraft.MinecraftVersion;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Build-time proof for folder-pack discovery, new-schema parsing, and world pack state. */
public final class ContentRegistryProof {
    private ContentRegistryProof() {
    }

    public static void main(String[] arguments) throws Exception {
        Path proofRoot = arguments.length == 0
                ? Path.of("build", "content-registry-proof")
                : Path.of(arguments[0]);
        Path currentProof = proofRoot.resolve("current");
        Path saves = currentProof.resolve("saves");
        Path folderWorld = saves.resolve("folder-proof-world");
        Files.createDirectories(folderWorld.resolve("datapacks"));
        writeLevelDat(folderWorld.resolve("level.dat"), ContentTestDatapackGenerator.PACK_DIRECTORY);
        Path folderPack = ContentTestDatapackGenerator.generate(folderWorld.resolve("datapacks"));
        validateJsonTree(folderPack);

        String zipFileName = ContentTestDatapackGenerator.PACK_DIRECTORY + ".zip";
        Path zipWorld = saves.resolve("zip-proof-world");
        Files.createDirectories(zipWorld.resolve("datapacks"));
        writeLevelDat(zipWorld.resolve("level.dat"), zipFileName);
        Path stagingPack = ContentTestDatapackGenerator.generate(currentProof.resolve("zip-staging"));
        zipPack(stagingPack, zipWorld.resolve("datapacks").resolve(zipFileName));

        WorldContentIndex index = new WorldDatapackScanner(saves).scanAllWorlds();
        require(index.worlds().size() == 2, "expected folder and zip proof worlds");
        for (WorldContentIndex.WorldEntry worldEntry : index.worlds()) {
            require(worldEntry.packs().size() == 1, "expected exactly one proof datapack in " + worldEntry.worldId());
            WorldContentIndex.PackEntry pack = worldEntry.packs().get(0);
            require("enabled".equals(pack.state()), "proof datapack was not detected as enabled");
            require(pack.definitions().size() == 3, "expected item, tool, and block definitions");
            require(pack.definitions().stream().allMatch(WorldContentIndex.DefinitionEntry::activatable), "a proof definition failed validation");
            require(pack.definitions().stream().allMatch(definition -> definition.definition().has("extensions")), "extension fields were not preserved");
        }
        require(index.validation().isEmpty(), "index-level validation unexpectedly failed");
        String serializedIndex = new GsonBuilder().setPrettyPrinting().create().toJson(index);
        require(JsonParser.parseString(serializedIndex).isJsonObject(), "generated world index did not serialize as JSON");

        System.out.println(
                "Content registry proof passed: folder+zip, " + index.worlds().size() + " worlds, "
                        + index.definitionCount() + " indexed definitions"
        );
    }

    private static void writeLevelDat(Path levelDat, String packFileName) throws Exception {
        NbtCompound root = new NbtCompound();
        NbtCompound data = new NbtCompound();
        NbtCompound version = new NbtCompound();
        NbtCompound dataPacks = new NbtCompound();
        NbtList enabled = new NbtList();
        enabled.add(NbtString.of("vanilla"));
        enabled.add(NbtString.of("file/" + packFileName));
        dataPacks.put("Enabled", enabled);
        dataPacks.put("Disabled", new NbtList());
        version.putString("Name", MinecraftVersion.CURRENT.getName());
        data.putString("LevelName", "Content Registry Proof");
        data.putInt("DataVersion", 3465);
        data.putLong("LastPlayed", System.currentTimeMillis());
        data.put("Version", version);
        data.put("DataPacks", dataPacks);
        root.put("Data", data);
        NbtIo.writeCompressed(root, levelDat.toFile());
    }

    private static void zipPack(Path sourcePack, Path zipPath) throws Exception {
        Files.createDirectories(zipPath.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zipPath));
             var paths = Files.walk(sourcePack)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList()) {
                String relative = sourcePack.relativize(path).toString().replace('\\', '/');
                output.putNextEntry(new ZipEntry(relative));
                Files.copy(path, output);
                output.closeEntry();
            }
        }
    }

    private static void validateJsonTree(Path pack) throws Exception {
        try (var paths = Files.walk(pack)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".json") || file.getFileName().toString().equals("pack.mcmeta"))
                    .toList()) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    JsonParser.parseReader(reader);
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
