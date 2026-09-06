package com.spirit.koil.api.design.particle;

import com.spirit.koil.api.design.particle.KoilVanillaParticleSprites.SpriteSet;

/**
 * Copyable example showing the public extension path for UI particles.
 *
 * <p>This class does not auto-register itself. Call {@link #registerExample()}
 * from a client initializer only if the example should actually appear.</p>
 */
public final class KoilUiParticleAuthoringExample {
    private KoilUiParticleAuthoringExample() {
    }

    public static void registerExample() {
        KoilUiParticleRegistry.register(
                KoilUiParticleEffect.builder("example_diamond_sparkle")
                        .themeColor(0x55FFFF)
                        .onBegin(ctx -> {
                            ctx.pulse(0.0F, 0.35F, 0x55FFFF, 0.8F);
                            for (int i = 0; i < 18; i++) {
                                spawnDiamondSpark(ctx, i % 8 == 0
                                        ? KoilUiParticleEngine.Layer.FRONT
                                        : KoilUiParticleEngine.Layer.BACK);
                            }
                        })
                        .onTick((ctx, dt) -> {
                            int count = ctx.emissionCount("diamond", 4.0F, dt);
                            for (int i = 0; i < count; i++) {
                                spawnDiamondSpark(ctx, KoilUiParticleEngine.Layer.BACK);
                            }
                        })
                        .build()
        );
    }

    private static void spawnDiamondSpark(
            KoilUiParticleEngine.EffectContext ctx,
            KoilUiParticleEngine.Layer layer
    ) {
        double angle = ctx.random(0.0F, (float) (Math.PI * 2.0));
        float speed = ctx.random(40.0F, 95.0F);

        ctx.spawn(ctx.particle(KoilUiParticleEngine.Shape.CRIT)
                .sprite(SpriteSet.CRIT)
                .color(ctx.pickColor(0x55FFFF, 0xBFFFFF, 0xFFFFFF))
                .size(ctx.random(0.7F, 1.1F))
                .lifetime(ctx.random(0.65F, 1.25F))
                .layer(layer)
                .velocity((float) Math.cos(angle) * speed, (float) Math.sin(angle) * speed)
                .gravity(34.0F)
                .drag(0.972F)
                .collideButtons(true)
                .collideScreen(true)
                .restitution(0.45F)
                .surfaceFriction(0.84F));
    }
}
