package com.spirit.koil.api.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Small provider-neutral validator for the JSON-schema subset used by Koil
 * model tools. Registry execution remains authoritative, but preflight can now
 * reject malformed speculative calls before they consume a tool round.
 */
public final class ModelToolSchemaValidator {
    private static final int MAXIMUM_ERRORS = 12;

    private ModelToolSchemaValidator() {
    }

    public static List<String> validate(JsonObject schema, JsonObject arguments) {
        if (schema == null || schema.entrySet().isEmpty()) return List.of();
        List<String> errors = new ArrayList<>();
        validateValue(schema, arguments == null ? new JsonObject() : arguments, "$", errors);
        return List.copyOf(errors);
    }

    private static void validateValue(JsonObject schema, JsonElement value, String path, List<String> errors) {
        if (errors.size() >= MAXIMUM_ERRORS || schema == null) return;
        String type = string(schema, "type");
        if (!type.isBlank() && !matchesType(type, value)) {
            add(errors, "schema:" + path + ":expected_" + type);
            return;
        }
        validateEnum(schema, value, path, errors);
        if (errors.size() >= MAXIMUM_ERRORS) return;
        switch (type) {
            case "object" -> validateObject(schema, value.getAsJsonObject(), path, errors);
            case "array" -> validateArray(schema, value.getAsJsonArray(), path, errors);
            case "string" -> validateString(schema, value.getAsString(), path, errors);
            case "integer", "number" -> validateNumber(schema, value, path, errors, "integer".equals(type));
            default -> {
                // Empty/unknown schema types are left to the registry-specific executor.
            }
        }
    }

    private static void validateObject(JsonObject schema, JsonObject value, String path, List<String> errors) {
        JsonArray required = array(schema, "required");
        if (required != null) {
            for (JsonElement element : required) {
                if (errors.size() >= MAXIMUM_ERRORS) return;
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) continue;
                String key = element.getAsString();
                if (!value.has(key) || value.get(key).isJsonNull()) {
                    add(errors, "schema:" + path + ":missing:" + key);
                }
            }
        }
        JsonObject properties = object(schema, "properties");
        boolean additionalAllowed = !schema.has("additionalProperties")
                || !schema.get("additionalProperties").isJsonPrimitive()
                || schema.get("additionalProperties").getAsBoolean();
        for (var entry : value.entrySet()) {
            if (errors.size() >= MAXIMUM_ERRORS) return;
            JsonObject propertySchema = properties == null ? null : object(properties, entry.getKey());
            if (propertySchema == null) {
                if (!additionalAllowed) add(errors, "schema:" + path + ":unexpected:" + entry.getKey());
                continue;
            }
            validateValue(propertySchema, entry.getValue(), path + "." + entry.getKey(), errors);
        }
    }

    private static void validateArray(JsonObject schema, JsonArray value, String path, List<String> errors) {
        Integer minimum = integer(schema, "minItems");
        Integer maximum = integer(schema, "maxItems");
        if (minimum != null && value.size() < minimum) add(errors, "schema:" + path + ":minItems=" + minimum);
        if (maximum != null && value.size() > maximum) add(errors, "schema:" + path + ":maxItems=" + maximum);
        JsonObject items = object(schema, "items");
        if (items == null) return;
        for (int index = 0; index < value.size() && errors.size() < MAXIMUM_ERRORS; index++) {
            validateValue(items, value.get(index), path + "[" + index + "]", errors);
        }
    }

    private static void validateString(JsonObject schema, String value, String path, List<String> errors) {
        Integer minimum = integer(schema, "minLength");
        Integer maximum = integer(schema, "maxLength");
        if (minimum != null && value.length() < minimum) add(errors, "schema:" + path + ":minLength=" + minimum);
        if (maximum != null && value.length() > maximum) add(errors, "schema:" + path + ":maxLength=" + maximum);
        String pattern = string(schema, "pattern");
        if (!pattern.isBlank()) {
            try {
                if (!Pattern.compile(pattern).matcher(value).find()) add(errors, "schema:" + path + ":pattern");
            } catch (PatternSyntaxException invalidSchema) {
                add(errors, "schema:" + path + ":invalid_pattern_definition");
            }
        }
    }

    private static void validateNumber(
            JsonObject schema,
            JsonElement value,
            String path,
            List<String> errors,
            boolean integerOnly
    ) {
        BigDecimal number;
        try {
            number = value.getAsBigDecimal();
        } catch (RuntimeException invalid) {
            add(errors, "schema:" + path + ":invalid_number");
            return;
        }
        if (integerOnly && number.stripTrailingZeros().scale() > 0) {
            add(errors, "schema:" + path + ":expected_integer");
            return;
        }
        BigDecimal minimum = decimal(schema, "minimum");
        BigDecimal maximum = decimal(schema, "maximum");
        if (minimum != null && number.compareTo(minimum) < 0) add(errors, "schema:" + path + ":minimum=" + minimum);
        if (maximum != null && number.compareTo(maximum) > 0) add(errors, "schema:" + path + ":maximum=" + maximum);
    }

    private static void validateEnum(JsonObject schema, JsonElement value, String path, List<String> errors) {
        JsonArray allowed = array(schema, "enum");
        if (allowed == null) return;
        for (JsonElement candidate : allowed) {
            if (candidate.equals(value)) return;
        }
        add(errors, "schema:" + path + ":enum");
    }

    private static boolean matchesType(String type, JsonElement value) {
        if (value == null || value.isJsonNull()) return false;
        return switch (type) {
            case "object" -> value.isJsonObject();
            case "array" -> value.isJsonArray();
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            default -> true;
        };
    }

    private static String string(JsonObject object, String key) {
        try {
            JsonPrimitive primitive = object.getAsJsonPrimitive(key);
            return primitive == null || !primitive.isString() ? "" : primitive.getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static Integer integer(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static BigDecimal decimal(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsBigDecimal() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static void add(List<String> errors, String value) {
        if (errors.size() < MAXIMUM_ERRORS) errors.add(value);
    }
}
