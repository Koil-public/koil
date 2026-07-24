package com.spirit.koil.api.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spirit.koil.api.registry.definition.ContentVersionAdapters;
import com.spirit.koil.api.registry.definition.DefinitionParser;
import com.spirit.koil.api.registry.definition.DefinitionValidator;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Scans current-instance world datapacks in place. It never extracts or rewrites source files. */
public final class WorldDatapackScanner {
    private static final int MAX_WORLDS = 512;
    private static final int MAX_PACKS_PER_WORLD = 512;
    private static final int MAX_DEFINITIONS_PER_PACK = 10_000;
    private static final int MAX_ZIP_ENTRIES = 50_000;
    private static final int MAX_JSON_BYTES = 1_048_576;
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern IDENTIFIER_PATH = Pattern.compile("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*");

    private final Path savesRoot;

    public WorldDatapackScanner(Path savesRoot) {
        this.savesRoot = savesRoot.toAbsolutePath().normalize();
    }

    public WorldContentIndex scanAllWorlds() {
        List<WorldContentIndex.WorldEntry> worlds = new ArrayList<>();
        List<WorldContentIndex.ValidationMessage> validation = new ArrayList<>();
        if (!Files.isDirectory(savesRoot)) {
            validation.add(WorldContentIndex.ValidationMessage.warning(
                    "missing_saves_root",
                    "The current instance has no saves directory to scan.",
                    savesRoot.toString(),
                    "Create or import a world, then scan Content again."
            ));
            return new WorldContentIndex(
                    WorldContentIndex.CURRENT_SCHEMA_VERSION,
                    Instant.now().toString(),
                    savesRoot.toString(),
                    worlds,
                    validation
            );
        }

        try (var stream = Files.list(savesRoot)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve("level.dat")))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .limit(MAX_WORLDS)
                    .map(this::scanWorld)
                    .forEach(worlds::add);
        } catch (IOException exception) {
            validation.add(WorldContentIndex.ValidationMessage.error(
                    "world_scan_failed",
                    "Failed to enumerate the current instance worlds: " + exception.getMessage(),
                    savesRoot.toString(),
                    "Check filesystem permissions and retry the scan."
            ));
        }

        return new WorldContentIndex(
                WorldContentIndex.CURRENT_SCHEMA_VERSION,
                Instant.now().toString(),
                savesRoot.toString(),
                worlds,
                validation
        );
    }

    public WorldContentIndex.WorldEntry scanWorld(Path worldPath) {
        Path normalizedWorld = worldPath.toAbsolutePath().normalize();
        List<WorldContentIndex.ValidationMessage> validation = new ArrayList<>();
        WorldMetadata worldMetadata = readWorldMetadata(normalizedWorld, validation);
        List<WorldContentIndex.PackEntry> packs = scanPacks(normalizedWorld, worldMetadata, validation);
        reportDuplicateIds(packs, normalizedWorld, validation);
        String folderName = normalizedWorld.getFileName() == null ? "world" : normalizedWorld.getFileName().toString();
        return new WorldContentIndex.WorldEntry(
                folderName,
                worldMetadata.displayName().isBlank() ? folderName : worldMetadata.displayName(),
                normalizedWorld.toString(),
                worldMetadata.minecraftVersion(),
                worldMetadata.dataVersion(),
                worldMetadata.lastPlayed(),
                packs,
                validation
        );
    }

    private List<WorldContentIndex.PackEntry> scanPacks(
            Path worldPath,
            WorldMetadata worldMetadata,
            List<WorldContentIndex.ValidationMessage> worldValidation
    ) {
        Path datapacksDirectory = worldPath.resolve("datapacks");
        if (!Files.isDirectory(datapacksDirectory)) {
            return List.of();
        }
        List<WorldContentIndex.PackEntry> packs = new ArrayList<>();
        try (var stream = Files.list(datapacksDirectory)) {
            stream.filter(path -> Files.isDirectory(path) || isZip(path))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .limit(MAX_PACKS_PER_WORLD)
                    .forEach(path -> packs.add(scanPack(path, worldMetadata)));
        } catch (IOException exception) {
            worldValidation.add(WorldContentIndex.ValidationMessage.error(
                    "datapack_scan_failed",
                    "Failed to enumerate world datapacks: " + exception.getMessage(),
                    datapacksDirectory.toString(),
                    "Check the datapacks directory and retry."
            ));
        }
        return List.copyOf(packs);
    }

    private WorldContentIndex.PackEntry scanPack(Path packPath, WorldMetadata worldMetadata) {
        String fileName = packPath.getFileName().toString();
        String packId = "file/" + fileName;
        boolean packed = Files.isRegularFile(packPath);
        PackScan scan = new PackScan(packId);
        if (packed) {
            scanZipPack(packPath, scan);
        } else {
            scanFolderPack(packPath, scan);
        }
        scan.validation.addAll(ContentAssetValidator.validate(packPath, packed, scan.definitions));
        String state = packState(packId, fileName, worldMetadata, scan.metadataValid);
        PackMetadata metadata = readPackMetadata(scan.metadata, fileName, packPath, scan.validation);
        return new WorldContentIndex.PackEntry(
                packId,
                fileName,
                packPath.toAbsolutePath().normalize().toString(),
                packed,
                state,
                metadata.displayName(),
                metadata.description(),
                metadata.packFormat(),
                metadata.contentVersion(),
                metadata.minecraftRequirement(),
                sorted(scan.namespaces),
                metadata.dependencies(),
                metadata.requiredMods(),
                scan.metadata,
                scan.definitions,
                scan.validation
        );
    }

    private void scanFolderPack(Path packPath, PackScan scan) {
        Path metadataPath = packPath.resolve("pack.mcmeta");
        if (Files.isRegularFile(metadataPath)) {
            scan.metadata = parseJson(
                    () -> readBounded(metadataPath),
                    metadataPath.toString(),
                    scan.validation,
                    "invalid_pack_metadata"
            );
            scan.metadataValid = !scan.metadata.entrySet().isEmpty();
        } else {
            scan.validation.add(WorldContentIndex.ValidationMessage.error(
                    "missing_pack_mcmeta",
                    "Datapack is missing pack.mcmeta.",
                    packPath.toString(),
                    "Generate a valid pack.mcmeta at the datapack root."
            ));
        }

        Path dataRoot = packPath.resolve("data");
        if (!Files.isDirectory(dataRoot)) {
            return;
        }
        try (var namespaces = Files.list(dataRoot)) {
            namespaces.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .forEach(scan.namespaces::add);
        } catch (IOException ignored) {
        }
        try (var files = Files.walk(dataRoot, 24)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .filter(path -> isRegistryDefinition(packPath.relativize(path).toString().replace('\\', '/')))
                    .limit(MAX_DEFINITIONS_PER_PACK)
                    .forEach(path -> {
                        String relative = packPath.relativize(path).toString().replace('\\', '/');
                        addDefinition(relative, path.toString(), () -> readBounded(path), scan);
                    });
        } catch (IOException exception) {
            scan.validation.add(WorldContentIndex.ValidationMessage.error(
                    "folder_pack_scan_failed",
                    "Failed to scan datapack files: " + exception.getMessage(),
                    packPath.toString(),
                    "Check the datapack directory and retry."
            ));
        }
    }

    private void scanZipPack(Path packPath, PackScan scan) {
        try (ZipFile zip = new ZipFile(packPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int visited = 0;
            int definitions = 0;
            while (entries.hasMoreElements() && visited++ < MAX_ZIP_ENTRIES) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !safeZipPath(entry.getName())) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                rememberZipNamespace(name, scan.namespaces);
                if ("pack.mcmeta".equals(name)) {
                    scan.metadata = parseJson(
                            () -> readBounded(zip, entry),
                            packPath + "!/" + name,
                            scan.validation,
                            "invalid_pack_metadata"
                    );
                    scan.metadataValid = !scan.metadata.entrySet().isEmpty();
                } else if (definitions < MAX_DEFINITIONS_PER_PACK && isRegistryDefinition(name)) {
                    definitions++;
                    String sourcePath = packPath + "!/" + name;
                    addDefinition(name, sourcePath, () -> readBounded(zip, entry), scan);
                }
            }
            if (!scan.metadataValid) {
                scan.validation.add(WorldContentIndex.ValidationMessage.error(
                        "missing_pack_mcmeta",
                        "Datapack zip is missing a valid root pack.mcmeta.",
                        packPath.toString(),
                        "Generate a valid pack.mcmeta at the zip root."
                ));
            }
            if (visited > MAX_ZIP_ENTRIES) {
                scan.validation.add(WorldContentIndex.ValidationMessage.warning(
                        "zip_entry_limit",
                        "Datapack scan stopped at the safe zip-entry limit.",
                        packPath.toString(),
                        "Reduce unrelated files or split the datapack."
                ));
            }
        } catch (IOException exception) {
            scan.validation.add(WorldContentIndex.ValidationMessage.error(
                    "zip_pack_scan_failed",
                    "Failed to read datapack zip: " + exception.getMessage(),
                    packPath.toString(),
                    "Repair or replace the zip and retry."
            ));
        }
    }

    private void addDefinition(String relativePath, String sourcePath, IoSupplier<byte[]> bytes, PackScan scan) {
        ContentRegistryEvents.beforeParse(sourcePath);
        String[] segments = relativePath.split("/");
        if (segments.length < 5) {
            return;
        }
        String namespace = segments[1];
        String typeDirectory = segments[3];
        String fileLocalId = String.join("/", java.util.Arrays.copyOfRange(segments, 4, segments.length));
        fileLocalId = fileLocalId.substring(0, fileLocalId.length() - ".json".length());
        scan.namespaces.add(namespace);

        List<WorldContentIndex.ValidationMessage> validation = new ArrayList<>();
        JsonObject definition = parseJson(bytes, sourcePath, validation, "invalid_definition_json");
        if (!NAMESPACE.matcher(namespace).matches()) {
            validation.add(WorldContentIndex.ValidationMessage.error(
                    "invalid_namespace",
                    "Definition source namespace is invalid: " + namespace,
                    sourcePath,
                    "Use lowercase letters, numbers, underscore, dot, or hyphen."
            ));
        }

        String rawId = string(definition, "id");
        if (definition.has("mod-id")) {
            ContentRegistryEvents.legacyModId(sourcePath);
            validation.add(WorldContentIndex.ValidationMessage.error(
                    "unsupported_legacy_mod_id",
                    "The replacement Content schema does not accept mod-id.",
                    sourcePath,
                    "Remove mod-id and set id to the definition's local or namespaced content id."
            ));
        }
        if (rawId.isBlank()) {
            validation.add(WorldContentIndex.ValidationMessage.error(
                    "missing_id",
                    "Content definition is missing id.",
                    sourcePath,
                    "Add an id such as \"" + fileLocalId + "\"."
            ));
        }

        String explicitNamespace = string(definition, "namespace");
        if (!explicitNamespace.isBlank() && !explicitNamespace.equals(namespace)) {
            validation.add(WorldContentIndex.ValidationMessage.error(
                    "namespace_mismatch",
                    "Definition namespace does not match its datapack path namespace.",
                    sourcePath,
                    "Use namespace \"" + namespace + "\" or move the file to the matching data namespace."
            ));
        }

        String idNamespace = namespace;
        String localId = rawId;
        int separator = rawId.indexOf(':');
        if (separator >= 0) {
            idNamespace = rawId.substring(0, separator);
            localId = rawId.substring(separator + 1);
            if (!idNamespace.equals(namespace)) {
                validation.add(WorldContentIndex.ValidationMessage.error(
                        "id_namespace_mismatch",
                        "Namespaced id does not match the source datapack namespace.",
                        sourcePath,
                        "Use " + namespace + ":" + localId + " or move the source file."
                ));
            }
        }
        if (!localId.isBlank() && !IDENTIFIER_PATH.matcher(localId).matches()) {
            validation.add(WorldContentIndex.ValidationMessage.error(
                    "invalid_id",
                    "Content id path is invalid: " + localId,
                    sourcePath,
                    "Use lowercase identifier path characters only."
            ));
        }
        if (!rawId.isBlank() && !localId.equals(fileLocalId)) {
            validation.add(WorldContentIndex.ValidationMessage.warning(
                    "id_path_mismatch",
                    "Definition id does not match its registry file path.",
                    sourcePath,
                    "Rename the file or id so both resolve to " + namespace + ":" + fileLocalId + "."
            ));
        }

        String declaredType = string(definition, "type");
        String expectedType = singularType(typeDirectory);
        if (declaredType.isBlank()) {
            validation.add(WorldContentIndex.ValidationMessage.error(
                    "missing_type",
                    "Content definition is missing type.",
                    sourcePath,
                    "Add \"type\": \"" + expectedType + "\"."
            ));
        } else if (!declaredType.equals(expectedType)) {
            validation.add(WorldContentIndex.ValidationMessage.error(
                    "type_path_mismatch",
                    "Definition type \"" + declaredType + "\" does not match registry/" + typeDirectory + ".",
                    sourcePath,
                    "Use type \"" + expectedType + "\" or move the definition."
            ));
        }

        String resolvedId = rawId.isBlank() ? namespace + ":" + fileLocalId : idNamespace + ":" + localId;
        WorldContentIndex.DefinitionEntry typedValidationSource = new WorldContentIndex.DefinitionEntry(
                resolvedId,
                localId.isBlank() ? fileLocalId : localId,
                namespace,
                expectedType,
                sourcePath,
                scan.packId,
                definition,
                true,
                validation
        );
        var parsedDefinition = DefinitionParser.parse(typedValidationSource);
        ContentRegistryEvents.afterParse(parsedDefinition);
        validation.addAll(DefinitionValidator.validate(parsedDefinition, ContentVersionAdapters.current()));
        boolean activatable = validation.stream().noneMatch(WorldContentIndex.ValidationMessage::blocksActivation);
        scan.definitions.add(new WorldContentIndex.DefinitionEntry(
                typedValidationSource.id(),
                typedValidationSource.localId(),
                typedValidationSource.namespace(),
                typedValidationSource.type(),
                typedValidationSource.sourcePath(),
                typedValidationSource.packId(),
                typedValidationSource.definition(),
                activatable,
                validation
        ));
    }

    private WorldMetadata readWorldMetadata(Path worldPath, List<WorldContentIndex.ValidationMessage> validation) {
        Path levelDat = worldPath.resolve("level.dat");
        try {
            NbtCompound root = NbtIo.readCompressed(levelDat.toFile());
            NbtCompound data = root == null ? new NbtCompound() : root.getCompound("Data");
            NbtCompound version = data.getCompound("Version");
            NbtCompound dataPacks = data.getCompound("DataPacks");
            return new WorldMetadata(
                    data.getString("LevelName"),
                    version.getString("Name"),
                    data.getInt("DataVersion"),
                    data.getLong("LastPlayed"),
                    readStringSet(dataPacks.getList("Enabled", NbtElement.STRING_TYPE)),
                    readStringSet(dataPacks.getList("Disabled", NbtElement.STRING_TYPE))
            );
        } catch (Exception exception) {
            validation.add(WorldContentIndex.ValidationMessage.error(
                    "level_dat_read_failed",
                    "Failed to read world metadata: " + exception.getMessage(),
                    levelDat.toString(),
                    "Open the world in Minecraft or restore a valid level.dat."
            ));
            return new WorldMetadata("", "", 0, 0L, Set.of(), Set.of());
        }
    }

    private PackMetadata readPackMetadata(
            JsonObject root,
            String fileName,
            Path source,
            List<WorldContentIndex.ValidationMessage> validation
    ) {
        JsonObject pack = object(root, "pack");
        int packFormat = integer(pack, "pack_format", -1);
        if (packFormat < 0) {
            validation.add(WorldContentIndex.ValidationMessage.error(
                    "missing_pack_format",
                    "pack.mcmeta is missing pack.pack_format.",
                    source.toString(),
                    "Set the pack format required by the target Minecraft version."
            ));
        }
        String description = displayJson(pack.get("description"));
        JsonObject registry = object(root, "koil:registry");
        if (registry.entrySet().isEmpty()) {
            registry = object(root, "registry");
        }
        return new PackMetadata(
                string(registry, "name").isBlank() ? fileName : string(registry, "name"),
                description,
                packFormat,
                string(registry, "version"),
                displayJson(registry.get("minecraft")),
                strings(array(registry, "dependencies")),
                strings(array(registry, "mods"))
        );
    }

    private static void reportDuplicateIds(
            List<WorldContentIndex.PackEntry> packs,
            Path worldPath,
            List<WorldContentIndex.ValidationMessage> validation
    ) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (WorldContentIndex.PackEntry pack : packs) {
            for (WorldContentIndex.DefinitionEntry definition : pack.definitions()) {
                counts.merge(definition.id(), 1, Integer::sum);
            }
        }
        counts.forEach((id, count) -> {
            if (count > 1) {
                validation.add(WorldContentIndex.ValidationMessage.warning(
                        "duplicate_available_id",
                        count + " installed datapack definitions use id " + id + ".",
                        worldPath.toString(),
                        "Only one enabled pack may own an id; active conflicts are blocked."
                ));
            }
        });
    }

    private static JsonObject parseJson(
            IoSupplier<byte[]> bytes,
            String sourcePath,
            List<WorldContentIndex.ValidationMessage> validation,
            String errorCode
    ) {
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes.get(), StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("root must be a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (Exception exception) {
            validation.add(WorldContentIndex.ValidationMessage.error(
                    errorCode,
                    "Failed to parse JSON: " + exception.getMessage(),
                    sourcePath,
                    "Repair the JSON and scan Content again."
            ));
            return new JsonObject();
        }
    }

    private static byte[] readBounded(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return readBounded(input, path.toString());
        }
    }

    private static byte[] readBounded(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            return readBounded(input, entry.getName());
        }
    }

    private static byte[] readBounded(InputStream input, String source) throws IOException {
        byte[] bytes = input.readNBytes(MAX_JSON_BYTES + 1);
        if (bytes.length > MAX_JSON_BYTES) {
            throw new IOException("JSON exceeds " + MAX_JSON_BYTES + " bytes: " + source);
        }
        return bytes;
    }

    private static String packState(String packId, String fileName, WorldMetadata metadata, boolean metadataValid) {
        if (!metadataValid) {
            return "invalid";
        }
        if (containsPack(metadata.enabledPacks(), packId, fileName)) {
            return "enabled";
        }
        if (containsPack(metadata.disabledPacks(), packId, fileName)) {
            return "disabled";
        }
        return "available";
    }

    private static boolean containsPack(Set<String> values, String packId, String fileName) {
        return values.contains(packId) || values.contains(fileName) || values.contains("file/" + fileName);
    }

    private static Set<String> readStringSet(NbtList list) {
        Set<String> values = new LinkedHashSet<>();
        for (int index = 0; index < list.size(); index++) {
            values.add(list.getString(index));
        }
        return Set.copyOf(values);
    }

    private static void rememberZipNamespace(String entry, Set<String> namespaces) {
        String[] segments = entry.split("/");
        if (segments.length >= 2 && "data".equals(segments[0])) {
            namespaces.add(segments[1]);
        }
    }

    private static boolean isRegistryDefinition(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        String[] segments = normalized.split("/");
        return segments.length >= 5
                && "data".equals(segments[0])
                && "registry".equals(segments[2])
                && normalized.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    private static boolean safeZipPath(String entry) {
        return !entry.startsWith("/") && !entry.startsWith("\\") && !entry.contains("../") && !entry.contains("..\\");
    }

    private static boolean isZip(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private static String singularType(String typeDirectory) {
        return switch (typeDirectory) {
            case "items" -> "item";
            case "blocks" -> "block";
            case "entities" -> "entity";
            case "particles" -> "particle";
            case "effects" -> "effect";
            case "sounds" -> "sound";
            case "creative_tabs" -> "creative_tab";
            case "recipes" -> "recipe";
            case "tags" -> "tag";
            default -> typeDirectory.endsWith("s") && typeDirectory.length() > 1
                    ? typeDirectory.substring(0, typeDirectory.length() - 1)
                    : typeDirectory;
        };
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString().trim() : "";
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        try {
            return value == null ? fallback : value.getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static List<String> strings(JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                values.add(element.getAsString());
            }
        }
        return List.copyOf(values);
    }

    private static String displayJson(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private record WorldMetadata(
            String displayName,
            String minecraftVersion,
            int dataVersion,
            long lastPlayed,
            Set<String> enabledPacks,
            Set<String> disabledPacks
    ) {
    }

    private record PackMetadata(
            String displayName,
            String description,
            int packFormat,
            String contentVersion,
            String minecraftRequirement,
            List<String> dependencies,
            List<String> requiredMods
    ) {
    }

    private static final class PackScan {
        private final String packId;
        private final Set<String> namespaces = new LinkedHashSet<>();
        private final List<WorldContentIndex.DefinitionEntry> definitions = new ArrayList<>();
        private final List<WorldContentIndex.ValidationMessage> validation = new ArrayList<>();
        private JsonObject metadata = new JsonObject();
        private boolean metadataValid;

        private PackScan(String packId) {
            this.packId = packId;
        }
    }
}
