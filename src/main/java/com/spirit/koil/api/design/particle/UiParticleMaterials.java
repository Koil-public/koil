package com.spirit.koil.api.design.particle;

import static com.spirit.koil.api.design.particle.UiParticleEngine.*;

/**
 * Material presets describe how particles feel, independent of which sprite is
 * drawn. Effects may combine any visual family with any behavior/material.
 */
public final class UiParticleMaterials {
    private UiParticleMaterials() { }

    public enum Material {
        DEFAULT,
        SMOKE,
        BLOCK,
        SLIME,
        HONEY,
        SOUL_FIRE,
        REDSTONE,
        ENCHANT,
        WATER,
        SNOW,
        SAND,
        GOO,
        FIREWORK,
        PORTAL,
        AMETHYST
    }

    public static void apply(Material material, UiParticleEngine.ParticleBuilder builder) {
        if (material == null || builder == null) return;
        switch (material) {
            case DEFAULT -> { }
            case SMOKE -> builder.behavior(Behavior.RISING_SMOKE).gravity(-7.0F).drag(0.955F)
                    .sizeBand(SizeBand.LARGE).rotationPolicy(RotationPolicy.LOCKED)
                    .alpha(0.82F).fades(0.10F, 0.42F).collideButtons(false)
                    .solidity(0.0F).mass(0.05F).buoyancy(1.0F).contactMode(ParticleContactMode.IGNORE);
            case BLOCK -> builder.behavior(Behavior.RICOCHET).gravity(220.0F).drag(0.995F)
                    .restitution(0.66F).surfaceFriction(0.90F).sizeBand(SizeBand.LARGE)
                    .rotationPolicy(RotationPolicy.FREE).collideButtons(true).collideScreen(true)
                    .particleCollision(true).contactMode(ParticleContactMode.AUTO)
                    .solidity(1.0F).mass(2.4F).conductivity(0.15F).tag("solid").tag("block");
            case SLIME -> builder.behavior(Behavior.WOBBLE_SLIME).gravity(185.0F).drag(0.994F)
                    .restitution(0.91F).surfaceFriction(0.96F).sizeBand(SizeBand.LARGE)
                    .rotationPolicy(RotationPolicy.QUARTER_TURN).collideButtons(true).collideScreen(true)
                    .particleCollision(true).contactMode(ParticleContactMode.AUTO)
                    .solidity(0.88F).mass(1.15F).tag("solid").tag("slime");
            case HONEY -> builder.behavior(Behavior.STICKY_HONEY).gravity(72.0F).drag(0.965F)
                    .restitution(0.12F).surfaceFriction(0.38F).sizeBand(SizeBand.NORMAL)
                    .stickToButtons(0.36F).stretchWithVelocity(true).collideButtons(true)
                    .particleCollision(true).contactMode(ParticleContactMode.AUTO)
                    .solidity(0.32F).mass(0.72F).tag("sticky").tag("liquid");
            case SOUL_FIRE -> builder.behavior(Behavior.RISE_AND_WANDER).gravity(-20.0F).drag(0.965F)
                    .lift(8.0F).force(9.0F).frequency(7.0F).sizeBand(SizeBand.NORMAL)
                    .rotationPolicy(RotationPolicy.LOCKED).collideButtons(false);
            case REDSTONE -> builder.behavior(Behavior.DRIFT).gravity(8.0F).drag(0.948F)
                    .sizeBand(SizeBand.SMALL).rotationPolicy(RotationPolicy.LOCKED)
                    .alpha(0.98F).collideButtons(true);
            case ENCHANT -> builder.behavior(Behavior.SPIRAL).gravity(-5.0F).drag(0.972F)
                    .sizeBand(SizeBand.NORMAL).rotationPolicy(RotationPolicy.LOCKED)
                    .alpha(0.98F).collideButtons(false);
            case WATER -> builder.behavior(Behavior.SPLASHING_LIQUID).gravity(155.0F).drag(0.986F)
                    .restitution(0.22F).sizeBand(SizeBand.NORMAL).collideButtons(true).collideScreen(true)
                    .particleCollision(true).contactMode(ParticleContactMode.KILL_BOTH)
                    .solidity(0.08F).mass(0.38F).tag("liquid");
            case SNOW -> builder.behavior(Behavior.FLUTTER).gravity(24.0F).drag(0.968F)
                    .sizeBand(SizeBand.SMALL).settleOnSurfaces(true).collideButtons(true).collideScreen(true);
            case SAND -> builder.behavior(Behavior.BALLISTIC).gravity(210.0F).drag(0.992F)
                    .restitution(0.16F).surfaceFriction(0.65F).sizeBand(SizeBand.SMALL)
                    .settleOnSurfaces(true).collideButtons(true).collideScreen(true)
                    .particleCollision(true).contactMode(ParticleContactMode.AUTO)
                    .solidity(0.62F).mass(0.55F).tag("granular");
            case GOO -> builder.behavior(Behavior.WOBBLE_SLIME).gravity(105.0F).drag(0.978F)
                    .restitution(0.34F).sizeBand(SizeBand.NORMAL).particleCollision(true)
                    .mergeGroup("goo", true).collideButtons(true).collideScreen(true)
                    .solidity(0.45F).mass(0.8F).tag("goo").tag("mergeable");
            case FIREWORK -> builder.behavior(Behavior.BURST_SHELL).gravity(58.0F).drag(0.982F)
                    .sizeBand(SizeBand.SMALL).rotationPolicy(RotationPolicy.FACE_VELOCITY)
                    .collideButtons(true).collideScreen(true).particleCollision(false)
                    .solidity(0.02F).mass(0.08F).contactMode(ParticleContactMode.KILL_BOTH).tag("spark");
            case PORTAL -> builder.behavior(Behavior.DRIFT).gravity(-3.0F).drag(0.972F)
                    .sizeBand(SizeBand.NORMAL).rotationPolicy(RotationPolicy.LOCKED).collideButtons(false);
            case AMETHYST -> builder.behavior(Behavior.RICOCHET).gravity(205.0F).drag(0.994F)
                    .restitution(0.62F).sizeBand(SizeBand.NORMAL).rotationPolicy(RotationPolicy.FREE)
                    .collideButtons(true).collideScreen(true).particleCollision(true)
                    .contactMode(ParticleContactMode.AUTO).solidity(0.94F).mass(1.65F).tag("solid").tag("crystal");
        }
    }
}
