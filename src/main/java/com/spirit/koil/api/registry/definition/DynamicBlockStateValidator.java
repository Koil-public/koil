package com.spirit.koil.api.registry.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.spirit.koil.api.registry.DynamicStatePropertyHandlers;
import com.spirit.koil.api.registry.WorldContentIndex;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Version-neutral structural validation for data-driven blockstate descriptors. */
public final class DynamicBlockStateValidator {
    private static final Set<String> DIRECTIONS = Set.of("down", "up", "north", "south", "west", "east");
    private static final Set<String> AXES = Set.of("x", "y", "z");

    private DynamicBlockStateValidator() {
    }

    public static List<WorldContentIndex.ValidationMessage> validate(
            ContentDefinition definition,
            DynamicBlockStateDefinition blockStates
    ) {
        if (!"block".equals(definition.type())) {
            return List.of();
        }
        List<WorldContentIndex.ValidationMessage> messages = new ArrayList<>();
        JsonElement rawSection = definition.raw().get("blockstates");
        if (rawSection == null) {
            return List.of();
        }
        if (!rawSection.isJsonObject()) {
            return List.of(error(
                    definition,
                    "invalid_blockstates_type",
                    "\"blockstates\" must be a JSON object.",
                    "Replace it with an object containing properties, variants, multipart, and behavior_mapping."
            ));
        }
        JsonObject raw = rawSection.getAsJsonObject();
        validateSectionType(definition, raw, "properties", true, messages);
        validateSectionType(definition, raw, "variants", false, messages);
        validateSectionType(definition, raw, "multipart", true, messages);
        validateSectionType(definition, raw, "behavior_mapping", false, messages);
        validateSectionType(definition, raw, "unknown_values", false, messages);

        Set<String> names = new HashSet<>();
        for (DynamicStatePropertyDefinition property : blockStates.properties()) {
            validateProperty(definition, property, names, messages);
        }
        try {
            long states = blockStates.materializedStateCount();
            if (states > DynamicBlockStateDefinition.MAX_MATERIALIZED_STATES) {
                messages.add(error(
                        definition,
                        "blockstate_combination_limit",
                        "Materialized blockstate properties would create " + states + " states; the safe limit is "
                                + DynamicBlockStateDefinition.MAX_MATERIALIZED_STATES + ".",
                        "Reduce allowed values or move non-state data into components/metadata."
                ));
            }
        } catch (ArithmeticException exception) {
            messages.add(error(
                    definition,
                    "blockstate_combination_overflow",
                    "The blockstate property combinations exceed the supported count.",
                    "Reduce the number of properties and allowed values."
            ));
        }

        validateSelectors(definition, blockStates, blockStates.variants(), "variant", messages);
        validateSelectors(definition, blockStates, blockStates.behaviorMappings(), "behavior mapping", messages);
        return List.copyOf(messages);
    }

    private static void validateProperty(
            ContentDefinition definition,
            DynamicStatePropertyDefinition property,
            Set<String> names,
            List<WorldContentIndex.ValidationMessage> messages
    ) {
        if (property.name().isBlank()) {
            messages.add(error(
                    definition,
                    "missing_blockstate_property_name",
                    "A blockstate property is missing its name.",
                    "Give every blockstate property a unique lowercase name."
            ));
            return;
        }
        if (!names.add(property.name())) {
            messages.add(error(
                    definition,
                    "duplicate_blockstate_property",
                    "Blockstate property \"" + property.name() + "\" is declared more than once.",
                    "Keep one declaration for the property."
            ));
        }
        if (property.allowedValues().isEmpty()) {
            messages.add(error(
                    definition,
                    "missing_blockstate_values",
                    "Blockstate property \"" + property.name() + "\" has no allowed values.",
                    "Add allowed_values or valid minimum/maximum bounds."
            ));
            return;
        }
        if (property.materializable() && property.allowedValues().size() < 2) {
            messages.add(error(
                    definition,
                    "blockstate_property_requires_multiple_values",
                    "Materialized property \"" + property.name() + "\" must have at least two allowed values.",
                    "Add another allowed value or move the fixed value into properties/metadata."
            ));
        }
        if (!property.allowedValues().contains(property.defaultValue())) {
            messages.add(error(
                    definition,
                    "invalid_blockstate_default",
                    "Default value \"" + property.defaultValue() + "\" is not allowed for \""
                            + property.name() + "\".",
                    "Choose one of: " + String.join(", ", property.allowedValues())
            ));
        }

        switch (property.type()) {
            case BOOLEAN -> {
                if (!Set.copyOf(property.allowedValues()).equals(Set.of("false", "true"))) {
                    messages.add(error(
                            definition,
                            "invalid_boolean_blockstate_values",
                            "Boolean property \"" + property.name() + "\" must allow false and true.",
                            "Set allowed_values to [false, true]."
                    ));
                }
            }
            case INTEGER -> validateIntegers(definition, property, messages);
            case DIRECTION -> validateValueSet(definition, property, DIRECTIONS, "direction", messages);
            case AXIS -> validateValueSet(definition, property, AXES, "axis", messages);
            case ENUM -> {
                // Generic string-backed enums are supported when every value is a
                // lowercase vanilla property token.
            }
            case CUSTOM -> {
                String typeId = property.raw().has("type")
                        ? property.raw().get("type").getAsString()
                        : "";
                if (!DynamicStatePropertyHandlers.hasHandler(typeId)) {
                    messages.add(warning(
                            definition,
                            "custom_blockstate_preserved",
                            "Custom blockstate property \"" + property.name()
                                    + "\" is preserved but has no Minecraft 1.20.1 physical property handler.",
                            "Register a custom property parser or use boolean/integer/enum/direction/axis."
                    ));
                }
            }
        }
        messages.addAll(DynamicStatePropertyHandlers.validate(property, definition.sourcePath()));

        if (!property.materializable() && property.type() != DynamicStatePropertyDefinition.PropertyType.CUSTOM) {
            messages.add(warning(
                    definition,
                    "blockstate_property_not_materialized",
                    "Blockstate property \"" + property.name()
                            + "\" is preserved but cannot be encoded as a vanilla 1.20.1 property.",
                    "Use lowercase [a-z0-9_] names/values or provide an extension parser."
            ));
        }
    }

