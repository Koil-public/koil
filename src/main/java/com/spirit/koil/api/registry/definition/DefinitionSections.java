package com.spirit.koil.api.registry.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Immutable-by-copy section view that never discards unknown or namespaced extension fields. */
public final class DefinitionSections {
    private static final Set<String> KNOWN_ROOT_FIELDS = Set.of(
            "schema_version", "id", "type", "namespace",
            "display", "behavior", "properties", "components", "tags",
            "creative", "assets", "model", "texture", "loot", "recipes",
            "compatibility", "version", "metadata", "extensions", "blockstates"
    );

    private final JsonObject raw;
    private final JsonObject unknownRootFields;

    public DefinitionSections(JsonObject raw) {
        this.raw = raw == null ? new JsonObject() : raw.deepCopy();
        unknownRootFields = new JsonObject();
        for (var entry : this.raw.entrySet()) {
            if (!KNOWN_ROOT_FIELDS.contains(entry.getKey())) {
                unknownRootFields.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
    }

    public JsonObject display() {
        return object("display");
    }

    public JsonObject behavior() {
        return object("behavior");
    }

    public JsonObject properties() {
        return object("properties");
    }

    public JsonElement components() {
        return copy("components");
    }

    public JsonElement tags() {
        return copy("tags");
    }

    public JsonObject creative() {
        return object("creative");
    }

    public JsonObject assets() {
        return object("assets");
    }

    public JsonElement recipes() {
        return copy("recipes");
    }

    public JsonObject compatibility() {
        return object("compatibility");
    }

    public JsonObject metadata() {
        return object("metadata");
    }

    public JsonObject extensions() {
        return object("extensions");
    }

    /**
     * Returns explicit extension entries plus namespaced custom root sections.
     * Explicit entries under {@code extensions} take precedence if both forms
     * declare the same extension id.
     */
    public Map<String, JsonElement> extensionSections() {
        LinkedHashMap<String, JsonElement> merged = new LinkedHashMap<>();
        for (var entry : extensions().entrySet()) {
            merged.put(entry.getKey(), entry.getValue().deepCopy());
        }
        for (var entry : unknownRootFields.entrySet()) {
            if (entry.getKey().contains(":")) {
                merged.putIfAbsent(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return Map.copyOf(merged);
    }

    public JsonElement blockstates() {
        return copy("blockstates");
    }

    public JsonObject unknownRootFields() {
        return unknownRootFields.deepCopy();
    }

    public Set<String> unknownRootFieldNames() {
        return Set.copyOf(new LinkedHashSet<>(unknownRootFields.keySet()));
    }

    public JsonObject raw() {
        return raw.deepCopy();
    }

    private JsonObject object(String key) {
        JsonElement value = raw.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject().deepCopy() : new JsonObject();
    }

    private JsonElement copy(String key) {
        JsonElement value = raw.get(key);
        return value == null ? new JsonObject() : value.deepCopy();
    }
}
