package com.spirit.koil.api.design.particle;

import net.minecraft.client.gui.screen.Screen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Custom UI collision bridge for Koil controls that are drawn manually instead
 * of being represented by ButtonWidget instances.
 */
public final class UiColliderRegistry {
    public record Bounds(float x, float y, float width, float height) { }

    private static final Map<String, Function<Screen, List<Bounds>>> PROVIDERS = new LinkedHashMap<>();

    private UiColliderRegistry() { }

    public static synchronized void register(String id, Function<Screen, List<Bounds>> provider) {
        PROVIDERS.put(normalize(id), Objects.requireNonNull(provider, "provider"));
    }

    public static synchronized void unregister(String id) {
        PROVIDERS.remove(normalize(id));
    }

    public static synchronized List<Bounds> collect(Screen screen) {
        if (screen == null || PROVIDERS.isEmpty()) return Collections.emptyList();
        List<Bounds> out = new ArrayList<>();
        for (Function<Screen, List<Bounds>> provider : PROVIDERS.values()) {
            try {
                List<Bounds> bounds = provider.apply(screen);
                if (bounds != null) out.addAll(bounds);
            } catch (RuntimeException ignored) {
                // A broken addon collider should not break UI rendering.
            }
        }
        return out;
    }

    private static String normalize(String id) {
        return Objects.requireNonNull(id, "id").trim().toLowerCase();
    }
}
