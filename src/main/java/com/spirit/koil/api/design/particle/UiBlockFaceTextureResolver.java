package com.spirit.koil.api.design.particle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves registered blocks to flat, resource-pack-aware face textures.
 *
 * <p>The resolver is state-aware. A caller may supply a compact block-state
 * signature such as {@code lit=true}, {@code powered=true} or
 * {@code extended=true,facing=north}. Koil uses that to choose the matching
 * blockstate model rather than permanently caching one representative model for
 * a block. This is what lets screen sprites visually turn lamps/repeaters on,
 * swap furnace fronts, and update other stateful block visuals.</p>
 */
public final class UiBlockFaceTextureResolver {
    private record CacheKey(Identifier blockId, String stateSignature) { }

    private static final Map<CacheKey, Identifier> CACHE = new HashMap<>();
    private static final Set<CacheKey> KNOWN_MISSING = new HashSet<>();

    private UiBlockFaceTextureResolver() { }

    public static synchronized void clearCache() {
        CACHE.clear();
        KNOWN_MISSING.clear();
    }

    public static synchronized Identifier resolve(Block block) {
        return resolve(block, "");
    }

    /** Returns a deterministic compact signature for the block's real default state. */
    public static String defaultStateSignature(Block block) {
        if (block == null) return "";
        Map<String, String> state = new LinkedHashMap<>();
        try {
            for (Map.Entry<Property<?>, Comparable<?>> entry : block.getDefaultState().getEntries().entrySet()) {
                String key = entry.getKey().getName().toLowerCase();
                String value = String.valueOf(entry.getValue()).toLowerCase();
                state.put(key, value);
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        return normalizeStateMap(state);
    }

    public static synchronized Identifier resolve(Block block, String stateSignature) {
        if (block == null) return null;
        Identifier blockId = Registries.BLOCK.getId(block);
        if (blockId == null) return null;
        Map<String, String> mergedState = parseState(defaultStateSignature(block));
        mergedState.putAll(parseState(stateSignature));
        String normalizedState = normalizeStateMap(mergedState);
        CacheKey key = new CacheKey(blockId, normalizedState);
        Identifier cached = CACHE.get(key);
        if (cached != null) return cached;
        if (KNOWN_MISSING.contains(key)) return null;

        Identifier resolved = resolveNow(blockId, parseState(normalizedState));
        if (resolved != null) CACHE.put(key, resolved);
        else KNOWN_MISSING.add(key);
        return resolved;
    }

    private static Identifier resolveNow(Identifier blockId, Map<String, String> state) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getResourceManager() == null) return null;
        ResourceManager resources = client.getResourceManager();

        List<Identifier> modelIds = representativeModels(resources, blockId, state);
        modelIds.add(new Identifier(blockId.getNamespace(), "block/" + blockId.getPath()));

        for (Identifier modelId : modelIds) {
            Map<String, String> textures = new LinkedHashMap<>();
            collectTextures(resources, modelId, textures, new HashSet<>());
            Identifier texture = chooseTexture(resources, blockId, textures, state);
            if (texture != null) return texture;
        }

        for (String suffix : directSuffixPriority(blockId.getPath(), state)) {
            Identifier candidate = new Identifier(blockId.getNamespace(),
                    "textures/block/" + blockId.getPath() + suffix + ".png");
            if (exists(resources, candidate)) return candidate;
        }
        return null;
    }

    private static List<Identifier> representativeModels(ResourceManager resources, Identifier blockId,
                                                          Map<String, String> state) {
        List<Identifier> results = new ArrayList<>();
        Identifier blockstate = new Identifier(blockId.getNamespace(), "blockstates/" + blockId.getPath() + ".json");
        Optional<Resource> resource = resources.getResource(blockstate);
        if (resource.isEmpty()) return results;
        try (InputStreamReader reader = new InputStreamReader(resource.get().getInputStream(), StandardCharsets.UTF_8)) {
            JsonElement rootElement = JsonParser.parseReader(reader);
            if (!rootElement.isJsonObject()) return results;
            JsonObject root = rootElement.getAsJsonObject();

            if (root.has("variants") && root.get("variants").isJsonObject()) {
                JsonObject variants = root.getAsJsonObject("variants");
                String bestKey = null;
                int bestScore = -1;
                int bestSpecificity = -1;
                for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
                    String key = entry.getKey();
                    int score = variantMatchScore(key, state);
                    if (score < 0) continue;
                    int specificity = key.isBlank() ? 0 : key.split(",").length;
                    if (score > bestScore || (score == bestScore && specificity > bestSpecificity)) {
                        bestKey = key;
                        bestScore = score;
                        bestSpecificity = specificity;
                    }
                }
                if (bestKey != null) collectModelIds(variants.get(bestKey), blockId.getNamespace(), results, 16);
            }

            if (root.has("multipart") && root.get("multipart").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("multipart")) {
                    if (!element.isJsonObject()) continue;
                    JsonObject part = element.getAsJsonObject();
                    JsonElement when = part.get("when");
                    if (when == null || multipartMatches(when, state)) {
                        collectModelIds(part.get("apply"), blockId.getNamespace(), results, 12);
                    }
                    if (results.size() >= 12) break;
                }
            }

