package com.spirit.koil.api.registry;

import com.spirit.koil.api.registry.definition.DynamicStatePropertyDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe custom blockstate property handler registry. */
public final class DynamicStatePropertyHandlers {
    private static final ConcurrentHashMap<String, DynamicStatePropertyHandler> HANDLERS =
            new ConcurrentHashMap<>();

    private DynamicStatePropertyHandlers() {
    }

    public static void register(DynamicStatePropertyHandler handler) {
        if (handler == null || handler.typeId() == null || handler.typeId().isBlank()) {
            throw new IllegalArgumentException("Dynamic state property handler requires a type id");
        }
        DynamicStatePropertyHandler previous = HANDLERS.putIfAbsent(handler.typeId(), handler);
        if (previous != null && previous != handler) {
            throw new IllegalStateException("Dynamic state property handler already registered: " + handler.typeId());
        }
    }

    public static void unregister(String typeId) {
        if (typeId != null) {
            HANDLERS.remove(typeId);
        }
    }

    public static Collection<String> registeredTypeIds() {
        return List.copyOf(HANDLERS.keySet());
    }

    public static boolean hasHandler(String typeId) {
        return typeId != null && HANDLERS.containsKey(typeId);
    }

    public static List<WorldContentIndex.ValidationMessage> validate(
            DynamicStatePropertyDefinition definition,
            String sourcePath
    ) {
        DynamicStatePropertyHandler handler = handler(definition);
        if (handler == null) {
            return List.of();
        }
        try {
            return List.copyOf(handler.validate(definition, sourcePath));
        } catch (RuntimeException exception) {
            return List.of(WorldContentIndex.ValidationMessage.error(
                    "custom_state_property_validation_failed",
                    "Custom state property handler \"" + typeId(definition)
                            + "\" failed: " + exception.getMessage(),
                    sourcePath,
                    "Update the owning mod extension handler or correct the property data."
            ));
        }
    }

    static Optional<MaterializedStateProperty<?>> materialize(
            DynamicStatePropertyDefinition definition
    ) {
        DynamicStatePropertyHandler handler = handler(definition);
        if (handler == null) {
            return Optional.empty();
        }
        try {
            return handler.materialize(definition);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static DynamicStatePropertyHandler handler(DynamicStatePropertyDefinition definition) {
        return HANDLERS.get(typeId(definition));
    }

    private static String typeId(DynamicStatePropertyDefinition definition) {
        var rawType = definition.raw().get("type");
        return rawType != null && rawType.isJsonPrimitive() ? rawType.getAsString() : "";
    }
}
