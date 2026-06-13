package com.spirit.client.gui.mod.modconfig;

import com.google.gson.*;
import com.spirit.koil.api.util.file.json.JSONFileEditor;
import com.spirit.koil.api.util.file.json5.JSON5FileHandler;
import com.spirit.koil.api.util.file.properties.PropertiesFileHandler;
import com.spirit.koil.api.util.file.toml.TOMLFileHandler;
import com.spirit.koil.api.util.file.yaml.YamlFileHandler;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

public class ModConfigDocument {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final File file;
    private final ConfigFormat format;
    private final Object formatState;
    private JsonObject root;

    public ModConfigDocument(File file, ConfigFormat format, JsonObject root) {
        this(file, format, root, null);
    }

    public ModConfigDocument(File file, ConfigFormat format, JsonObject root, Object formatState) {
        this.file = file;
        this.format = format;
        this.root = root;
        this.formatState = formatState;
    }

    public static ModConfigDocument load(File file) throws IOException {
        ConfigFormat format = ConfigFormat.fromFile(file);
        return switch (format) {
            case JSON -> new ModConfigDocument(file, format, JSONFileEditor.getJsonObjectFromFile(file));
            case JSON5 -> new ModConfigDocument(file, format, JSON5FileHandler.parseJSON5File(file));
            case PROPERTIES -> {
                PropertiesFileHandler.PropertiesDocument propertiesDocument = PropertiesFileHandler.loadDocument(file);
                yield new ModConfigDocument(file, format, propertiesDocument.root(), propertiesDocument);
            }
            case TOML -> new ModConfigDocument(file, format, TOMLFileHandler.parseTomlFile(file));
            case YAML -> new ModConfigDocument(file, format, YamlFileHandler.parseYamlFile(file));
            case UNKNOWN -> new ModConfigDocument(file, format, new JsonObject());
        };
    }

    public File getFile() {
        return file;
    }

    public ConfigFormat getFormat() {
        return format;
    }

    public JsonObject getRoot() {
        return root;
    }

    public ModConfigDocument recreateWithRoot(JsonObject nextRoot) {
        return new ModConfigDocument(file, format, nextRoot, formatState);
    }

    public void setValue(String path, JsonElement value) {
        String[] parts = path.split("\\.");
        JsonObject parent = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (!parent.has(part) || !parent.get(part).isJsonObject()) {
                parent.add(part, new JsonObject());
            }
            parent = parent.getAsJsonObject(part);
        }
        parent.add(parts[parts.length - 1], value);
    }

    public void removeValue(String path) {
        String[] parts = path.split("\\.");
        JsonObject parent = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            if (!parent.has(part) || !parent.get(part).isJsonObject()) {
                return;
            }
            parent = parent.getAsJsonObject(part);
        }
        parent.remove(parts[parts.length - 1]);
    }

    public void save() throws IOException {
        switch (format) {
            case JSON -> JSONFileEditor.writeJsonToFile(file.getAbsolutePath(), root);
            case JSON5 -> JSON5FileHandler.writeJSON5File(root, file);
            case PROPERTIES -> {
                if (formatState instanceof PropertiesFileHandler.PropertiesDocument propertiesDocument) {
                    propertiesDocument.save(root, file);
                } else {
                    PropertiesFileHandler.savePropertiesFile(root, file);
                }
            }
            case TOML -> TOMLFileHandler.saveTomlFile(root, file);
            case YAML -> YamlFileHandler.saveYamlFile(root, file);
            case UNKNOWN -> throw new IOException("Unsupported config format for " + file.getName());
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ModConfigDocument that)) return false;
        return Objects.equals(file, that.file) && format == that.format;
    }

    @Override
    public int hashCode() {
        return Objects.hash(file, format);
    }
}
