package com.spirit.koil.api.design.particle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Minecraft data/runtime services that remain available while no gameplay world,
 * integrated server, connection, or player exists.
 *
 * <p>This is deliberately <strong>not</strong> a hidden ClientWorld. Koil's sprite
 * engine is expected to run on menus and other detached UI surfaces, so gameplay
 * simulation must be owned by Koil's virtual world. This runtime only exposes
 * deterministic data that can be read from the already-loaded Minecraft/mod
 * installation, such as recipes and item tags.</p>
 *
 * <p>Data is read through Fabric Loader's mod root paths. Those roots work for
 * normal jars and development classpath folders without converting virtual jar
 * paths to {@code File}. Nothing here starts a server or joins/creates a world.</p>
 */
public final class DetachedMinecraftRuntime {
    public enum CookingKind {
        SMELTING("minecraft:smelting", 200),
        BLASTING("minecraft:blasting", 100),
        SMOKING("minecraft:smoking", 100),
        CAMPFIRE("minecraft:campfire_cooking", 600);

        private final String recipeType;
        private final int defaultCookTime;

        CookingKind(String recipeType, int defaultCookTime) {
            this.recipeType = recipeType;
            this.defaultCookTime = defaultCookTime;
        }

        public String recipeType() { return recipeType; }
        public int defaultCookTime() { return defaultCookTime; }

        static CookingKind fromType(String value) {
            if (value == null) return null;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (CookingKind kind : values()) if (kind.recipeType.equals(normalized)) return kind;
            return null;
        }
    }

    public record CookingMatch(
            ItemStack output,
            int cookTime,
            float experience,
            String recipeId,
            String sourceMod
    ) { }

    public record Diagnostics(
            boolean loaded,
            int scannedMods,
            int itemTags,
            int cookingRecipes,
            int unsupportedRecipes,
            int loadErrors,
            String mode
    ) {
        public String summary() {
            return "mode=" + mode
                    + ",mods=" + scannedMods
                    + ",tags=" + itemTags
                    + ",cooking=" + cookingRecipes
                    + ",unsupported=" + unsupportedRecipes
                    + ",errors=" + loadErrors;
        }
    }

    private record IngredientRef(Identifier itemId, Identifier tagId) {
        boolean matches(Item item, Map<Identifier, Set<Identifier>> resolvedTags) {
            if (item == null || item == Items.AIR) return false;
            Identifier id = Registries.ITEM.getId(item);
            if (id == null) return false;
            if (itemId != null && itemId.equals(id)) return true;
            if (tagId != null) {
                Set<Identifier> members = resolvedTags.get(tagId);
                return members != null && members.contains(id);
            }
            return false;
        }
    }

    private record CookingRecipe(
            CookingKind kind,
            List<IngredientRef> alternatives,
            Identifier outputId,
            int outputCount,
            int cookTime,
            float experience,
            String recipeId,
            String sourceMod
    ) { }

    private static final class MutableTag {
        private final List<String> entries = new ArrayList<>();
    }

    private record Snapshot(
            Map<Identifier, Set<Identifier>> itemTags,
            Map<CookingKind, List<CookingRecipe>> cooking,
            Diagnostics diagnostics
    ) { }

    private static final DetachedMinecraftRuntime SHARED = new DetachedMinecraftRuntime();

    private volatile Snapshot snapshot;

    private DetachedMinecraftRuntime() { }

    public static DetachedMinecraftRuntime shared() {
        return SHARED;
    }

    /** True by design. Koil should never need a gameplay world to run this service. */
    public boolean isDetached() {
        return true;
    }

    /** Explicit invariant consumed by diagnostics/tests. */
    public boolean requiresGameplayWorld() {
        return false;
    }

    public Diagnostics diagnostics() {
        return ensureSnapshot().diagnostics();
    }

    /** Returns current cache status without forcing disk/jar scanning from a render tick. */
    public Diagnostics peekDiagnostics() {
        Snapshot local = snapshot;
        return local == null
                ? new Diagnostics(false, 0, 0, 0, 0, 0, "detached")
                : local.diagnostics();
    }

    public void invalidate() {
        snapshot = null;
    }