            // State can be incomplete for a generic registry sprite. Fall back to
            // the first available model only after trying state-specific matches.
            if (results.isEmpty()) collectModelIds(root, blockId.getNamespace(), results, 12);
        } catch (Exception ignored) {
            // Direct-model and filename fallbacks below remain available.
        }
        return results;
    }

    private static int variantMatchScore(String variant, Map<String, String> state) {
        if (variant == null || variant.isBlank()) return state.isEmpty() ? 1 : 0;
        int score = 0;
        for (String part : variant.split(",")) {
            int equals = part.indexOf('=');
            if (equals <= 0) continue;
            String key = part.substring(0, equals).trim().toLowerCase();
            String expected = part.substring(equals + 1).trim().toLowerCase();
            String actual = state.get(key);
            // Unspecified properties are wildcards for a 2D sprite. This lets a
            // powered=true visual choose the correct family even when the sprite
            // does not care about world-only facing/attachment properties.
            if (actual == null) continue;
            if (!valueMatches(expected, actual)) return -1;
            score++;
        }
        return score;
    }

    private static boolean multipartMatches(JsonElement when, Map<String, String> state) {
        if (when == null || when.isJsonNull()) return true;
        if (when.isJsonArray()) {
            for (JsonElement child : when.getAsJsonArray()) if (multipartMatches(child, state)) return true;
            return false;
        }
        if (!when.isJsonObject()) return true;
        JsonObject object = when.getAsJsonObject();
        if (object.has("OR") && object.get("OR").isJsonArray()) return multipartMatches(object.get("OR"), state);
        if (object.has("AND") && object.get("AND").isJsonArray()) {
            for (JsonElement child : object.getAsJsonArray("AND")) if (!multipartMatches(child, state)) return false;
            return true;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) continue;
            String actual = state.get(entry.getKey().toLowerCase());
            if (actual == null || !valueMatches(entry.getValue().getAsString().toLowerCase(), actual)) return false;
        }
        return true;
    }

    private static boolean valueMatches(String expected, String actual) {
        if (expected == null || actual == null) return false;
        for (String option : expected.split("\\|")) if (option.trim().equalsIgnoreCase(actual.trim())) return true;
        return false;
    }

    private static void collectModelIds(JsonElement element, String defaultNamespace,
                                        List<Identifier> out, int remaining) {
        if (element == null || remaining <= 0 || out.size() >= 12) return;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("model") && object.get("model").isJsonPrimitive()) {
                Identifier id = parseIdentifier(object.get("model").getAsString(), defaultNamespace);
                if (id != null && !out.contains(id)) out.add(id);
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (!entry.getKey().equals("model")) collectModelIds(entry.getValue(), defaultNamespace, out, remaining - 1);
                if (out.size() >= 12) break;
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                collectModelIds(child, defaultNamespace, out, remaining - 1);
                if (out.size() >= 12) break;
            }
        }
    }

    private static void collectTextures(ResourceManager resources, Identifier modelId,
                                        Map<String, String> textures, Set<Identifier> visited) {
        if (modelId == null || !visited.add(modelId) || visited.size() > 24) return;
        Identifier modelResource = new Identifier(modelId.getNamespace(), "models/" + modelId.getPath() + ".json");
        Optional<Resource> resource = resources.getResource(modelResource);
        if (resource.isEmpty()) return;

        try (InputStreamReader reader = new InputStreamReader(resource.get().getInputStream(), StandardCharsets.UTF_8)) {
            JsonObject model = JsonParser.parseReader(reader).getAsJsonObject();
            if (model.has("parent") && model.get("parent").isJsonPrimitive()) {
                Identifier parent = parseIdentifier(model.get("parent").getAsString(), modelId.getNamespace());
                collectTextures(resources, parent, textures, visited);
            }
            if (model.has("textures") && model.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : model.getAsJsonObject("textures").entrySet()) {
                    if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                        textures.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
        } catch (Exception ignored) {
            // Another model candidate or direct filename fallback may still resolve.
        }
    }

    private static Identifier chooseTexture(ResourceManager resources, Identifier blockId,
                                            Map<String, String> textures, Map<String, String> state) {
        if (textures.isEmpty()) return null;
        for (String key : faceKeyPriority(blockId.getPath(), state)) {
            String raw = resolveTextureReference(key, textures, new HashSet<>());
            Identifier texture = textureResource(raw, blockId.getNamespace());
            if (texture != null && exists(resources, texture)) return texture;
        }
        for (String key : textures.keySet()) {
            String raw = resolveTextureReference(key, textures, new HashSet<>());
            Identifier texture = textureResource(raw, blockId.getNamespace());
            if (texture != null && exists(resources, texture)) return texture;
        }
        return null;
    }

    private static List<String> faceKeyPriority(String path, Map<String, String> state) {
        String p = path == null ? "" : path;
        boolean lit = "true".equals(state.get("lit")) || "true".equals(state.get("powered"));
        if (p.contains("furnace") || p.contains("smoker") || p.contains("blast_furnace")) {
            return lit ? List.of("front_on", "front", "all", "side", "top", "particle")
                    : List.of("front", "front_on", "all", "side", "top", "particle");
        }
        if (p.contains("dispenser") || p.contains("dropper") || p.contains("observer") || p.contains("piston")) {
            return List.of("front", "front_on", "top", "all", "side", "particle", "end");
        }
        if (p.contains("log") || p.contains("stem") || p.contains("hyphae") || p.contains("wood")) {
            return List.of("side", "all", "bark", "top", "end", "particle");
        }
        if (p.contains("grass") || p.contains("mycelium") || p.contains("podzol")
                || p.contains("moss") || p.contains("nylium")) {
            return List.of("top", "all", "side", "particle", "bottom");
        }
        if (p.contains("crafting_table") || p.contains("target") || p.contains("bookshelf")) {
            return List.of("top", "front", "all", "side", "particle");
        }
        return List.of("all", "side", "front", "top", "end", "particle", "texture", "0");
    }

    private static String[] directSuffixPriority(String path, Map<String, String> state) {
        boolean lit = "true".equals(state.get("lit")) || "true".equals(state.get("powered"));
        if (path != null && path.contains("redstone_lamp") && lit) return new String[]{"_on", "", "_side", "_top"};
        if (path != null && (path.contains("furnace") || path.contains("smoker") || path.contains("blast_furnace"))) {
            return lit ? new String[]{"_front_on", "_front", "", "_side", "_top"}
                    : new String[]{"_front", "", "_side", "_top", "_front_on"};
        }
        if (path != null && (path.contains("dispenser") || path.contains("dropper") || path.contains("observer") || path.contains("piston"))) {
            return new String[]{"_front", "", "_side", "_top"};
        }
        if (path != null && (path.contains("grass") || path.contains("mycelium") || path.contains("podzol")
                || path.contains("moss") || path.contains("nylium"))) {
            return new String[]{"_top", "", "_side"};
        }
        if (path != null && (path.contains("log") || path.contains("stem") || path.contains("hyphae"))) {
            return new String[]{"", "_side", "_top", "_end"};
        }
        return new String[]{"", "_side", "_front", "_top", "_end"};
    }

    private static String resolveTextureReference(String key, Map<String, String> textures, Set<String> visited) {
        if (key == null || key.isBlank() || !visited.add(key)) return null;
        String value = textures.get(key);
        if (value == null || value.isBlank()) return null;
        if (!value.startsWith("#")) return value;
        return resolveTextureReference(value.substring(1), textures, visited);
    }

    private static Identifier textureResource(String raw, String defaultNamespace) {
        if (raw == null || raw.isBlank() || raw.startsWith("#")) return null;
        Identifier id = parseIdentifier(raw, defaultNamespace);
        if (id == null) return null;
        String path = id.getPath();
        if (!path.startsWith("textures/")) path = "textures/" + path;
        if (!path.endsWith(".png")) path += ".png";
        return new Identifier(id.getNamespace(), path);
    }

    private static Identifier parseIdentifier(String raw, String defaultNamespace) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String value = raw.trim();
            if (value.indexOf(':') >= 0) return new Identifier(value);
            return new Identifier(defaultNamespace == null || defaultNamespace.isBlank() ? "minecraft" : defaultNamespace, value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Map<String, String> parseState(String signature) {
        Map<String, String> state = new LinkedHashMap<>();
        if (signature == null || signature.isBlank()) return state;
        for (String pair : signature.split("[,;]")) {
            int equals = pair.indexOf('=');
            if (equals <= 0 || equals + 1 >= pair.length()) continue;
            state.put(pair.substring(0, equals).trim().toLowerCase(), pair.substring(equals + 1).trim().toLowerCase());
        }
        return state;
    }

    private static String normalizeStateSignature(String signature) {
        return normalizeStateMap(parseState(signature));
    }

    private static String normalizeStateMap(Map<String, String> state) {
        if (state == null || state.isEmpty()) return "";
        List<String> keys = new ArrayList<>(state.keySet());
        keys.sort(String::compareTo);
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            if (out.length() > 0) out.append(',');
            out.append(key).append('=').append(state.get(key));
        }
        return out.toString();
    }

    private static boolean exists(ResourceManager resources, Identifier resource) {
        return resource != null && resources.getResource(resource).isPresent();
    }
}
