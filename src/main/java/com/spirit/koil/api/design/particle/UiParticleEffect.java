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
 * {@link UiParticleRegistry#register(UiParticleEffect)}. Built-in Koil
 * effects use this exact same contract.</p>
 */
public interface UiParticleEffect {
    String id();

    /** Primary vanilla-style accent used for the protected button aura. */
    int themeColor();

    /** Relative selection weight when an engine picks a random effect. */
    default int weight() {
        return 1;
    }

    /** Optional broad visual family used to avoid repetitive random hover picks. */
    default String selectionFamily() {
        return "";
    }

    /** Optional Minecraft-native sound played once when the effect is selected. */
    default UiParticleSoundProfile.Cue beginSound() {
        return null;
    }

    default void onBegin(UiParticleEngine.EffectContext context) {
    }

    default void onTick(UiParticleEngine.EffectContext context, float deltaSeconds) {
    }

    static Builder builder(String id) {
        return new Builder(id);
    }

    final class Builder {
        private final String id;
        private int themeColor = 0xFFFFFF;
        private int weight = 1;
        private String selectionFamily = "";
        private UiParticleSoundProfile.Cue beginSound;
        private Consumer<UiParticleEngine.EffectContext> begin = context -> { };
        private BiConsumer<UiParticleEngine.EffectContext, Float> tick = (context, delta) -> { };

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

        public Builder selectionFamily(String family) {
            this.selectionFamily = family == null ? "" : family.trim().toLowerCase();
            return this;
        }

        public Builder beginSound(UiParticleSoundProfile.Cue cue) {
            this.beginSound = cue;
            return this;
        }

        public Builder onBegin(Consumer<UiParticleEngine.EffectContext> begin) {
            this.begin = Objects.requireNonNull(begin, "begin");
            return this;
        }

        public Builder onTick(BiConsumer<UiParticleEngine.EffectContext, Float> tick) {
            this.tick = Objects.requireNonNull(tick, "tick");
            return this;
        }

        public UiParticleEffect build() {
            final String effectId = this.id;
            final int effectThemeColor = this.themeColor;
            final int effectWeight = this.weight;
            final String effectSelectionFamily = this.selectionFamily;
            final UiParticleSoundProfile.Cue effectBeginSound = this.beginSound;
            final Consumer<UiParticleEngine.EffectContext> effectBegin = this.begin;
            final BiConsumer<UiParticleEngine.EffectContext, Float> effectTick = this.tick;

            return new UiParticleEffect() {
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
                public String selectionFamily() {
                    return effectSelectionFamily;
                }

                @Override
                public UiParticleSoundProfile.Cue beginSound() {
                    return effectBeginSound;
                }

                @Override
                public void onBegin(UiParticleEngine.EffectContext context) {
                    effectBegin.accept(context);
                }

                @Override
                public void onTick(UiParticleEngine.EffectContext context, float deltaSeconds) {
                    effectTick.accept(context, deltaSeconds);
                }
            };
        }
    }
}