    public CookingMatch findCooking(CookingKind kind, ItemStack input) {
        if (kind == null || input == null || input.isEmpty()) return null;
        Snapshot data = ensureSnapshot();
        List<CookingRecipe> recipes = data.cooking().get(kind);
        if (recipes == null || recipes.isEmpty()) return null;
        Item item = input.getItem();
        for (CookingRecipe recipe : recipes) {
            boolean matched = false;
            for (IngredientRef alternative : recipe.alternatives()) {
                if (alternative.matches(item, data.itemTags())) {
                    matched = true;
                    break;
                }
            }
            if (!matched) continue;
            Item outputItem = Registries.ITEM.get(recipe.outputId());
            if (outputItem == null || outputItem == Items.AIR) continue;
            return new CookingMatch(
                    new ItemStack(outputItem, Math.max(1, recipe.outputCount())),
                    Math.max(1, recipe.cookTime()),
                    recipe.experience(),
                    recipe.recipeId(),
                    recipe.sourceMod());
        }
        return null;
    }

    public boolean itemInTag(Item item, Identifier tagId) {
        if (item == null || tagId == null) return false;
        Identifier itemId = Registries.ITEM.getId(item);
        if (itemId == null) return false;
        Set<Identifier> entries = ensureSnapshot().itemTags().get(tagId);
        return entries != null && entries.contains(itemId);
    }

    private Snapshot ensureSnapshot() {
        Snapshot local = snapshot;
        if (local != null) return local;
        synchronized (this) {
            local = snapshot;
            if (local == null) snapshot = local = loadSnapshot();
        }
        return local;
    }

    private Snapshot loadSnapshot() {
        Map<Identifier, MutableTag> mutableTags = new LinkedHashMap<>();
        Map<String, CookingRecipe> recipesById = new LinkedHashMap<>();
        int scannedMods = 0;
        int unsupportedRecipes = 0;
        int loadErrors = 0;

        Collection<ModContainer> loadedMods;
        try {
            loadedMods = FabricLoader.getInstance().getAllMods();
        } catch (RuntimeException failure) {
            return new Snapshot(Map.of(), emptyCookingMap(),
                    new Diagnostics(true, 0, 0, 0, 0, 1, "detached"));
        }

        // Minecraft first, then preserve Fabric's order for the rest. Later roots
        // replace recipes with the same identifier, matching normal resource-style
        // override semantics closely without constructing a server resource manager.
        List<ModContainer> mods = new ArrayList<>(loadedMods);
        mods.sort(Comparator.comparingInt((ModContainer mod) ->
                        "minecraft".equals(mod.getMetadata().getId()) ? 0 : 1));

        for (ModContainer mod : mods) {
            String sourceMod = mod.getMetadata().getId();
            boolean scanned = false;
            for (Path dataRoot : dataRoots(mod)) {
                if (!Files.isDirectory(dataRoot)) continue;
                scanned = true;
                List<Path> namespaces = childDirectories(dataRoot);
                for (Path namespaceRoot : namespaces) {
                    String namespace = namespaceRoot.getFileName().toString();

                    Path itemTagsRoot = namespaceRoot.resolve("tags").resolve("items");
                    for (Path json : jsonFilesUnder(itemTagsRoot)) {
                        try {
                            Path relativeToTagRoot = itemTagsRoot.relativize(json);
                            Path synthetic = Path.of(namespace, "tags", "items").resolve(relativeToTagRoot);
                            readItemTag(json, namespace, synthetic, mutableTags);
                        } catch (Exception ignored) {
                            loadErrors++;
                        }
                    }

                    Path recipesRoot = namespaceRoot.resolve("recipes");
                    for (Path json : jsonFilesUnder(recipesRoot)) {
                        try {
                            Path relativeToRecipes = recipesRoot.relativize(json);
                            Path synthetic = Path.of(namespace, "recipes").resolve(relativeToRecipes);
                            RecipeReadResult result = readCookingRecipe(json, namespace, synthetic, sourceMod);
                            if (result.recipe() != null) recipesById.put(result.recipe().recipeId(), result.recipe());
                            if (result.unsupported()) unsupportedRecipes++;
                        } catch (Exception ignored) {
                            loadErrors++;
                        }
                    }
                }
            }
            if (scanned) scannedMods++;
        }

        Map<Identifier, Set<Identifier>> resolvedTags = resolveTags(mutableTags);
        Map<CookingKind, List<CookingRecipe>> cooking = emptyCookingMapMutable();
        for (CookingRecipe recipe : recipesById.values()) {
            cooking.get(recipe.kind()).add(recipe);
        }
        for (Map.Entry<CookingKind, List<CookingRecipe>> entry : cooking.entrySet()) {
            entry.getValue().sort(Comparator.comparing(CookingRecipe::recipeId));
            entry.setValue(List.copyOf(entry.getValue()));
        }

        Diagnostics diagnostics = new Diagnostics(true, scannedMods, resolvedTags.size(), recipesById.size(),
                unsupportedRecipes, loadErrors, "detached");
        return new Snapshot(Map.copyOf(resolvedTags), Map.copyOf(cooking), diagnostics);
    }

