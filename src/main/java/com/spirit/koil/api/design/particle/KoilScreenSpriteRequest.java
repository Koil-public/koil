package com.spirit.koil.api.design.particle;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded, transport-safe request for a screen-space particle effect. */
public record KoilScreenSpriteRequest(String id, int x, int y, int count, float scale, String overrides) {
    public static final int CURSOR = -1;
    public static final int MAX_COORDINATE = 16384;
    public static final int MAX_COUNT = 128;
    public static final float MIN_SCALE = 0.05F;
    public static final float MAX_SCALE = 16.0F;
    public static final int MAX_OVERRIDE_LENGTH = 4096;

    public KoilScreenSpriteRequest {
        id = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
        x = x == CURSOR ? CURSOR : clamp(x, 0, MAX_COORDINATE);
        y = y == CURSOR ? CURSOR : clamp(y, 0, MAX_COORDINATE);
        if (x == CURSOR || y == CURSOR) {
            x = CURSOR;
            y = CURSOR;
        }
        count = clamp(count, 1, MAX_COUNT);
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        overrides = overrides == null ? "" : overrides.strip();
        if (overrides.length() > MAX_OVERRIDE_LENGTH) overrides = overrides.substring(0, MAX_OVERRIDE_LENGTH);
    }

    public static KoilScreenSpriteRequest cursor(String id, int count, float scale) {
        return cursor(id, count, scale, "");
    }

    public static KoilScreenSpriteRequest cursor(String id, int count, float scale, String overrides) {
        return new KoilScreenSpriteRequest(id, CURSOR, CURSOR, count, scale, overrides);
    }

    public static KoilScreenSpriteRequest at(String id, int x, int y, int count, float scale) {
        return at(id, x, y, count, scale, "");
    }

    public static KoilScreenSpriteRequest at(String id, int x, int y, int count, float scale, String overrides) {
        return new KoilScreenSpriteRequest(id, x, y, count, scale, overrides);
    }

    public boolean cursor() { return x == CURSOR; }

    public boolean valid() {
        return !id.isBlank() && KoilUiParticleRegistry.contains(id);
    }

    /**
     * Parses `key=value;key=value`. Semicolon is the canonical separator so
     * comma-valued options such as interaction_layers=4,5,6 remain intact.
     */
    public Map<String, String> overrideMap() {
        Map<String, String> values = new LinkedHashMap<>();
        if (overrides == null || overrides.isBlank()) return values;
        for (String token : overrides.split(";")) {
            String part = token.trim();
            if (part.isEmpty()) continue;
            int equals = part.indexOf('=');
            if (equals < 1) {
                values.put(part.toLowerCase(Locale.ROOT), "true");
                continue;
            }
            String key = part.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = part.substring(equals + 1).trim();
            if (!key.isEmpty()) values.put(key, value);
        }
        return values;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