    private static void validateIntegers(
            ContentDefinition definition,
            DynamicStatePropertyDefinition property,
            List<WorldContentIndex.ValidationMessage> messages
    ) {
        for (String value : property.allowedValues()) {
            try {
                Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                messages.add(error(
                        definition,
                        "invalid_integer_blockstate_value",
                        "\"" + value + "\" is not an integer for property \"" + property.name() + "\".",
                        "Use integer allowed_values only."
                ));
            }
        }
        if (property.minimum() != null && property.maximum() != null
                && property.maximum() < property.minimum()) {
            messages.add(error(
                    definition,
                    "invalid_blockstate_range",
                    "Maximum is below minimum for property \"" + property.name() + "\".",
                    "Set maximum greater than or equal to minimum."
            ));
        }
    }

    private static void validateValueSet(
            ContentDefinition definition,
            DynamicStatePropertyDefinition property,
            Set<String> valid,
            String type,
            List<WorldContentIndex.ValidationMessage> messages
    ) {
        for (String value : property.allowedValues()) {
            if (!valid.contains(value)) {
                messages.add(error(
                        definition,
                        "invalid_" + type + "_blockstate_value",
                        "\"" + value + "\" is not a valid " + type + " value for \"" + property.name() + "\".",
                        "Use values from: " + String.join(", ", valid)
                ));
            }
        }
    }

    private static void validateSelectors(
            ContentDefinition definition,
            DynamicBlockStateDefinition blockStates,
            JsonObject mappings,
            String label,
            List<WorldContentIndex.ValidationMessage> messages
    ) {
        for (String selector : mappings.keySet()) {
            if (selector.isBlank()) {
                continue;
            }
            for (String condition : selector.split(",")) {
                String[] pair = condition.trim().split("=", 2);
                if (pair.length != 2) {
                    messages.add(error(
                            definition,
                            "invalid_blockstate_selector",
                            "Invalid " + label + " selector \"" + selector + "\".",
                            "Use property=value pairs separated by commas."
                    ));
                    continue;
                }
                DynamicStatePropertyDefinition property =
                        blockStates.property(pair[0].trim().toLowerCase(Locale.ROOT));
                if (property == null) {
                    messages.add(error(
                            definition,
                            "unknown_blockstate_selector_property",
                            "Selector \"" + selector + "\" references unknown property \"" + pair[0].trim() + "\".",
                            "Declare the property or correct the selector."
                    ));
                } else if (!property.allowedValues().contains(pair[1].trim().toLowerCase(Locale.ROOT))) {
                    messages.add(error(
                            definition,
                            "invalid_blockstate_selector_value",
                            "Selector \"" + selector + "\" uses an unsupported value for \"" + property.name() + "\".",
                            "Use one of: " + String.join(", ", property.allowedValues())
                    ));
                }
            }
        }
    }

    private static void validateSectionType(
            ContentDefinition definition,
            JsonObject root,
            String key,
            boolean array,
            List<WorldContentIndex.ValidationMessage> messages
    ) {
        JsonElement value = root.get(key);
        if (value == null || (array ? value.isJsonArray() : value.isJsonObject())) {
            return;
        }
        messages.add(error(
                definition,
                "invalid_blockstates_" + key + "_type",
                "\"blockstates." + key + "\" must be a JSON " + (array ? "array" : "object") + ".",
                "Change the section type without deleting its custom values."
        ));
    }

    private static WorldContentIndex.ValidationMessage error(
            ContentDefinition definition,
            String code,
            String message,
            String suggestion
    ) {
        return WorldContentIndex.ValidationMessage.error(code, message, definition.sourcePath(), suggestion);
    }

    private static WorldContentIndex.ValidationMessage warning(
            ContentDefinition definition,
            String code,
            String message,
            String suggestion
    ) {
        return WorldContentIndex.ValidationMessage.warning(code, message, definition.sourcePath(), suggestion);
    }
}
