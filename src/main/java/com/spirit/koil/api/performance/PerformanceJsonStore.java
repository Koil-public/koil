package com.spirit.koil.api.performance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PerformanceJsonStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private PerformanceJsonStore() {
    }

    public static void write(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(value, writer);
            }
        } catch (Exception ignored) {
        }
    }

    public static JsonArray readArray(Path path) {
        try {
            if (!Files.exists(path)) {
                return new JsonArray();
            }
            try (Reader reader = Files.newBufferedReader(path)) {
                JsonElement element = JsonParser.parseReader(reader);
                return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
            }
        } catch (Exception ignored) {
            return new JsonArray();
        }
    }

    public static void append(Path path, Object value) {
        JsonArray array = readArray(path);
        array.add(GSON.toJsonTree(value));
        write(path, array);
    }
}
