package com.spirit.koil.api.design.particle;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Public definition contract for Koil screen-space particle effects.
 *
 * <p>Effects are deliberately data-light. The engine owns simulation,
 * collisions, rendering, layering, limits, and frame timing. An effect only
 * describes what should be emitted when it starts and while it remains active.</p>
 *
 * <p>Third-party Koil/Fabric code can register effects through
 * {@link KoilUiParticleRegistry#register(KoilUiParticleEffect)}. Built-in Koil
 * effects use this exact same contract.</p>
 */
public interface KoilUiParticleEffect {
    String id();

    /** Primary vanilla-style accent used for the protected button aura. */
    int themeColor();

    /** Relative selection weight when an engine picks a random effect. */
    default int weight() {
        return 1;
    }

    /** Optional Minecraft-native sound played once when the effect is selected. */
    default KoilUiParticleSoundProfile.Cue beginSound() {
        return null;
    }

    default void onBegin(KoilUiParticleEngine.EffectContext context) {
    }

    default void onTick(KoilUiParticleEngine.EffectContext context, float deltaSeconds) {
    }

    static Builder builder(String id) {
        return new Builder(id);
    }

    final class Builder {
        private final String id;
        private int themeColor = 0xFFFFFF;
        private int weight = 1;
        private KoilUiParticleSoundProfile.Cue beginSound;
        private Consumer<KoilUiParticleEngine.EffectContext> begin = context -> { };
        private BiConsumer<KoilUiParticleEngine.EffectContext, Float> tick = (context, delta) -> { };

        private Builder(String id) {
            String normalized = Objects.requireNonNull(id, "id").trim().toLowerCase();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Particle effect id cannot be blank");
            }
            this.id = normalized;
        }

        public Builder themeColor(int rgb) {
            this.themeColor = rgb & 0x00FFFFFF;
            return this;
        }

        public Builder weight(int weight) {
            this.weight = Math.max(1, weight);
            return this;
        }

        public Builder beginSound(KoilUiParticleSoundProfile.Cue cue) {
            this.beginSound = cue;
            return this;
        }

        public Builder onBegin(Consumer<KoilUiParticleEngine.EffectContext> begin) {
            this.begin = Objects.requireNonNull(begin, "begin");
            return this;
        }

        public Builder onTick(BiConsumer<KoilUiParticleEngine.EffectContext, Float> tick) {
            this.tick = Objects.requireNonNull(tick, "tick");
            return this;
        }

        public KoilUiParticleEffect build() {
            final String effectId = this.id;
            final int effectThemeColor = this.themeColor;
            final int effectWeight = this.weight;
            final KoilUiParticleSoundProfile.Cue effectBeginSound = this.beginSound;
            final Consumer<KoilUiParticleEngine.EffectContext> effectBegin = this.begin;
            final BiConsumer<KoilUiParticleEngine.EffectContext, Float> effectTick = this.tick;

            return new KoilUiParticleEffect() {
                @Override
                public String id() {
                    return effectId;
                }

                @Override
                public int themeColor() {
                    return effectThemeColor;
                }

                @Override
                public int weight() {
                    return effectWeight;
                }

                @Override
                public KoilUiParticleSoundProfile.Cue beginSound() {
                    return effectBeginSound;
                }

                @Override
                public void onBegin(KoilUiParticleEngine.EffectContext context) {
                    effectBegin.accept(context);
                }

                @Override
                public void onTick(KoilUiParticleEngine.EffectContext context, float deltaSeconds) {
                    effectTick.accept(context, deltaSeconds);
                }
            };
        }
    }
}
