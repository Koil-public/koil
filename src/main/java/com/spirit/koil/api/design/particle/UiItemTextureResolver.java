package com.spirit.koil.api.design.particle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.item.ModelPredicateProvider;
import net.minecraft.client.item.ModelPredicateProviderRegistry;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a registered item to a flat resource-pack-aware texture.
 *
 * <p>Unlike the original item-only resolver, this implementation also evaluates
 * item-model overrides against the authoritative {@link ItemStack} and Koil's
 * detached use state. That preserves bow/crossbow pulling and charged textures,
 * damage/custom-model-data variants, and modded stack-only model predicates
 * without requiring a playable Minecraft world or fake player entity.</p>
 */
public final class UiItemTextureResolver {
    private static final Map<Identifier, Identifier> MODEL_TEXTURE_CACHE = new HashMap<>();
    private static final Set<Identifier> KNOWN_MISSING_MODELS = new HashSet<>();

    private UiItemTextureResolver() { }

    public static synchronized void clearCache() {
        MODEL_TEXTURE_CACHE.clear();
        KNOWN_MISSING_MODELS.clear();
    }

    public static Identifier resolve(Item item) {
        if (item == null) return null;
        return resolve(new ItemStack(item), 0.0F, false);
    }

    public static synchronized Identifier resolve(ItemStack stack, float useProgress, boolean using) {
        if (stack == null || stack.isEmpty()) return null;
        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        if (itemId == null) return null;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getResourceManager() == null) return null;
        ResourceManager resources = client.getResourceManager();

        Identifier baseModel = new Identifier(itemId.getNamespace(), "item/" + itemId.getPath());
        Identifier selectedModel = selectOverrideModel(resources, baseModel, stack,
                Math.max(0.0F, Math.min(1.0F, useProgress)), using);
        if (selectedModel == null) selectedModel = baseModel;

        Identifier cached = MODEL_TEXTURE_CACHE.get(selectedModel);
        if (cached != null) return cached;
        if (KNOWN_MISSING_MODELS.contains(selectedModel)) return directTexture(resources, itemId);

