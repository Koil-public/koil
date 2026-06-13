package com.spirit.client.gui.mod.modconfig;

import java.io.File;
import java.util.Locale;

public enum ConfigFormat {
    JSON,
    JSON5,
    PROPERTIES,
    TOML,
    YAML,
    UNKNOWN;

    public static ConfigFormat fromFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".json")) return JSON;
        if (name.endsWith(".json5")) return JSON5;
        if (name.endsWith(".properties") || name.endsWith(".cfg") || name.endsWith(".conf") || name.endsWith(".ini") || name.endsWith(".txt")) {
            return PROPERTIES;
        }
        if (name.endsWith(".toml")) return TOML;
        if (name.endsWith(".yaml") || name.endsWith(".yml")) return YAML;
        return UNKNOWN;
    }
}
