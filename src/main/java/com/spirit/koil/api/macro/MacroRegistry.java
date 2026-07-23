package com.spirit.koil.api.macro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class MacroRegistry {
    private static final Path STORAGE_PATH = Path.of("koil", "sys", "macros.json");
    private static final long MODIFIED_CHECK_INTERVAL_MILLIS = 1_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static List<MacroDefinition> cached = List.of();
    private static long cachedModified = Long.MIN_VALUE;
    private static long lastModifiedCheckMillis;

    private MacroRegistry() {
    }

    public static synchronized List<MacroDefinition> all() {
        reloadIfNeeded();
        return cached;
    }

    public static synchronized Optional<MacroDefinition> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return all().stream().filter(macro -> id.equals(macro.id())).findFirst();
    }

    public static synchronized void upsert(MacroDefinition macro) {
        if (macro == null) {
            return;
        }
        List<MacroDefinition> updated = new ArrayList<>(all());
        updated.removeIf(existing -> existing.id().equals(macro.id()));
        updated.add(macro);
        updated.sort(Comparator.comparing(MacroDefinition::name, String.CASE_INSENSITIVE_ORDER));
        write(updated);
    }

    public static synchronized void delete(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        List<MacroDefinition> updated = new ArrayList<>(all());
        if (updated.removeIf(macro -> id.equals(macro.id()))) {
            write(updated);
        }
    }

    public static synchronized void setEnabled(String id, boolean enabled) {
        find(id).ifPresent(macro -> upsert(new MacroDefinition(
                macro.id(),
                macro.name(),
                enabled,
                macro.triggerType(),
                macro.triggerCode(),
                macro.actions()
        )));
    }

    private static void reloadIfNeeded() {
        ensureStorage();
        long now = System.currentTimeMillis();
        if (cachedModified != Long.MIN_VALUE
                && now - lastModifiedCheckMillis < MODIFIED_CHECK_INTERVAL_MILLIS) {
            return;
        }
        lastModifiedCheckMillis = now;
        long modified = modifiedTime();
        if (modified == cachedModified) {
            return;
        }
        cached = read();
        cachedModified = modified;
    }

    private static List<MacroDefinition> read() {
        try {
            JsonElement rootElement = JsonParser.parseString(Files.readString(STORAGE_PATH, StandardCharsets.UTF_8));
            if (!rootElement.isJsonObject()) {
                return List.of();
            }
            JsonArray array = rootElement.getAsJsonObject().getAsJsonArray("macros");
            if (array == null) {
                return List.of();
            }
            List<MacroDefinition> macros = new ArrayList<>();
            for (JsonElement element : array) {
                if (element != null && element.isJsonObject()) {
                    MacroDefinition macro = readMacro(element.getAsJsonObject());
                    if (macro != null) {
                        macros.add(macro);
                    }
                }
            }
            macros.sort(Comparator.comparing(MacroDefinition::name, String.CASE_INSENSITIVE_ORDER));
            return List.copyOf(macros);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static MacroDefinition readMacro(JsonObject object) {
        try {
            String id = string(object, "id", "");
            String name = string(object, "name", "New Macro");
            boolean enabled = bool(object, "enabled", true);
            MacroTriggerType triggerType = enumValue(
                    MacroTriggerType.class,
                    string(object, "triggerType", "NONE"),
                    MacroTriggerType.NONE
            );
            int triggerCode = integer(object, "triggerCode", -1);
            List<MacroAction> actions = new ArrayList<>();
            JsonArray actionArray = object.getAsJsonArray("actions");
            if (actionArray != null) {
                for (JsonElement actionElement : actionArray) {
                    if (actionElement == null || !actionElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject action = actionElement.getAsJsonObject();
                    actions.add(new MacroAction(
                            enumValue(MacroActionType.class, string(action, "type", "WAIT"), MacroActionType.WAIT),
                            string(action, "text", ""),
                            integer(action, "code", -1),
                            integer(action, "durationTicks", 1),
                            decimal(action, "x", 0.0D),
                            decimal(action, "y", 0.0D)
                    ));
                }
            }
            return new MacroDefinition(id, name, enabled, triggerType, triggerCode, actions);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void write(List<MacroDefinition> macros) {
        try {
            Files.createDirectories(STORAGE_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("schemaVersion", 1);
            JsonArray array = new JsonArray();
            for (MacroDefinition macro : macros) {
                JsonObject object = new JsonObject();
                object.addProperty("id", macro.id());
                object.addProperty("name", macro.name());
                object.addProperty("enabled", macro.enabled());
                object.addProperty("triggerType", macro.triggerType().name());
                object.addProperty("triggerCode", macro.triggerCode());
                JsonArray actions = new JsonArray();
                for (MacroAction action : macro.actions()) {
                    JsonObject actionObject = new JsonObject();
                    actionObject.addProperty("type", action.type().name());
                    actionObject.addProperty("text", action.text());
                    actionObject.addProperty("code", action.code());
                    actionObject.addProperty("durationTicks", action.durationTicks());
                    actionObject.addProperty("x", action.x());
                    actionObject.addProperty("y", action.y());
                    actions.add(actionObject);
                }
                object.add("actions", actions);
                array.add(object);
            }
            root.add("macros", array);
            Path temporary = STORAGE_PATH.resolveSibling(STORAGE_PATH.getFileName() + ".tmp");
            Files.writeString(
                    temporary,
                    GSON.toJson(root),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            try {
                Files.move(temporary, STORAGE_PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, STORAGE_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
            cached = List.copyOf(macros);
            cachedModified = modifiedTime();
            lastModifiedCheckMillis = System.currentTimeMillis();
        } catch (Exception ignored) {
        }
    }

    private static void ensureStorage() {
        if (Files.isRegularFile(STORAGE_PATH)) {
            return;
        }
        write(List.of());
    }

    private static long modifiedTime() {
        try {
            return Files.getLastModifiedTime(STORAGE_PATH).toMillis();
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static String string(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            return object.has(key) ? object.get(key).getAsBoolean() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double decimal(JsonObject object, String key, double fallback) {
        try {
            return object.has(key) ? object.get(key).getAsDouble() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
