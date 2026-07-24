package com.spirit.koil.api.registry.definition;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Version-neutral, raw-preserving blockstate descriptor.
 *
 * <p>Physical property materialization is delegated to the selected Minecraft
 * adapter. This class intentionally keeps variants, multipart rules, behavior
 * mappings, and unknown values even when the current runtime cannot apply them.</p>
 */
public final class DynamicBlockStateDefinition {
    public static final int MAX_MATERIALIZED_STATES = 256;
    private static final Pattern MINECRAFT_PROPERTY_NAME = Pattern.compile("[a-z0-9_]+");
    private static final Pattern MINECRAFT_PROPERTY_VALUE = Pattern.compile("[a-z0-9_]+");

    private final List<DynamicStatePropertyDefinition> properties;
    private final JsonObject variants;
    private final JsonArray multipart;
    private final JsonObject behaviorMappings;
    private final JsonObject unknownValues;
    private final JsonObject raw;

    private DynamicBlockStateDefinition(
            List<DynamicStatePropertyDefinition> properties,
            JsonObject variants,
            JsonArray multipart,
            JsonObject behaviorMappings,
            JsonObject unknownValues,
            JsonObject raw
    ) {
        this.properties = List.copyOf(properties);
        this.variants = variants.deepCopy();
        this.multipart = multipart.deepCopy();
        this.behaviorMappings = behaviorMappings.deepCopy();
        this.unknownValues = unknownValues.deepCopy();
        this.raw = raw.deepCopy();
    }

    public static DynamicBlockStateDefinition parse(ContentDefinition definition) {
        JsonElement section = definition.sections().blockstates();
        JsonObject root = section != null && section.isJsonObject()
                ? section.getAsJsonObject()
                : new JsonObject();
        List<DynamicStatePropertyDefinition> properties = new ArrayList<>();
        JsonElement propertyElement = root.get("properties");
        if (propertyElement != null && propertyElement.isJsonArray()) {
            for (JsonElement element : propertyElement.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    properties.add(parseProperty(element.getAsJsonObject()));
                }
            }
        }
        return new DynamicBlockStateDefinition(
                properties,
                object(root, "variants"),
                array(root, "multipart"),
                object(root, "behavior_mapping"),
                object(root, "unknown_values"),
                root
        );
    }

    public List<DynamicStatePropertyDefinition> properties() {
        return properties;
    }

    public JsonObject variants() {
        return variants.deepCopy();
    }

    public JsonArray multipart() {
        return multipart.deepCopy();
    }

    public JsonObject behaviorMappings() {
        return behaviorMappings.deepCopy();
    }

    public JsonObject unknownValues() {
        return unknownValues.deepCopy();
    }

    public JsonObject raw() {
        return raw.deepCopy();
    }

    public long materializedStateCount() {
        long count = 1;
        for (DynamicStatePropertyDefinition property : properties) {
            if (!property.materializable()) {
                continue;
            }
            count = Math.multiplyExact(count, Math.max(1, property.allowedValues().size()));
            if (count > MAX_MATERIALIZED_STATES) {
                return count;
            }
        }
        return count;
    }

    public DynamicStatePropertyDefinition property(String name) {
        for (DynamicStatePropertyDefinition property : properties) {
            if (property.name().equals(name)) {
                return property;
            }
        }
        return null;
    }

    private static DynamicStatePropertyDefinition parseProperty(JsonObject raw) {
        String name = string(raw, "name").toLowerCase(Locale.ROOT);
        DynamicStatePropertyDefinition.PropertyType type =
                DynamicStatePropertyDefinition.PropertyType.parse(string(raw, "type"), name);
        List<String> values = allowedValues(raw, type);
        String defaultValue = scalar(raw.get("default"));
        if (defaultValue.isBlank() && !values.isEmpty()) {
            defaultValue = values.get(0);
        }
        Integer minimum = integerOrNull(raw.get("minimum"));
        Integer maximum = integerOrNull(raw.get("maximum"));
        boolean materializable = MINECRAFT_PROPERTY_NAME.matcher(name).matches()
                && !values.isEmpty()
                && values.stream().allMatch(value -> MINECRAFT_PROPERTY_VALUE.matcher(value).matches())
                && type != DynamicStatePropertyDefinition.PropertyType.CUSTOM;
        return new DynamicStatePropertyDefinition(
                name,
                type,
                values,
                defaultValue,
                minimum,
                maximum,
                materializable,
                raw
        );
    }

    private static List<String> allowedValues(
            JsonObject raw,
            DynamicStatePropertyDefinition.PropertyType type
    ) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        JsonElement allowed = raw.get("allowed_values");
        if (allowed != null && allowed.isJsonArray()) {
            for (JsonElement value : allowed.getAsJsonArray()) {
                String parsed = scalar(value);
                if (!parsed.isBlank()) {
                    values.add(parsed);
                }
            }
        }
        if (values.isEmpty()) {
            switch (type) {
                case BOOLEAN -> {
                    values.add("false");
                    values.add("true");
                }
                case DIRECTION -> {
                    values.add("north");
                    values.add("east");
                    values.add("south");
                    values.add("west");
                }
                case AXIS -> {
                    values.add("x");
                    values.add("y");
                    values.add("z");
                }
                case INTEGER -> {
                    Integer minimum = integerOrNull(raw.get("minimum"));
                    Integer maximum = integerOrNull(raw.get("maximum"));
                    if (minimum != null && maximum != null && maximum >= minimum && maximum - minimum <= 31) {
                        for (int value = minimum; value <= maximum; value++) {
                            values.add(Integer.toString(value));
                        }
                    }
                }
                default -> {
                }
            }
        }
        return List.copyOf(values);
    }

    private static String scalar(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            return "";
        }
        try {
            return value.getAsString().trim().toLowerCase(Locale.ROOT);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String string(JsonObject object, String key) {
        return scalar(object.get(key));
    }

    private static Integer integerOrNull(JsonElement value) {
        try {
            return value != null && value.isJsonPrimitive() ? value.getAsInt() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject().deepCopy() : new JsonObject();
    }

    private static JsonArray array(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray().deepCopy() : new JsonArray();
    }
}
