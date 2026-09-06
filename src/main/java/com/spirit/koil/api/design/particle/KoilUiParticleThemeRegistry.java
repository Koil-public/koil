package com.spirit.koil.api.design.particle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Named screen-level particle themes. */
public final class KoilUiParticleThemeRegistry {
    public record Theme(String id, String[] effectIds, float density, float motion) { }
    private static final Map<String, Theme> THEMES = new LinkedHashMap<>();

    static {
        register(new Theme("vanilla", new String[0], 1.0F, 1.0F));
        register(new Theme("enchanted", new String[]{"enchantment_table","portal_motes","amethyst_crystal_dust","conduit_swirl"}, 1.0F, 0.95F));
        register(new Theme("redstone", new String[]{"redstone_dust","electric_spark","ricochet_split_sparks","tnt_bounce_fuse"}, 1.05F, 1.0F));
        register(new Theme("calm", new String[]{"snow_accumulation","cherry_petals","campfire_smoke","conduit_swirl"}, 0.72F, 0.65F));
    }

    private KoilUiParticleThemeRegistry() { }

    public static synchronized void register(Theme theme) { THEMES.put(normalize(theme.id()), Objects.requireNonNull(theme)); }
    public static synchronized Theme get(String id) { return THEMES.get(normalize(id)); }
    public static void apply(KoilUiParticleEngine engine, String id) {
        Theme theme = get(id);
        if (engine == null || theme == null) return;
        engine.setEffectPool(theme.effectIds());
        engine.setDensityScale(theme.density());
        engine.setMotionScale(theme.motion());
    }
    private static String normalize(String id) { return id == null ? "" : id.trim().toLowerCase(); }
}
