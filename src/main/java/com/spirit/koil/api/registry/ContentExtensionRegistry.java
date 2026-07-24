package com.spirit.koil.api.registry;

import com.google.gson.JsonElement;
import com.spirit.koil.api.registry.definition.ContentDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.spirit.Main.SUBLOGGER;

/** Thread-safe API registry for mod-provided namespaced Content extensions. */
public final class ContentExtensionRegistry {
    private static final ConcurrentHashMap<String, ContentExtensionHandler> HANDLERS =
            new ConcurrentHashMap<>();

    private ContentExtensionRegistry() {
    }

    public static void register(ContentExtensionHandler handler) {
        if (handler == null || !isNamespaced(handler.extensionId())) {
            throw new IllegalArgumentException("Content extension ids must be namespaced");
        }
        ContentExtensionHandler previous = HANDLERS.putIfAbsent(handler.extensionId(), handler);
        if (previous != null && previous != handler) {
            throw new IllegalStateException("Content extension handler already registered: " + handler.extensionId());
        }
    }

    public static void unregister(String extensionId) {
        if (extensionId != null) {
            HANDLERS.remove(extensionId);
        }
    }

    public static Optional<ContentExtensionHandler> handler(String extensionId) {
        return Optional.ofNullable(HANDLERS.get(extensionId));
    }

    public static Collection<String> registeredExtensionIds() {
        return List.copyOf(HANDLERS.keySet());
    }

    public static List<WorldContentIndex.ValidationMessage> validate(ContentDefinition definition) {
        List<WorldContentIndex.ValidationMessage> messages = new ArrayList<>();
        for (var entry : definition.sections().extensionSections().entrySet()) {
            ContentExtensionHandler handler = HANDLERS.get(entry.getKey());
            if (handler == null) {
                messages.add(WorldContentIndex.ValidationMessage.warning(
                        "unknown_extension_section",
                        "Extension section \"" + entry.getKey() + "\" was preserved but has no installed handler.",
                        definition.sourcePath(),
                        "Install/register the owning mod extension handler to enable runtime behavior."
                ));
                continue;
            }
            try {
                messages.addAll(handler.validate(definition, entry.getValue().deepCopy()));
            } catch (RuntimeException exception) {
                messages.add(WorldContentIndex.ValidationMessage.error(
                        "extension_validation_failed",
                        "Extension validator \"" + entry.getKey() + "\" failed: " + exception.getMessage(),
                        definition.sourcePath(),
                        "Update the extension handler or correct its extension data."
                ));
            }
        }
        return List.copyOf(messages);
    }

    static void synchronize(
            Map<String, ContentDefinition> previous,
            Map<String, ContentDefinition> active
    ) {
        for (var entry : previous.entrySet()) {
            ContentDefinition replacement = active.get(entry.getKey());
            if (replacement == null || !replacement.raw().equals(entry.getValue().raw())) {
                dispatch(entry.getValue(), false);
            }
        }
        for (var entry : active.entrySet()) {
            ContentDefinition old = previous.get(entry.getKey());
            if (old == null || !old.raw().equals(entry.getValue().raw())) {
                dispatch(entry.getValue(), true);
            }
        }
    }

    private static void dispatch(ContentDefinition definition, boolean activate) {
        for (var entry : definition.sections().extensionSections().entrySet()) {
            ContentExtensionHandler handler = HANDLERS.get(entry.getKey());
            if (handler == null) {
                continue;
            }
            try {
                JsonElement data = entry.getValue().deepCopy();
                if (activate) {
                    handler.onDefinitionActivated(definition, data);
                } else {
                    handler.onDefinitionDeactivated(definition, data);
                }
            } catch (RuntimeException exception) {
                SUBLOGGER.logE(
                        "Content Registry",
                        "Extension handler " + entry.getKey() + " failed without blocking activation: "
                                + exception.getMessage()
                );
            }
        }
    }

    private static boolean isNamespaced(String value) {
        if (value == null) {
            return false;
        }
        int separator = value.indexOf(':');
        return separator > 0 && separator < value.length() - 1;
    }
}
