package com.spirit.koil.api.design.particle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Global registry for Koil UI particle effects.
 *
 * <p>Registration is intentionally public so Koil screens, optional modules,
 * and other Fabric mods can contribute effects without modifying the particle
 * engine. Re-registering an id replaces the old definition, which also gives
 * resource packs/modpacks a controlled override point at code level.</p>
 */
public final class KoilUiParticleRegistry {
    private static final Map<String, KoilUiParticleEffect> EFFECTS = new LinkedHashMap<>();

    private KoilUiParticleRegistry() {
    }

    public static synchronized KoilUiParticleEffect register(KoilUiParticleEffect effect) {
        Objects.requireNonNull(effect, "effect");
        String id = normalize(effect.id());
        EFFECTS.put(id, effect);
        return effect;
    }

    public static synchronized boolean unregister(String id) {
        return EFFECTS.remove(normalize(id)) != null;
    }

    public static synchronized KoilUiParticleEffect get(String id) {
        return EFFECTS.get(normalize(id));
    }

    public static synchronized boolean contains(String id) {
        return EFFECTS.containsKey(normalize(id));
    }

    public static synchronized Collection<KoilUiParticleEffect> values() {
        return Collections.unmodifiableList(new ArrayList<>(EFFECTS.values()));
    }

    public static synchronized List<String> ids() {
        return Collections.unmodifiableList(new ArrayList<>(EFFECTS.keySet()));
    }

    static synchronized KoilUiParticleEffect random(Random random, KoilUiParticleEffect excluded, List<String> allowedIds) {
        List<KoilUiParticleEffect> pool = new ArrayList<>();
        int totalWeight = 0;
        for (KoilUiParticleEffect effect : EFFECTS.values()) {
            if (effect == excluded) {
                continue;
            }
            String normalizedId = normalize(effect.id());
            // Runtime mirrors of Minecraft/Fabric particle types are command/API
            // assets, not additions to Koil's curated random hover-effect pool.
            // They become eligible when a caller explicitly supplies a pool.
            if ((allowedIds == null || allowedIds.isEmpty())
                    && (normalizedId.startsWith("game.") || normalizedId.contains(":"))) {
                continue;
            }
            if (allowedIds != null && !allowedIds.isEmpty() && !allowedIds.contains(normalizedId)) {
                continue;
            }
            pool.add(effect);
            totalWeight += Math.max(1, effect.weight());
        }

        if (pool.isEmpty()) {
            if (excluded != null && (allowedIds == null || allowedIds.isEmpty() || allowedIds.contains(normalize(excluded.id())))) {
                return excluded;
            }
            return null;
        }

        int pick = random.nextInt(Math.max(1, totalWeight));
        for (KoilUiParticleEffect effect : pool) {
            pick -= Math.max(1, effect.weight());
            if (pick < 0) {
                return effect;
            }
        }
        return pool.get(pool.size() - 1);
    }

    private static String normalize(String id) {
        return Objects.requireNonNull(id, "id").trim().toLowerCase();
    }
}
