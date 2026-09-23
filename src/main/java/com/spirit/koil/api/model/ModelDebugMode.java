package com.spirit.koil.api.model;

import com.google.gson.JsonElement;
import com.spirit.koil.api.util.file.json.JSONFileEditor;

/**
 * Small fail-closed view of Koil's existing {@code koil/sys/config.json}
 * debug switch for model presentation code.
 *
 * <p>The value is sampled with a short cache so render-time callers can query
 * it freely without reparsing the config every frame. Runtime telemetry is
 * still recorded regardless of this flag; the flag controls user-facing debug
 * presentation only.</p>
 */
public final class ModelDebugMode {
    private static final String CONFIG_PATH = "./koil/sys/config.json";
    private static final long CACHE_NANOS = 250_000_000L;

    private static volatile long lastReadNanos;
    private static volatile boolean enabled;

    private ModelDebugMode() {
    }

    public static boolean enabled() {
        long now = System.nanoTime();
        if (lastReadNanos != 0L && now - lastReadNanos <= CACHE_NANOS) {
            return enabled;
        }
        synchronized (ModelDebugMode.class) {
            now = System.nanoTime();
            if (lastReadNanos != 0L && now - lastReadNanos <= CACHE_NANOS) {
                return enabled;
            }
            boolean resolved = false;
            try {
                JsonElement value = JSONFileEditor.getValueFromJson(CONFIG_PATH, "debug");
                resolved = value != null && value.isJsonPrimitive() && value.getAsBoolean();
            } catch (RuntimeException ignored) {
                // Presentation visibility fails closed. Telemetry itself is not discarded.
            }
            enabled = resolved;
            lastReadNanos = now;
            return resolved;
        }
    }

    /** Forces the next query to re-read config.json. */
    public static void invalidate() {
        lastReadNanos = 0L;
    }
}