    private record RecipeReadResult(CookingRecipe recipe, boolean unsupported) { }

    private RecipeReadResult readCookingRecipe(Path json, String namespace, Path relative, String sourceMod) throws IOException {
        JsonObject root = readObject(json);
        if (root == null || !root.has("type")) return new RecipeReadResult(null, false);
        CookingKind kind = CookingKind.fromType(root.get("type").getAsString());
        if (kind == null) return new RecipeReadResult(null, false);

        List<IngredientRef> alternatives = parseIngredient(root.get("ingredient"));
        if (alternatives.isEmpty()) return new RecipeReadResult(null, true);

        Identifier outputId = null;
        int outputCount = 1;
        JsonElement result = root.get("result");
        if (result != null && result.isJsonPrimitive()) {
            outputId = parseIdentifier(result.getAsString(), namespace);
        } else if (result != null && result.isJsonObject()) {
            JsonObject object = result.getAsJsonObject();
            if (object.has("item")) outputId = parseIdentifier(object.get("item").getAsString(), namespace);
            if (object.has("count")) outputCount = Math.max(1, object.get("count").getAsInt());
        }
        if (outputId == null) return new RecipeReadResult(null, true);

        int cookTime = root.has("cookingtime") ? Math.max(1, root.get("cookingtime").getAsInt()) : kind.defaultCookTime();
        float experience = root.has("experience") ? root.get("experience").getAsFloat() : 0.0F;
        String recipeId = recipeIdentifier(namespace, relative, 2);
        return new RecipeReadResult(new CookingRecipe(kind, List.copyOf(alternatives), outputId, outputCount,
                cookTime, experience, recipeId, sourceMod), false);
    }

    private List<IngredientRef> parseIngredient(JsonElement ingredient) {
        if (ingredient == null || ingredient.isJsonNull()) return List.of();
        List<IngredientRef> out = new ArrayList<>();
        if (ingredient.isJsonArray()) {
            JsonArray array = ingredient.getAsJsonArray();
            for (JsonElement element : array) addIngredientAlternative(element, out);
        } else {
            addIngredientAlternative(ingredient, out);
        }
        return out;
    }