        Identifier resolved = resolveModelTexture(resources, selectedModel, itemId.getNamespace());
        if (resolved != null) MODEL_TEXTURE_CACHE.put(selectedModel, resolved);
        else KNOWN_MISSING_MODELS.add(selectedModel);
        return resolved != null ? resolved : directTexture(resources, itemId);
    }

    private static Identifier selectOverrideModel(ResourceManager resources, Identifier baseModel,
                                                   ItemStack stack, float useProgress, boolean using) {
        if (resources == null || baseModel == null) return baseModel;
        JsonObject model = readModel(resources, baseModel);
        Identifier selected = matchingOverride(model, baseModel, stack, useProgress, using);

        // Match ItemRenderer#getModel semantics: vanilla applies the override list
        // of the initially selected item model once. It does not recursively apply
        // override lists from the override target. Parent models are still followed
        // later for texture inheritance by collectTextures().
        return selected == null ? baseModel : selected;
    }

    private static Identifier matchingOverride(JsonObject model, Identifier modelId,
                                               ItemStack stack, float useProgress, boolean using) {
        if (model == null || modelId == null || !model.has("overrides")
                || !model.get("overrides").isJsonArray()) return null;
        Identifier selected = null;
        JsonArray overrides = model.getAsJsonArray("overrides");
        // Item overrides are order-sensitive. The last matching entry wins, which
        // is how vanilla bow/crossbow threshold lists select their final stage.
        for (JsonElement element : overrides) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject override = element.getAsJsonObject();
            if (!override.has("model") || !override.get("model").isJsonPrimitive()) continue;
            JsonObject predicates = override.has("predicate") && override.get("predicate").isJsonObject()
                    ? override.getAsJsonObject("predicate") : new JsonObject();
            if (!matchesPredicates(stack, predicates, useProgress, using)) continue;
            Identifier candidate = parseIdentifier(override.get("model").getAsString(), modelId.getNamespace());
            if (candidate != null) selected = candidate;
        }
        return selected;
    }

    private static boolean matchesPredicates(ItemStack stack, JsonObject predicates,
                                             float useProgress, boolean using) {
        if (predicates == null) return true;
        for (Map.Entry<String, JsonElement> entry : predicates.entrySet()) {
            if (entry.getValue() == null || !entry.getValue().isJsonPrimitive()) return false;
            float required;
            try { required = entry.getValue().getAsFloat(); }
            catch (RuntimeException ignored) { return false; }
            Float actual = predicateValue(stack, entry.getKey(), useProgress, using);
            if (actual == null || Float.isNaN(actual) || actual + 0.00001F < required) return false;
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    private static Float predicateValue(ItemStack stack, String rawPredicate,
                                        float useProgress, boolean using) {
        Identifier predicateId = parseIdentifier(rawPredicate, "minecraft");
        if (predicateId == null) return null;
        String namespace = predicateId.getNamespace();
        String path = predicateId.getPath();

        // Detached-use values replace vanilla predicates that normally require a
        // LivingEntity. This is what makes weapon texture stages work on menus.
        if ("minecraft".equals(namespace)) {
            if ("pulling".equals(path) || "throwing".equals(path) || "blocking".equals(path)) {
                return using ? 1.0F : 0.0F;
            }
            if ("pull".equals(path)) return useProgress;
            if ("charged".equals(path)) {
                return stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack) ? 1.0F : 0.0F;
            }
            if ("firework".equals(path)) {
                return stack.getItem() instanceof CrossbowItem
                        && CrossbowItem.hasProjectile(stack, Items.FIREWORK_ROCKET) ? 1.0F : 0.0F;
            }
        }

        // Let Minecraft/Fabric/mod registrations answer every predicate that can
        // be evaluated from stack state without a world/entity. Providers that
        // require an entity commonly return 0 for null; Koil overrides the known
        // active-use predicates above with its own authoritative actor state.
        try {
            ModelPredicateProvider provider = ModelPredicateProviderRegistry.get(stack.getItem(), predicateId);
            if (provider != null) return provider.call(stack, null, null, 0);
        } catch (RuntimeException ignored) { }
        return null;
    }

    private static Identifier resolveModelTexture(ResourceManager resources, Identifier modelId, String fallbackNamespace) {
        Map<String, String> textures = new LinkedHashMap<>();
        collectTextures(resources, modelId, textures, new HashSet<>());
        for (String key : new String[]{"layer0", "layer1", "particle", "texture", "all", "0"}) {
            Identifier texture = textureResource(resolveTextureReference(key, textures, new HashSet<>()), fallbackNamespace);
            if (exists(resources, texture)) return texture;
        }
        for (String key : textures.keySet()) {
            Identifier texture = textureResource(resolveTextureReference(key, textures, new HashSet<>()), fallbackNamespace);
            if (exists(resources, texture)) return texture;
        }
        return null;
    }

    private static JsonObject readModel(ResourceManager resources, Identifier modelId) {
        if (resources == null || modelId == null) return null;
        Identifier modelResource = new Identifier(modelId.getNamespace(), "models/" + modelId.getPath() + ".json");
        Optional<Resource> resource = resources.getResource(modelResource);
        if (resource.isEmpty()) return null;
        try (InputStreamReader reader = new InputStreamReader(resource.get().getInputStream(), StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void collectTextures(ResourceManager resources, Identifier modelId,
                                        Map<String, String> textures, Set<Identifier> visited) {
        if (modelId == null || !visited.add(modelId) || visited.size() > 24) return;
        JsonObject model = readModel(resources, modelId);
        if (model == null) return;
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
    }

    private static String resolveTextureReference(String key, Map<String, String> textures, Set<String> visited) {
        if (key == null || key.isBlank() || !visited.add(key)) return null;
        String value = textures.get(key);
        if (value == null || value.isBlank()) return null;
        if (!value.startsWith("#")) return value;
        return resolveTextureReference(value.substring(1), textures, visited);
    }

    private static Identifier directTexture(ResourceManager resources, Identifier itemId) {
        Identifier direct = new Identifier(itemId.getNamespace(), "textures/item/" + itemId.getPath() + ".png");
        return exists(resources, direct) ? direct : null;
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

    private static boolean exists(ResourceManager resources, Identifier resource) {
        return resource != null && resources.getResource(resource).isPresent();
    }
}
