package com.spirit.koil.api.design.particle;

import net.minecraft.util.Identifier;

/**
 * Minecraft Java 1.20.1 particle sprite catalog used by Koil's UI engine.
 *
 * <p>Every identifier points into the normal Minecraft resource namespace, so
 * active resource packs remain authoritative. The metadata here is UI-specific:
 * readable pixel size, animation policy, tint policy, and rotation policy.</p>
 */
public final class KoilVanillaParticleSprites {
    private KoilVanillaParticleSprites() { }

    public enum AnimationMode {
        AGE,
        LOOP,
        STATIC,
        RANDOM_STATIC
    }

    public enum SpriteSet {
        ANGRY(false, 9, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "angry"),
        GENERIC(true, 6, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.QUARTER_TURN, frames("generic_", 0, 7)),
        DUST(true, 5, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.LOCKED, framesReverse("generic_", 7, 0)),
        PORTAL(true, 7, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.LOCKED, frames("generic_", 0, 7)),
        SPELL(true, 8, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.LOCKED, frames("spell_", 0, 7)),
        EFFECT(true, 8, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.LOCKED, frames("effect_", 0, 7)),
        GLITTER(true, 6, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.FACE_VELOCITY, framesReverse("glitter_", 7, 0)),
        FIREWORK(true, 6, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.FACE_VELOCITY, framesReverse("spark_", 7, 0)),
        BIG_SMOKE(true, 11, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.LOCKED, frames("big_smoke_", 0, 11)),
        EXPLOSION(false, 16, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.LOCKED, frames("explosion_", 0, 15)),
        BUBBLE(true, 8, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "bubble"),
        BUBBLE_POP(true, 9, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.LOCKED, frames("bubble_pop_", 0, 4)),
        CHERRY(false, 8, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.FREE, frames("cherry_", 0, 11)),
        CRIT(true, 7, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "critical_hit"),
        DAMAGE(true, 7, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "damage"),
        DRIP_HANG(true, 7, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "drip_hang"),
        DRIP_FALL(true, 7, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "drip_fall"),
        DRIP_LAND(true, 8, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "drip_land"),
        ENCHANTED_HIT(true, 8, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "enchanted_hit"),
        FLAME(false, 8, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "flame"),
        FLASH(false, 15, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "flash"),
        GLOW(true, 8, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "glow"),
        HEART(false, 10, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "heart"),
        LAVA(false, 9, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "lava"),
        NAUTILUS(true, 8, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "nautilus"),
        NOTE(true, 10, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "note"),
        SCULK_CHARGE(false, 9, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.LOCKED, frames("sculk_charge_", 0, 6)),
        SCULK_CHARGE_POP(false, 9, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.LOCKED, frames("sculk_charge_pop_", 0, 3)),
        SCULK_SOUL(false, 9, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.LOCKED, frames("sculk_soul_", 0, 10)),
        SGA(true, 9, AnimationMode.RANDOM_STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, alphabetFrames()),
        SHRIEK(false, 12, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "shriek"),
        SONIC_BOOM(false, 15, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.LOCKED, frames("sonic_boom_", 0, 15)),
        SOUL(false, 9, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.LOCKED, frames("soul_", 0, 10)),
        SOUL_FIRE_FLAME(false, 8, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "soul_fire_flame"),
        SPLASH(true, 9, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.LOCKED, frames("splash_", 0, 3)),
        SWEEP(false, 15, AnimationMode.AGE, KoilUiParticleEngine.RotationPolicy.LOCKED, frames("sweep_", 0, 7)),
        VIBRATION(false, 11, AnimationMode.STATIC, KoilUiParticleEngine.RotationPolicy.LOCKED, "vibration");

        private final boolean tintable;
        private final int recommendedPixels;
        private final AnimationMode animationMode;
        private final KoilUiParticleEngine.RotationPolicy rotationPolicy;
        private final String[] frames;

        SpriteSet(boolean tintable, int recommendedPixels, AnimationMode animationMode,
                  KoilUiParticleEngine.RotationPolicy rotationPolicy, String... frames) {
            this.tintable = tintable;
            this.recommendedPixels = Math.max(2, recommendedPixels);
            this.animationMode = animationMode;
            this.rotationPolicy = rotationPolicy;
            this.frames = frames;
        }

        public boolean tintable() { return tintable; }
        public int recommendedPixels() { return recommendedPixels; }
        public AnimationMode animationMode() { return animationMode; }
        public KoilUiParticleEngine.RotationPolicy rotationPolicy() { return rotationPolicy; }
        public int frameCount() { return frames.length; }

