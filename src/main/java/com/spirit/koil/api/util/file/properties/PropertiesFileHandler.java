package com.spirit.koil.api.util.file.properties;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PropertiesFileHandler {
    public static PropertiesDocument loadDocument(File file) {
        List<Line> lines = new ArrayList<>();
        JsonObject result = new JsonObject();
        try {
            List<String> rawLines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String rawLine : rawLines) {
                Line line = parseLine(rawLine);
                lines.add(line);
                if (line instanceof PropertyLine propertyLine) {
                    addTypedProperty(result, propertyLine.key(), propertyLine.value());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new PropertiesDocument(lines, result);
    }

    public static JsonObject parsePropertiesFile(File file) {
        return loadDocument(file).root();
    }

    public static void savePropertiesFile(JsonObject jsonObject, File file) {
        loadDocument(file).save(jsonObject, file);
    }

    private static void flattenJson(String parentKey, JsonObject jsonObject, Map<String, String> flattened) {
        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
            String fullKey = parentKey + "." + entry.getKey();
            JsonElement value = entry.getValue();

            if (value.isJsonObject()) {
                flattenJson(fullKey, value.getAsJsonObject(), flattened);
            } else {
                flattened.put(fullKey, serializeValue(fullKey, value, null));
            }
        }
    }

    private static Line parseLine(String rawLine) {
        if (rawLine == null || rawLine.isEmpty()) {
            return new RawLine(rawLine == null ? "" : rawLine);
        }
        String trimmed = rawLine.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return new RawLine(rawLine);
        }

        int separatorIndex = findSeparator(rawLine);
        if (separatorIndex < 0) {
            return new RawLine(rawLine);
        }

        String key = rawLine.substring(0, separatorIndex).trim();
        int valueStart = separatorIndex + 1;
        while (valueStart < rawLine.length() && Character.isWhitespace(rawLine.charAt(valueStart))) {
            valueStart++;
        }
        String separator = String.valueOf(rawLine.charAt(separatorIndex));
        String value = valueStart >= rawLine.length() ? "" : rawLine.substring(valueStart);
        return new PropertyLine(rawLine, key, separator, value, detectListStyle(value));
    }

    private static int findSeparator(String rawLine) {
        boolean escaped = false;
        for (int i = 0; i < rawLine.length(); i++) {
            char c = rawLine.charAt(i);
            if (!escaped && (c == '=' || c == ':')) {
                return i;
            }
            escaped = c == '\\' && !escaped;
        }
        for (int i = 0; i < rawLine.length(); i++) {
            if (Character.isWhitespace(rawLine.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static void addTypedProperty(JsonObject root, String key, String rawValue) {
        String[] parts = key.split("\\.");
        JsonObject parent = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (!parent.has(part) || !parent.get(part).isJsonObject()) {
                parent.add(part, new JsonObject());
            }
            parent = parent.getAsJsonObject(part);
        }
        parent.add(parts[parts.length - 1], parseValue(key, rawValue));
    }

    private static JsonElement parseValue(String key, String rawValue) {
        if (rawValue == null) {
            return JsonNull.INSTANCE;
        }
        String value = rawValue.trim();
        if (value.isEmpty()) {
            return new JsonPrimitive("");
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return new JsonPrimitive(Boolean.parseBoolean(value));
        }
        try {
            if (value.contains(".") || value.contains("e") || value.contains("E")) {
                double doubleValue = Double.parseDouble(value);
                return new JsonPrimitive(doubleValue);
            }
            long longValue = Long.parseLong(value);
            if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
                return new JsonPrimitive((int) longValue);
            }
            return new JsonPrimitive(longValue);
        } catch (NumberFormatException ignored) {
        }

        if (looksLikeListKey(key) || isDelimitedList(value)) {
            JsonArray array = parseList(value);
            if (array != null) {
                return array;
            }
        }
        return new JsonPrimitive(value);
    }

    private static boolean looksLikeListKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("list")
                || normalized.contains("files")
                || normalized.contains("modules")
                || normalized.contains("presets")
                || normalized.contains("values")
                || normalized.contains("recent");
    }

    private static boolean isDelimitedList(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("[") && trimmed.endsWith("]")
                || (trimmed.contains(",") && !trimmed.contains("://"));
    }

    private static JsonArray parseList(String rawValue) {
        String trimmed = rawValue.trim();
        String body = trimmed;
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            body = trimmed.substring(1, trimmed.length() - 1);
        }
        if (body.isBlank()) {
            return new JsonArray();
        }
        String[] parts = body.split("\\s*,\\s*");
        if (parts.length <= 1) {
            return null;
        }
        JsonArray array = new JsonArray();
        for (String part : parts) {
            array.add(parseValue("", part));
        }
        return array;
    }

    private static String serializeValue(String key, JsonElement value, ListStyle listStyle) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (value.isJsonArray()) {
            List<String> values = new ArrayList<>();
            for (JsonElement element : value.getAsJsonArray()) {
                values.add(serializeScalar(element));
            }
            return listStyle == ListStyle.BRACKETED
                    ? "[" + String.join(", ", values) + "]"
                    : String.join(", ", values);
        }
        return serializeScalar(value);
    }

    private static String serializeScalar(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        if (!value.isJsonPrimitive()) {
            return value.toString();
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isString()) {
            return primitive.getAsString();
        }
        return primitive.getAsString();
    }

    private static ListStyle detectListStyle(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return ListStyle.BRACKETED;
        }
        if (trimmed.contains(",")) {
            return ListStyle.COMMA_SEPARATED;
        }
        return ListStyle.NONE;
    }

    public record PropertiesDocument(List<Line> lines, JsonObject root) {
        public void save(JsonObject jsonObject, File file) {
            Map<String, String> flattened = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    flattenJson(entry.getKey(), entry.getValue().getAsJsonObject(), flattened);
                } else {
                    flattened.put(entry.getKey(), serializeValue(entry.getKey(), entry.getValue(), null));
                }
            }

            List<String> output = new ArrayList<>();
            for (Line line : lines) {
                if (line instanceof RawLine rawLine) {
                    output.add(rawLine.raw());
                    continue;
                }
                PropertyLine propertyLine = (PropertyLine) line;
                if (!flattened.containsKey(propertyLine.key())) {
                    continue;
                }
                String value = flattened.remove(propertyLine.key());
                output.add(propertyLine.key() + propertyLine.separator() + value);
            }

            if (!flattened.isEmpty() && !output.isEmpty() && !output.get(output.size() - 1).isBlank()) {
                output.add("");
            }
            for (Map.Entry<String, String> entry : flattened.entrySet()) {
                output.add(entry.getKey() + "=" + entry.getValue());
            }

            try {
                Files.write(file.toPath(), output, StandardCharsets.UTF_8);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private sealed interface Line permits RawLine, PropertyLine {
    }

    private record RawLine(String raw) implements Line {
    }

    private record PropertyLine(String raw, String key, String separator, String value, ListStyle listStyle) implements Line {
    }

    private enum ListStyle {
        NONE,
        COMMA_SEPARATED,
        BRACKETED
    }
}
