package com.spirit.koil.api.design.particle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

/**
 * Detached index of Minecraft/mod data-pack world-generation resources.
 *
 * <p>The playground cannot depend on a ClientWorld or integrated server, so the
 * catalog is built directly from Fabric mod-container roots. Minecraft itself is
 * a Fabric loader container and therefore contributes its vanilla data files in
 * the same way as mods. This keeps the authoring browser usable from the title
 * screen while still exposing actual biome/feature/structure/noise resources.</p>
 */
public final class SpriteWorldgenCatalog {
    private SpriteWorldgenCatalog() { }

    public enum Kind {
        BIOME("Biomes", "Biome", "worldgen/biome", ".json"),
        CONFIGURED_FEATURE("Features", "Feature", "worldgen/configured_feature", ".json"),
        PLACED_FEATURE("Placed", "Placed", "worldgen/placed_feature", ".json"),
        STRUCTURE("Structures", "Structure", "worldgen/structure", ".json"),
        STRUCTURE_TEMPLATE("Templates", "Template", "structures", ".nbt"),
        TEMPLATE_POOL("Pools", "Pool", "worldgen/template_pool", ".json"),
        PROCESSOR_LIST("Processors", "Processor", "worldgen/processor_list", ".json"),
        STRUCTURE_SET("Sets", "Set", "worldgen/structure_set", ".json"),
        CONFIGURED_CARVER("Carvers", "Carver", "worldgen/configured_carver", ".json"),
        DIMENSION("Dimensions", "Dimension", "dimension", ".json"),
        DIMENSION_TYPE("Dim Types", "Dim Type", "dimension_type", ".json"),
        WORLD_PRESET("Presets", "Preset", "worldgen/world_preset", ".json"),
        FLAT_LEVEL_GENERATOR_PRESET("Flat Presets", "Flat", "worldgen/flat_level_generator_preset", ".json"),
        MULTI_NOISE_PARAMETER_LIST("Biome Sources", "Biome Src", "worldgen/multi_noise_biome_source_parameter_list", ".json"),
        NOISE_SETTINGS("Terrain", "Terrain", "worldgen/noise_settings", ".json"),
        DENSITY_FUNCTION("Density", "Density", "worldgen/density_function", ".json"),
        NOISE("Noise", "Noise", "worldgen/noise", ".json");

        private final String label;
        private final String shortLabel;
        private final String folder;
        private final String extension;

        Kind(String label, String shortLabel, String folder, String extension) {
            this.label = label;
            this.shortLabel = shortLabel;
            this.folder = folder;
            this.extension = extension;
        }

        public String label() { return label; }
        public String shortLabel() { return shortLabel; }
        public String folder() { return folder; }
        public String extension() { return extension; }
    }

    public record Entry(Kind kind, Identifier id, String provider, Path path, String summary) {
        public String searchable() {
            return (kind.name() + " " + kind.label() + " " + id + " " + provider + " " + summary)
                    .toLowerCase(Locale.ROOT);
        }

        public String label() {
            String path = id == null ? "unknown" : id.getPath();
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
    }

    private static volatile List<Entry> cached = List.of();
    private static volatile boolean loaded;
    private static final Map<Identifier, Set<Identifier>> BIOME_TAG_CACHE = new LinkedHashMap<>();

    public static List<Entry> entries() {
        if (!loaded) reload();
        return cached;
    }

    public static synchronized List<Entry> reload() {
        Map<String, Entry> found = new LinkedHashMap<>();
        BIOME_TAG_CACHE.clear();
        try {
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                if (mod == null) continue;
                String provider = mod.getMetadata() == null ? "unknown" : mod.getMetadata().getId();
                for (Path root : mod.getRootPaths()) {
                    if (root == null) continue;
                    Path data = root.resolve("data");
                    if (!Files.isDirectory(data)) continue;
                    try (var namespaces = Files.list(data)) {
                        namespaces.filter(Files::isDirectory).forEach(namespaceRoot -> {
                            String namespace = namespaceRoot.getFileName().toString();
                            if (namespace.isBlank() || namespace.startsWith("__")) return;
                            for (Kind kind : Kind.values()) {
                                scanKind(found, provider, namespace, namespaceRoot, kind);
                            }
                        });
                    } catch (IOException ignored) { }
                }
            }
        } catch (RuntimeException ignored) {
            // Catalog failure must not make the detached playground unusable.
        }
        List<Entry> result = new ArrayList<>(found.values());
        result.sort(Comparator.comparing((Entry e) -> e.kind().ordinal())
                .thenComparing(e -> e.id().getNamespace())
                .thenComparing(e -> e.id().getPath()));
        cached = List.copyOf(result);
        loaded = true;
        return cached;
    }

