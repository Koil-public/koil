package com.spirit.koil.api.registry.definition;

import com.google.gson.JsonObject;

import java.util.List;

/** Preserved, version-neutral description of one data-driven block-state property. */
public record DynamicStatePropertyDefinition(
        String name,
        PropertyType type,
        List<String> allowedValues,
        String defaultValue,
        Integer minimum,
        Integer maximum,
        boolean materializable,
        JsonObject raw
) {
    public DynamicStatePropertyDefinition {
        name = name == null ? "" : name;
        type = type == null ? PropertyType.CUSTOM : type;
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        defaultValue = defaultValue == null ? "" : defaultValue;
        raw = raw == null ? new JsonObject() : raw.deepCopy();
    }

    public enum PropertyType {
        BOOLEAN,
        INTEGER,
        ENUM,
        DIRECTION,
        AXIS,
        CUSTOM;

        public static PropertyType parse(String value, String propertyName) {
            String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
            if (normalized.isBlank()) {
                if ("facing".equals(propertyName)) {
                    return DIRECTION;
                }
                if ("axis".equals(propertyName)) {
                    return AXIS;
                }
                return CUSTOM;
            }
            return switch (normalized) {
                case "bool", "boolean" -> BOOLEAN;
                case "int", "integer", "age" -> INTEGER;
                case "enum", "string" -> ENUM;
                case "direction", "facing" -> DIRECTION;
                case "axis" -> AXIS;
                default -> CUSTOM;
            };
        }
    }
}