    private void addIngredientAlternative(JsonElement element, List<IngredientRef> out) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonPrimitive()) {
            Identifier item = parseIdentifier(element.getAsString(), "minecraft");
            if (item != null) out.add(new IngredientRef(item, null));
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        if (object.has("item")) {
            Identifier item = parseIdentifier(object.get("item").getAsString(), "minecraft");
            if (item != null) out.add(new IngredientRef(item, null));
            return;
        }
        if (object.has("tag")) {
            Identifier tag = parseIdentifier(object.get("tag").getAsString(), "minecraft");
            if (tag != null) out.add(new IngredientRef(null, tag));
        }
    }

    private void readItemTag(Path json, String namespace, Path relative,
                             Map<Identifier, MutableTag> mutableTags) throws IOException {
        String idPath = relativeSubpath(relative, 3);
        if (idPath.isBlank()) return;
        Identifier tagId = new Identifier(namespace, stripJson(idPath));
        JsonObject root = readObject(json);
        if (root == null) return;
        MutableTag tag = mutableTags.computeIfAbsent(tagId, ignored -> new MutableTag());
        if (root.has("replace") && root.get("replace").getAsBoolean()) tag.entries.clear();
        JsonArray values = root.has("values") && root.get("values").isJsonArray()
                ? root.getAsJsonArray("values") : null;
        if (values == null) return;
        for (JsonElement value : values) {
            if (value.isJsonPrimitive()) {
                String entry = value.getAsString();
                if (entry != null && !entry.isBlank()) tag.entries.add(entry.trim());
            } else if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                if (object.has("id")) {
                    String entry = object.get("id").getAsString();
                    if (entry != null && !entry.isBlank()) tag.entries.add(entry.trim());
                }
            }
        }
    }

    private Map<Identifier, Set<Identifier>> resolveTags(Map<Identifier, MutableTag> mutableTags) {
        Map<Identifier, Set<Identifier>> resolved = new HashMap<>();
        for (Identifier tagId : mutableTags.keySet()) {
            resolveTag(tagId, mutableTags, resolved, new HashSet<>());
        }
        return resolved;
    }

    private Set<Identifier> resolveTag(Identifier tagId,
                                       Map<Identifier, MutableTag> mutableTags,
                                       Map<Identifier, Set<Identifier>> resolved,
                                       Set<Identifier> visiting) {
        Set<Identifier> cached = resolved.get(tagId);
        if (cached != null) return cached;
        if (!visiting.add(tagId)) return Set.of();
        MutableTag tag = mutableTags.get(tagId);
        if (tag == null) {
            visiting.remove(tagId);
            return Set.of();
        }
        LinkedHashSet<Identifier> members = new LinkedHashSet<>();
        for (String raw : tag.entries) {
            if (raw == null || raw.isBlank()) continue;
            if (raw.charAt(0) == '#') {
                Identifier nested = parseIdentifier(raw.substring(1), tagId.getNamespace());
                if (nested != null) members.addAll(resolveTag(nested, mutableTags, resolved, visiting));
            } else {
                Identifier item = parseIdentifier(raw, tagId.getNamespace());
                if (item != null) members.add(item);
            }
        }
        visiting.remove(tagId);
        Set<Identifier> frozen = Collections.unmodifiableSet(members);
        resolved.put(tagId, frozen);
        return frozen;
    }

    private List<Path> dataRoots(ModContainer mod) {
        if (mod == null) return List.of();
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        try {
            for (Path root : mod.getRootPaths()) {
                if (root != null) roots.add(root.resolve("data"));
            }
        } catch (RuntimeException ignored) { }
        try {
            mod.findPath("data").ifPresent(roots::add);
        } catch (RuntimeException ignored) { }
        return List.copyOf(roots);
    }

    private List<Path> childDirectories(Path root) {
        if (root == null || !Files.isDirectory(root)) return List.of();
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }
    }

    private List<Path> jsonFilesUnder(Path root) {
        if (root == null || !Files.isDirectory(root)) return List.of();
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException | RuntimeException ignored) {
            return List.of();
        }
    }

    private JsonObject readObject(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        }
    }

    private static String recipeIdentifier(String namespace, Path relative, int startIndex) {
        return namespace + ":" + stripJson(relativeSubpath(relative, startIndex));
    }

    private static String relativeSubpath(Path relative, int startIndex) {
        if (relative == null || relative.getNameCount() <= startIndex) return "";
        StringBuilder out = new StringBuilder();
        for (int i = startIndex; i < relative.getNameCount(); i++) {
            if (out.length() > 0) out.append('/');
            out.append(relative.getName(i));
        }
        return out.toString().replace('\\', '/');
    }

    private static String stripJson(String value) {
        if (value == null) return "";
        return value.endsWith(".json") ? value.substring(0, value.length() - 5) : value;
    }

    private static Identifier parseIdentifier(String value, String defaultNamespace) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        try {
            if (normalized.indexOf(':') >= 0) return new Identifier(normalized);
            return new Identifier(defaultNamespace == null || defaultNamespace.isBlank() ? "minecraft" : defaultNamespace,
                    normalized);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Map<CookingKind, List<CookingRecipe>> emptyCookingMapMutable() {
        Map<CookingKind, List<CookingRecipe>> map = new LinkedHashMap<>();
        for (CookingKind kind : CookingKind.values()) map.put(kind, new ArrayList<>());
        return map;
    }

    private static Map<CookingKind, List<CookingRecipe>> emptyCookingMap() {
        Map<CookingKind, List<CookingRecipe>> map = new LinkedHashMap<>();
        for (CookingKind kind : CookingKind.values()) map.put(kind, List.of());
        return Map.copyOf(map);
    }
}
