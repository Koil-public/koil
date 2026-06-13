package com.spirit.koil.api.util.file.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;

public class JSONFileEditor {
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public static void updateValueInJson(String filePath, String key, JsonElement newValue) throws IOException {
        JsonObject jsonObject = readJsonFile(filePath);
        updateValue(jsonObject, key, newValue);
        writeJsonToFile(filePath, jsonObject);
    }

    public static void updateValue(JsonObject jsonObject, String key, JsonElement newValue) {
        if (jsonObject == null || key == null || key.isBlank()) {
            return;
        }
        if (key.contains(".")) {
            updateDotPath(jsonObject, key, newValue);
            return;
        }
        if (updateFirstMatchingKey(jsonObject, key, newValue == null ? JsonNull.INSTANCE : newValue)) {
            return;
        }
        jsonObject.add(key, newValue == null ? JsonNull.INSTANCE : newValue);
    }

    public static JsonElement getValueFromJson(String filePath, String key) {
        JsonObject jsonObject = readJsonFile(filePath);
        return getValueFromJson(jsonObject, key);
    }

    public static JsonElement getValueFromJson(JsonObject jsonObject, String key) {
        if (jsonObject == null || key == null || key.isBlank()) {
            return null;
        }
        JsonElement dotPathValue = getDotPathValue(jsonObject, key);
        if (dotPathValue != null) {
            return dotPathValue;
        }
        return findValueRecursive(jsonObject, key);
    }

    public static boolean hasValueInJson(String filePath, String key) {
        return getValueFromJson(filePath, key) != null;
    }

    public static JsonObject getJsonObjectFromFile(File file) {
        JsonElement element = readJsonFile(String.valueOf(file));
        if (element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        throw new JsonSyntaxException("Expected a JsonObject but found: " + element.getClass().getSimpleName());
    }

    public static JsonObject readJsonFile(String filePath) {
        try (FileReader reader = new FileReader(filePath)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || element.isJsonNull()) {
                return new JsonObject();
            }
            if (!element.isJsonObject()) {
                throw new JsonSyntaxException("Expected root JsonObject in " + filePath);
            }
            return element.getAsJsonObject();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeJsonToFile(String filePath, JsonObject jsonObject) throws IOException {
        try (FileWriter writer = new FileWriter(filePath)) {
            gson.toJson(jsonObject, writer);
        }
    }

    private static JsonElement getDotPathValue(JsonObject root, String key) {
        if (!key.contains(".")) {
            return root.has(key) ? root.get(key) : null;
        }
        JsonElement current = root;
        for (String part : key.split("\\.")) {
            if (part.isBlank() || current == null || !current.isJsonObject()) {
                return null;
            }
            JsonObject object = current.getAsJsonObject();
            if (!object.has(part)) {
                return null;
            }
            current = object.get(part);
        }
        return current;
    }

    private static JsonElement findValueRecursive(JsonObject object, String key) {
        if (object.has(key)) {
            return object.get(key);
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonObject()) {
                JsonElement found = findValueRecursive(value.getAsJsonObject(), key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean updateFirstMatchingKey(JsonObject object, String key, JsonElement newValue) {
        if (object.has(key)) {
            object.add(key, newValue);
            return true;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonObject() && updateFirstMatchingKey(value.getAsJsonObject(), key, newValue)) {
                return true;
            }
        }
        return false;
    }

    private static void updateDotPath(JsonObject root, String key, JsonElement newValue) {
        String[] parts = key.split("\\.");
        JsonObject current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (part.isBlank()) {
                continue;
            }
            JsonElement next = current.get(part);
            if (next == null || !next.isJsonObject()) {
                JsonObject created = new JsonObject();
                current.add(part, created);
                current = created;
            } else {
                current = next.getAsJsonObject();
            }
        }
        current.add(parts[parts.length - 1], newValue == null ? JsonNull.INSTANCE : newValue);
    }
}
