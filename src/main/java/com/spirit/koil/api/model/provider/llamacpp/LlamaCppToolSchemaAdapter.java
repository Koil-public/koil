package com.spirit.koil.api.model.provider.llamacpp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Set;

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

    private LlamaCppToolSchemaAdapter() {
    }

    static JsonObject toWire(JsonObject schema) {
        JsonElement adapted = adapt(schema == null ? new JsonObject() : schema);
        return adapted.isJsonObject() ? adapted.getAsJsonObject() : new JsonObject();
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
