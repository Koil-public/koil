package com.spirit.koil.api.registry.dev;

import com.spirit.koil.api.registry.WorldContentIndex;
import com.spirit.koil.api.registry.WorldDatapackScanner;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Installs and enables the generated Content test datapack in one explicit local world. */
public final class ContentTestWorldInstaller {
    private ContentTestWorldInstaller() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected one explicit world directory argument.");
        }
        Path world = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path levelDat = world.resolve("level.dat");
        if (!Files.isRegularFile(levelDat)) {
            throw new IllegalArgumentException("Not a Minecraft Java world: " + world);
        }

        Path backup = world.resolve("level.dat.koil-content-test-backup");
        if (Files.notExists(backup)) {
            Files.copy(levelDat, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }

        Path generatedPack = ContentTestDatapackGenerator.generate(world.resolve("datapacks"));
        enablePack(levelDat, "file/" + ContentTestDatapackGenerator.PACK_DIRECTORY);
        verify(world);
        System.out.println("Installed and enabled Content test datapack: " + generatedPack);
        System.out.println("Recoverable level.dat backup: " + backup);
    }

    private static void enablePack(Path levelDat, String packId) throws Exception {
        NbtCompound root = NbtIo.readCompressed(levelDat.toFile());
        if (root == null || !root.contains("Data", NbtElement.COMPOUND_TYPE)) {
            throw new IllegalStateException("level.dat has no Data compound: " + levelDat);
        }
        NbtCompound data = root.getCompound("Data");
        NbtCompound dataPacks = data.getCompound("DataPacks");
        NbtList enabled = without(dataPacks.getList("Enabled", NbtElement.STRING_TYPE), packId);
        enabled.add(NbtString.of(packId));
        NbtList disabled = without(dataPacks.getList("Disabled", NbtElement.STRING_TYPE), packId);
        dataPacks.put("Enabled", enabled);
        dataPacks.put("Disabled", disabled);
        data.put("DataPacks", dataPacks);
        root.put("Data", data);

        Path temporary = levelDat.resolveSibling("level.dat.koil-content-test.tmp");
        NbtIo.writeCompressed(root, temporary.toFile());
        try {
            Files.move(temporary, levelDat, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, levelDat, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static NbtList without(NbtList source, String value) {
        NbtList result = new NbtList();
        for (int index = 0; index < source.size(); index++) {
            String existing = source.getString(index);
            if (!value.equals(existing)) {
                result.add(NbtString.of(existing));
            }
        }
        return result;
    }

    private static void verify(Path world) {
        WorldContentIndex.WorldEntry entry = new WorldDatapackScanner(world.getParent()).scanWorld(world);
        WorldContentIndex.PackEntry pack = entry.packs().stream()
                .filter(candidate -> candidate.packId().equals("file/" + ContentTestDatapackGenerator.PACK_DIRECTORY))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Generated datapack was not discovered."));
        if (!"enabled".equals(pack.state())) {
            throw new IllegalStateException("Generated datapack was not enabled in level.dat.");
        }
        if (pack.definitions().size() != 3 || pack.definitions().stream().anyMatch(definition -> !definition.activatable())) {
            throw new IllegalStateException("Generated datapack did not expose three valid definitions.");
        }
    }
}
