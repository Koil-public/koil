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
public final class UiParticleRegistry {
    private static final Map<String, UiParticleEffect> EFFECTS = new LinkedHashMap<>();
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    private UiParticleRegistry() {
    }

    public static synchronized UiParticleEffect register(UiParticleEffect effect) {
        Objects.requireNonNull(effect, "effect");
        String id = normalize(effect.id());
        EFFECTS.put(id, effect);
        ALIASES.entrySet().removeIf(entry -> entry.getValue().equals(id));
        registerAlias(id, id);
        if (id.startsWith("koil.button.effects.")) {
            registerAlias(id.substring("koil.button.effects.".length()), id);
        } else {
            registerAlias("koil.button.effects." + id, id);
        }
        int dot = id.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < id.length()) {
            registerAlias(id.substring(dot + 1), id);
        }
        return effect;
    }

    public static synchronized boolean unregister(String id) {
        String resolved = resolveId(id);
        boolean removed = EFFECTS.remove(resolved) != null;
        if (removed) {
            ALIASES.entrySet().removeIf(entry -> entry.getValue().equals(resolved));
        }
        return removed;
    }

    public static synchronized UiParticleEffect get(String id) {
        return EFFECTS.get(resolveId(id));
    }

    public static synchronized boolean contains(String id) {
        return EFFECTS.containsKey(resolveId(id));
    }

    public static synchronized String canonicalId(String id) {
        String resolved = resolveId(id);
        return EFFECTS.containsKey(resolved) ? resolved : normalize(id);
    }

    public static synchronized Collection<UiParticleEffect> values() {
        return Collections.unmodifiableList(new ArrayList<>(EFFECTS.values()));
    }

    public static synchronized List<String> ids() {
        return Collections.unmodifiableList(new ArrayList<>(EFFECTS.keySet()));
    }

    static synchronized UiParticleEffect random(Random random, UiParticleEffect excluded, List<String> allowedIds) {
        return randomDiverse(random, excluded, allowedIds, List.of(), List.of());
    }

    /**
     * Picks a hover effect while strongly suppressing both exact repeats and
     * recently seen visual families. The selection relaxes in stages when a
     * caller supplies a very small pool, so custom screens never dead-end.
     */
    static synchronized UiParticleEffect randomDiverse(
            Random random,
            UiParticleEffect excluded,
            List<String> allowedIds,
            List<String> recentIds,
            List<String> recentFamilies
    ) {
        List<UiParticleEffect> base = eligibleEffects(excluded, allowedIds);
        if (base.isEmpty()) {
            if (excluded != null && (allowedIds == null || allowedIds.isEmpty()
                    || allowedIds.contains(normalize(excluded.id())))) {
                return excluded;
            }
            return null;
        }

        List<String> normalizedRecentIds = new ArrayList<>();
        if (recentIds != null) {
            for (String id : recentIds) {
                if (id != null && !id.isBlank()) normalizedRecentIds.add(normalize(id));
            }
        }
        List<String> normalizedRecentFamilies = new ArrayList<>();
        if (recentFamilies != null) {
            for (String family : recentFamilies) {
                if (family != null && !family.isBlank()) normalizedRecentFamilies.add(family.trim().toLowerCase());
            }
        }

        // Best case: no exact repeat and no family repeat.
        List<UiParticleEffect> strict = new ArrayList<>();
        for (UiParticleEffect effect : base) {
            String id = normalize(effect.id());
            if (normalizedRecentIds.contains(id)) continue;
            if (normalizedRecentFamilies.contains(familyKey(effect))) continue;
            strict.add(effect);
        }
        if (!strict.isEmpty()) return weightedPick(random, strict);

        // Small custom pools may not have enough families. Keep exact-repeat
        // protection first, then relax family protection.
        List<UiParticleEffect> noExactRepeat = new ArrayList<>();
        for (UiParticleEffect effect : base) {
            if (!normalizedRecentIds.contains(normalize(effect.id()))) noExactRepeat.add(effect);
        }
        if (!noExactRepeat.isEmpty()) return weightedPick(random, noExactRepeat);

        return weightedPick(random, base);
    }

    private static List<UiParticleEffect> eligibleEffects(UiParticleEffect excluded, List<String> allowedIds) {
        List<UiParticleEffect> pool = new ArrayList<>();
        for (UiParticleEffect effect : EFFECTS.values()) {
            if (effect == excluded) continue;
            String normalizedId = normalize(effect.id());
            if (allowedIds == null || allowedIds.isEmpty()) {
                // Registry mirrors are capability/test sprites, not authored hover
                // effects. They only participate in random selection when a screen
                // explicitly places them in its effect pool.
                if (GameParticleRegistryBridge.containsId(normalizedId)
                        || GameSpriteRegistryBridge.containsId(normalizedId)
                        || normalizedId.contains(":")) {
                    continue;
                }
            }
            if (allowedIds != null && !allowedIds.isEmpty() && !allowedIds.contains(normalizedId)) continue;
            pool.add(effect);
        }
        return pool;
    }

    private static UiParticleEffect weightedPick(Random random, List<UiParticleEffect> pool) {
        int totalWeight = 0;
        for (UiParticleEffect effect : pool) totalWeight += Math.max(1, effect.weight());
        int pick = random.nextInt(Math.max(1, totalWeight));
        for (UiParticleEffect effect : pool) {
            pick -= Math.max(1, effect.weight());
            if (pick < 0) return effect;
        }
        return pool.get(pool.size() - 1);
    }

    /**
     * Broad visual family used only for repeat suppression. This deliberately
     * groups effects by what the user perceives rather than by JSON file.
     */
    static String familyKey(UiParticleEffect effect) {
        if (effect == null) return "";
        String authored = effect.selectionFamily();
        if (authored != null && !authored.isBlank()) return authored.trim().toLowerCase();
        return familyKey(effect.id());
    }

    static String familyKey(String rawId) {
        String id = normalize(rawId);
        if (id.startsWith("koil.button.effects.")) id = id.substring("koil.button.effects.".length());

        if (containsAny(id, "firework", "rocket", "sparkler")) return "firework";
        if (containsAny(id, "registry_block", "block_party", "block_jubilee")) return "block_party";
        if (containsAny(id, "registry_item", "item_", "loot", "treasure", "satchel", "toolbox")) return "item_party";
        if (containsAny(id, "piston", "lever", "observer", "repeater", "comparator", "redstone", "rail", "minecart", "hopper", "dispenser", "dropper")) return "redstone_mechanical";
        if (containsAny(id, "sculk", "warden", "shriek", "deep_dark", "sonic")) return "deep_dark";
        if (containsAny(id, "ender", "end_", "chorus", "portal", "shulker", "void")) return "end_void";
        if (containsAny(id, "nether", "lava", "magma", "blaze", "ghast", "piglin", "soul_", "basalt", "crimson", "warped")) return "nether_fire";
        if (containsAny(id, "water", "ocean", "bubble", "splash", "river", "fish", "guardian", "dolphin", "squid", "axolotl", "conduit")) return "aquatic";
        if (containsAny(id, "leaf", "cherry", "petal", "flower", "bloom", "moss", "azalea", "vine", "bamboo", "grass", "pollen", "bee")) return "foliage";
        if (containsAny(id, "slime", "honey", "sticky", "goo", "jelly")) return "elastic_sticky";
        if (containsAny(id, "enchant", "glyph", "potion", "brewing", "xp", "experience", "totem", "beacon", "amethyst")) return "magic_glow";
        if (containsAny(id, "snow", "ice", "frozen", "powder")) return "snow_ice";
        if (containsAny(id, "sand", "gravel", "stone", "ore", "deepslate", "dripstone", "geode", "obsidian", "bedrock", "dirt", "mud", "clay")) return "terrain";
        if (containsAny(id, "note", "music", "disc", "jukebox", "horn", "bell")) return "music_sound";
        if (containsAny(id, "sword", "bow", "crossbow", "trident", "shield", "arrow", "combat", "crit", "sweep")) return "combat";
        if (containsAny(id, "villager", "golem", "raid", "emerald", "trader")) return "village";
        if (containsAny(id, "rain", "thunder", "lightning", "weather")) return "weather";
        if (containsAny(id, "contraption", "clockwork", "machine", "pogo", "jack_in_box", "carousel")) return "wacky_contraption";

        int split = id.indexOf('_');
        return split > 0 ? id.substring(0, split) : id;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static void registerAlias(String alias, String canonicalId) {
        String normalizedAlias = normalize(alias);
        if (normalizedAlias.isBlank()) return;
        ALIASES.putIfAbsent(normalizedAlias, canonicalId);
    }

    private static String resolveId(String id) {
        String normalized = normalize(id);
        String aliased = ALIASES.get(normalized);
        if (aliased != null) return aliased;
        if (normalized.startsWith("koil.button.effects.")) {
            String stripped = normalized.substring("koil.button.effects.".length());
            aliased = ALIASES.get(stripped);
            if (aliased != null) return aliased;
        } else {
            aliased = ALIASES.get("koil.button.effects." + normalized);
            if (aliased != null) return aliased;
        }
        return normalized;
    }

    private static String normalize(String id) {
        return Objects.requireNonNull(id, "id").trim().toLowerCase();
    }
}
