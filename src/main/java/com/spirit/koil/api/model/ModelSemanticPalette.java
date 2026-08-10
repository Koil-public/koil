package com.spirit.koil.api.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Configuration-backed semantic colors shared by every activity surface. */
public final class ModelSemanticPalette {
    private static final Path CONFIG = Path.of("koil", "sys", "config.json");
    private static final EnumMap<ModelActivityState, Integer> DEFAULTS = defaults();
    private static volatile Map<ModelActivityState, Integer> configured = Map.copyOf(DEFAULTS);

    static {
        reload();
    }

    private ModelSemanticPalette() {
    }

    public static int color(ModelActivityState state) {
        ModelActivityState safe = state == null ? ModelActivityState.IDLE : state;
        return 0xFF000000 | configured.getOrDefault(safe, DEFAULTS.get(ModelActivityState.IDLE));
    }

    public static int color(String legacyState) {
        return color(ModelActivityState.fromLegacy(legacyState));
    }

    public static String section(ModelActivityState state) {
        return String.format("§#%06X", color(state) & 0x00FFFFFF);
    }

    public static String section(String legacyState) {
        return section(ModelActivityState.fromLegacy(legacyState));
    }

    /**
     * Reads optional `semanticStatusColors` hex entries from Koil's local
     * config. Invalid or absent values preserve the stable defaults.
     */
    public static synchronized void reload() {
        EnumMap<ModelActivityState, Integer> next = new EnumMap<>(DEFAULTS);
        if (Files.isRegularFile(CONFIG)) {
            try (Reader reader = Files.newBufferedReader(CONFIG)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root.isJsonObject() && root.getAsJsonObject().has("semanticStatusColors")) {
                    JsonObject colors = root.getAsJsonObject().getAsJsonObject("semanticStatusColors");
                    for (Map.Entry<String, JsonElement> entry : colors.entrySet()) {
                        ModelActivityState state = ModelActivityState.fromLegacy(entry.getKey());
                        if (state == ModelActivityState.THINKING
                                && !entry.getKey().strip().equalsIgnoreCase("thinking")) {
                            continue;
                        }
                        Integer parsed = parseColor(entry.getValue());
                        if (parsed != null) next.put(state, parsed);
                    }
                }
            } catch (Exception ignored) {
                // A malformed optional palette must never prevent startup.
            }
        }
        configured = Map.copyOf(next);
    }

    static synchronized void configureForProof(Map<ModelActivityState, Integer> overrides) {
        EnumMap<ModelActivityState, Integer> next = new EnumMap<>(DEFAULTS);
        if (overrides != null) {
            overrides.forEach((state, color) -> {
                if (state != null && color != null) next.put(state, color & 0x00FFFFFF);
            });
        }
        configured = Map.copyOf(next);
    }

    private static Integer parseColor(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return null;
        try {
            if (value.getAsJsonPrimitive().isNumber()) return value.getAsInt() & 0x00FFFFFF;
            String text = value.getAsString().strip().toLowerCase(Locale.ROOT);
            if (text.startsWith("#")) text = text.substring(1);
            if (text.startsWith("0x")) text = text.substring(2);
            return text.matches("[0-9a-f]{6}") ? Integer.parseInt(text, 16) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static EnumMap<ModelActivityState, Integer> defaults() {
        EnumMap<ModelActivityState, Integer> colors = new EnumMap<>(ModelActivityState.class);
        colors.put(ModelActivityState.STARTING, 0x77879C);
        colors.put(ModelActivityState.PREPARING, 0x6F8EBA);
        colors.put(ModelActivityState.THINKING, 0xFFD75A);
        colors.put(ModelActivityState.RESOLVING, 0x79B4D2);
        colors.put(ModelActivityState.DISCOVERING, 0x58B6C9);
        colors.put(ModelActivityState.INSPECTING, 0x52C7D6);
        colors.put(ModelActivityState.SEARCHING, 0x5F9EFF);
        colors.put(ModelActivityState.READING, 0x74B8E8);
        colors.put(ModelActivityState.COMPARING, 0x8F9EE8);
        colors.put(ModelActivityState.CALCULATING, 0x7CC6C2);
        colors.put(ModelActivityState.PLANNING, 0x6574D9);
        colors.put(ModelActivityState.AWAITING_APPROVAL, 0xF2B84B);
        colors.put(ModelActivityState.EXECUTING, 0xF08A45);
        colors.put(ModelActivityState.NAVIGATING, 0x4FA6D8);
        colors.put(ModelActivityState.ORIENTING, 0x62A2C2);
        colors.put(ModelActivityState.SPRINTING, 0x4C9FD5);
        colors.put(ModelActivityState.SWIMMING, 0x42A7C6);
        colors.put(ModelActivityState.CLIMBING, 0x80A86A);
        colors.put(ModelActivityState.PARKOUR, 0xD69A54);
        colors.put(ModelActivityState.RIDING, 0x8A83C5);
        colors.put(ModelActivityState.GLIDING, 0x69A7D8);
        colors.put(ModelActivityState.INTERACTING, 0xD7A45E);
        colors.put(ModelActivityState.USING_ITEM, 0xC9A85E);
        colors.put(ModelActivityState.EATING, 0x9FCB5C);
        colors.put(ModelActivityState.MINING, 0xC98954);
        colors.put(ModelActivityState.BUILDING, 0xD39A5A);
        colors.put(ModelActivityState.ATTACKING, 0xE06767);
        colors.put(ModelActivityState.OBSERVING, 0x63B6A8);
        colors.put(ModelActivityState.VALIDATING, 0x63C17A);
        colors.put(ModelActivityState.TESTING, 0x73C38B);
        colors.put(ModelActivityState.REPAIRING, 0xD58D62);
        colors.put(ModelActivityState.RETRYING, 0xD98B5F);
        colors.put(ModelActivityState.RECOVERING, 0xC98963);
        colors.put(ModelActivityState.REPLANNING, 0xA879E0);
        colors.put(ModelActivityState.EDITING, 0x779CDE);
        colors.put(ModelActivityState.FORMATTING, 0x8B9ED9);
        colors.put(ModelActivityState.WRITING, 0x87AEEA);
        colors.put(ModelActivityState.FINALIZING, 0x91A0B8);
        colors.put(ModelActivityState.COMPLETE, 0x67C879);
        colors.put(ModelActivityState.ALREADY_SATISFIED, 0x63C17A);
        colors.put(ModelActivityState.PARTIAL, 0xD9A052);
        colors.put(ModelActivityState.BLOCKED, 0xD48658);
        colors.put(ModelActivityState.FAILED, 0xE0525C);
        colors.put(ModelActivityState.INTERRUPTED, 0xB77B8D);
        colors.put(ModelActivityState.CANCELLED, 0xC46770);
        colors.put(ModelActivityState.IDLE, 0x8E9AA8);
        return colors;
    }
}