    private static void scanKind(Map<String, Entry> found, String provider, String namespace,
                                 Path namespaceRoot, Kind kind) {
        Path base = namespaceRoot.resolve(kind.folder());
        if (!Files.isDirectory(base)) return;
        try (var stream = Files.walk(base)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(kind.extension())) return;
                Path relative = base.relativize(file);
                String idPath = relative.toString().replace('\\', '/');
                idPath = idPath.substring(0, idPath.length() - kind.extension().length());
                Identifier id = Identifier.tryParse(namespace + ":" + idPath);
                if (id == null) return;
                String summary = kind == Kind.STRUCTURE_TEMPLATE ? structureSummary(file) : jsonSummary(file, kind);
                Entry entry = new Entry(kind, id, provider, file, summary);
                found.put(kind.name() + "|" + id, entry);
            });
        } catch (IOException | RuntimeException ignored) { }
    }

    public static List<Entry> search(Kind kind, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries()) {
            if (kind != null && entry.kind() != kind) continue;
            if (!needle.isEmpty() && !entry.searchable().contains(needle)) continue;
            out.add(entry);
        }
        return List.copyOf(out);
    }

    public static Optional<Entry> find(Kind kind, Identifier id) {
        if (kind == null || id == null) return Optional.empty();
        for (Entry entry : entries()) if (entry.kind() == kind && id.equals(entry.id())) return Optional.of(entry);
        return Optional.empty();
    }

    public static Optional<Entry> findAny(Identifier id) {
        if (id == null) return Optional.empty();
        for (Entry entry : entries()) if (id.equals(entry.id())) return Optional.of(entry);
        return Optional.empty();
    }


    /**
     * Tests a structure/biome selector directly from data-pack resources.
     *
     * <p>Structure JSON uses a RegistryEntryList for its {@code biomes} field,
     * which may be a direct biome id, a list, or a {@code #worldgen/biome}
     * tag. The detached playground cannot assume a ClientWorld/registry manager
     * exists, so Koil resolves those selectors from the same Fabric mod roots
     * used by this catalog. Nested tags and {@code replace:true} are honored.
     * This is also the mod-compatibility path for structure biome rules.</p>
     */
    public static boolean biomeSelectorContains(JsonElement selector, Identifier biomeId) {
        if (selector == null || selector.isJsonNull() || biomeId == null) return false;
        if (selector.isJsonArray()) {
            for (JsonElement child : selector.getAsJsonArray()) {
                if (biomeSelectorContains(child, biomeId)) return true;
            }
            return false;
        }
        if (selector.isJsonObject()) {
            JsonObject object = selector.getAsJsonObject();
            for (String key : List.of("id", "value", "values", "biomes")) {
                if (object.has(key) && biomeSelectorContains(object.get(key), biomeId)) return true;
            }
            return false;
        }
        if (!selector.isJsonPrimitive() || !selector.getAsJsonPrimitive().isString()) return false;
        String raw = selector.getAsString().trim();
        if (raw.isEmpty()) return false;
        if (raw.charAt(0) == '#') {
            Identifier tag = Identifier.tryParse(raw.substring(1));
            return tag != null && biomeTagMembers(tag).contains(biomeId);
        }
        Identifier direct = Identifier.tryParse(raw);
        return biomeId.equals(direct);
    }

    private static Set<Identifier> biomeTagMembers(Identifier tagId) {
        synchronized (BIOME_TAG_CACHE) {
            Set<Identifier> cachedTag = BIOME_TAG_CACHE.get(tagId);
            if (cachedTag != null) return cachedTag;
            Set<Identifier> resolved = Set.copyOf(resolveBiomeTag(tagId, new HashSet<>()));
            BIOME_TAG_CACHE.put(tagId, resolved);
            return resolved;
        }
    }

    private static Set<Identifier> resolveBiomeTag(Identifier tagId, Set<Identifier> visiting) {
        LinkedHashSet<Identifier> values = new LinkedHashSet<>();
        if (tagId == null || !visiting.add(tagId)) return values;
        try {
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                if (mod == null) continue;
                for (Path root : mod.getRootPaths()) {
                    if (root == null) continue;
                    Path file = root.resolve("data")
                            .resolve(tagId.getNamespace())
                            .resolve("tags/worldgen/biome")
                            .resolve(tagId.getPath() + ".json");
                    if (!Files.isRegularFile(file)) continue;
                    try {
                        JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                        if (!parsed.isJsonObject()) continue;
                        JsonObject object = parsed.getAsJsonObject();
                        if (object.has("replace") && object.get("replace").isJsonPrimitive()
                                && object.get("replace").getAsBoolean()) values.clear();
                        JsonElement entries = object.get("values");
                        if (entries == null || !entries.isJsonArray()) continue;
                        for (JsonElement value : entries.getAsJsonArray()) {
                            String raw = null;
                            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                                raw = value.getAsString();
                            } else if (value.isJsonObject() && value.getAsJsonObject().has("id")) {
                                raw = value.getAsJsonObject().get("id").getAsString();
                            }
                            if (raw == null || raw.isBlank()) continue;
                            raw = raw.trim();
                            if (raw.startsWith("#")) {
                                Identifier nested = Identifier.tryParse(raw.substring(1));
                                if (nested != null) values.addAll(resolveBiomeTag(nested, visiting));
                            } else {
                                Identifier id = Identifier.tryParse(raw);
                                if (id != null) values.add(id);
                            }
                        }
                    } catch (IOException | RuntimeException ignored) { }
                }
            }
        } catch (RuntimeException ignored) {
            // Detached catalog lookups must never make the title-screen tools fail.
        } finally {
            visiting.remove(tagId);
        }
        return values;
    }

    public static Optional<JsonObject> readJson(Entry entry) {
        if (entry == null || entry.kind() == Kind.STRUCTURE_TEMPLATE || entry.path() == null) return Optional.empty();
        try {
            JsonElement value = JsonParser.parseString(Files.readString(entry.path(), StandardCharsets.UTF_8));
            return value != null && value.isJsonObject() ? Optional.of(value.getAsJsonObject()) : Optional.empty();
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public static Optional<NbtCompound> readStructure(Entry entry) {
        if (entry == null || entry.kind() != Kind.STRUCTURE_TEMPLATE || entry.path() == null) return Optional.empty();
        try (InputStream input = Files.newInputStream(entry.path())) {
            return Optional.ofNullable(NbtIo.readCompressed(input));
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static String jsonSummary(Path path, Kind kind) {
        try {
            JsonElement element = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) return kind.shortLabel();
            JsonObject root = element.getAsJsonObject();
            if (root.has("type") && root.get("type").isJsonPrimitive()) return root.get("type").getAsString();
            if (kind == Kind.DIMENSION && root.has("generator") && root.get("generator").isJsonObject()) {
                JsonObject generator = root.getAsJsonObject("generator");
                if (generator.has("type") && generator.get("type").isJsonPrimitive()) return generator.get("type").getAsString();
            }
            if (kind == Kind.NOISE_SETTINGS && root.has("sea_level")) return "sea " + root.get("sea_level").getAsString();
            return kind.shortLabel();
        } catch (IOException | RuntimeException ignored) {
            return kind.shortLabel();
        }
    }

    private static int[] structureSize(NbtCompound nbt) {
        if (nbt == null) return new int[0];
        int[] packed = nbt.getIntArray("size");
        if (packed.length >= 3) return packed;
        NbtList listed = nbt.getList("size", NbtElement.INT_TYPE);
        if (listed.size() >= 3) {
            return new int[]{listed.getInt(0), listed.getInt(1), listed.getInt(2)};
        }
        return packed;
    }

    private static String structureSummary(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            NbtCompound nbt = NbtIo.readCompressed(input);
            int[] size = structureSize(nbt);
            if (size.length >= 3) return size[0] + "x" + size[1] + "x" + size[2];
        } catch (IOException | RuntimeException ignored) { }
        return "NBT template";
    }
}