        public Identifier texture(float normalizedAge, int variant, float ageSeconds) {
            if (frames.length == 0) return new Identifier("minecraft", "textures/particle/generic_0.png");
            int index;
            switch (animationMode) {
                case STATIC -> index = 0;
                case RANDOM_STATIC -> index = Math.floorMod(variant, frames.length);
                case LOOP -> index = Math.floorMod((int) (ageSeconds * 12.0F) + variant, frames.length);
                case AGE -> {
                    float age = Math.max(0.0F, Math.min(0.999999F, normalizedAge));
                    index = Math.min(frames.length - 1, (int) (age * frames.length));
                }
                default -> index = 0;
            }
            return new Identifier("minecraft", "textures/particle/" + frames[index] + ".png");
        }

        /** Backwards-compatible overload. */
        public Identifier texture(float normalizedAge, int variant) {
            return texture(normalizedAge, variant, normalizedAge);
        }
    }

    public static SpriteSet forVisualFamily(KoilUiParticleEngine.VisualFamily family) {
        if (family == null || family == KoilUiParticleEngine.VisualFamily.AUTO) return null;
        return switch (family) {
            case CRIT -> SpriteSet.CRIT;
            case ENCHANT_GLYPH -> SpriteSet.SGA;
            case SMOKE -> SpriteSet.BIG_SMOKE;
            case FIREWORK_SPARK -> SpriteSet.FIREWORK;
            case HEART -> SpriteSet.HEART;
            case NOTE -> SpriteSet.NOTE;
            case BUBBLE -> SpriteSet.BUBBLE;
            case CHERRY_PETAL -> SpriteSet.CHERRY;
            case SCULK_CHARGE -> SpriteSet.SCULK_CHARGE;
            case SLIME_SHARD, BLOCK_SHARD, EXPERIENCE -> SpriteSet.GENERIC;
            case SOUL_FLAME -> SpriteSet.SOUL_FIRE_FLAME;
            case PORTAL_MOTE -> SpriteSet.PORTAL;
            case TOTEM, END_ROD -> SpriteSet.GLITTER;
            case LAVA -> SpriteSet.LAVA;
            case HONEY -> SpriteSet.DRIP_FALL;
            case NAUTILUS -> SpriteSet.NAUTILUS;
            case SPLASH -> SpriteSet.SPLASH;
            case REDSTONE_DUST -> SpriteSet.DUST;
            case FLAME -> SpriteSet.FLAME;
            case EXPLOSION -> SpriteSet.EXPLOSION;
            case SPELL -> SpriteSet.SPELL;
            case AUTO -> null;
        };
    }

    public static SpriteSet forShape(KoilUiParticleEngine.Shape shape) {
        if (shape == null) return SpriteSet.GENERIC;
        return switch (shape) {
            case PIXEL, DUST, REDSTONE -> SpriteSet.DUST;
            case BLOCK_SHARD, SNOW, EXPERIENCE, SLIME -> SpriteSet.GENERIC;
            case SPARK, STAR -> SpriteSet.FIREWORK;
            case CRIT -> SpriteSet.CRIT;
            case GLYPH, ENCHANT -> SpriteSet.SGA;
            case BUBBLE -> SpriteSet.BUBBLE;
            case DROP -> SpriteSet.DRIP_FALL;
            case SMOKE -> SpriteSet.BIG_SMOKE;
            case CLOUD, RIBBON -> SpriteSet.SPELL;
            case FLAME -> SpriteSet.FLAME;
            case SOUL_FLAME -> SpriteSet.SOUL_FIRE_FLAME;
            case NOTE -> SpriteSet.NOTE;
            case HEART -> SpriteSet.HEART;
            case PETAL, LEAF -> SpriteSet.CHERRY;
            case END_ROD, STREAK -> SpriteSet.GLITTER;
            case SCULK -> SpriteSet.SCULK_CHARGE;
            case TOTEM -> SpriteSet.GLITTER;
        };
    }

    private static String[] frames(String prefix, int from, int to) {
        int length = Math.abs(to - from) + 1;
        String[] result = new String[length];
        int step = from <= to ? 1 : -1;
        int value = from;
        for (int i = 0; i < length; i++, value += step) result[i] = prefix + value;
        return result;
    }

    private static String[] framesReverse(String prefix, int from, int to) { return frames(prefix, from, to); }

    private static String[] alphabetFrames() {
        String[] result = new String[26];
        for (int i = 0; i < 26; i++) result[i] = "sga_" + (char) ('a' + i);
        return result;
    }
}
