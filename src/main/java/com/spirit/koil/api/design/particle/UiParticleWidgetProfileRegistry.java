package com.spirit.koil.api.design.particle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-widget profile registry. Integrations can use a stable profile id such as
 * "title.koil", "options.credits", or "model.send" without hard-coding effects.
 */
public final class UiParticleWidgetProfileRegistry {
    public record Profile(String id, String themeId, String[] effectIds, Float density, Float motion, Boolean sounds) { }
    private static final Map<String, Profile> PROFILES = new LinkedHashMap<>();

    private UiParticleWidgetProfileRegistry() { }

    public static synchronized void register(Profile profile) { PROFILES.put(normalize(profile.id()), Objects.requireNonNull(profile)); }
    public static synchronized Profile get(String id) { return PROFILES.get(normalize(id)); }

    public static void apply(UiParticleEngine engine, String id) {
        if (engine == null || id == null) return;
        Profile profile = get(id);
        if (profile == null) return;
        if (profile.themeId() != null && !profile.themeId().isBlank()) UiParticleThemeRegistry.apply(engine, profile.themeId());
        if (profile.effectIds() != null && profile.effectIds().length > 0) engine.setEffectPool(profile.effectIds());
        if (profile.density() != null) engine.setDensityScale(profile.density());
        if (profile.motion() != null) engine.setMotionScale(profile.motion());
        if (profile.sounds() != null) engine.setSoundEnabled(profile.sounds());
    }

    private static String normalize(String id) { return id == null ? "" : id.trim().toLowerCase(); }
}
