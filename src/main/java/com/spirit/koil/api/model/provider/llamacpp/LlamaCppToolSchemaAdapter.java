package com.spirit.koil.api.model.provider.llamacpp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.spirit.koil.api.model.cache.ModelPromptCacheIdentity;

/**
 * Produces a llama.cpp grammar-safe schema without weakening Koil's
 * authoritative post-generation capability validation.
 */
final class LlamaCppToolSchemaAdapter {
    private static final Set<String> REPETITION_UPPER_BOUNDS = Set.of(
            "maxLength",
            "maxItems",
            "maxProperties"
    );
    private static final int MAXIMUM_CACHED_SCHEMAS = 512;
    private static final ConcurrentHashMap<String, JsonObject> CACHE = new ConcurrentHashMap<>();

    private LlamaCppToolSchemaAdapter() {
    }

    static JsonObject toWire(JsonObject schema) {
        JsonObject canonical = ModelPromptCacheIdentity.canonicalObject(schema);
        String key = ModelPromptCacheIdentity.sha256(ModelPromptCacheIdentity.canonicalJson(canonical));
        JsonObject cached = CACHE.get(key);
        if (cached != null) return cached.deepCopy();
        JsonElement adapted = adapt(canonical);
        JsonObject result = adapted.isJsonObject() ? adapted.getAsJsonObject() : new JsonObject();
        if (CACHE.size() >= MAXIMUM_CACHED_SCHEMAS) CACHE.clear();
        CACHE.putIfAbsent(key, result.deepCopy());
        return result;
    }

    private static JsonElement adapt(JsonElement element) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
            return element == null ? new JsonObject() : element.deepCopy();
        }
        if (element.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement child : element.getAsJsonArray()) {
                result.add(adapt(child));
            }
            return result;
        }
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (!REPETITION_UPPER_BOUNDS.contains(entry.getKey())) {
                result.add(entry.getKey(), adapt(entry.getValue()));
            }
        }
        return result;
    }
}
