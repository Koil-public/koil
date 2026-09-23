package com.spirit.koil.api.design.particle;

import net.minecraft.block.Block;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvent;

/**
 * Event-driven sound profile for Koil UI particles.
 *
 * <p>Profiles deliberately reference Minecraft SoundEvent instances so resource
 * packs can replace the underlying sound assets. Block profiles are derived
 * from the block's own {@link BlockSoundGroup}, which keeps bounce/break audio
 * correct for stone, wood, slime, amethyst, and custom blocks.</p>
 */
public final class UiParticleSoundProfile {
    public record Cue(SoundEvent sound, float volume, float pitch, float pitchJitter, long cooldownMillis) {
        public Cue {
            volume = Math.max(0.0F, volume);
            pitch = Math.max(0.05F, pitch);
            pitchJitter = Math.max(0.0F, pitchJitter);
            cooldownMillis = Math.max(0L, cooldownMillis);
        }

        public static Cue of(SoundEvent sound, float volume, float pitch) {
            return new Cue(sound, volume, pitch, 0.0F, 0L);
        }

        public Cue jitter(float jitter) {
            return new Cue(sound, volume, pitch, jitter, cooldownMillis);
        }

        public Cue cooldown(long millis) {
            return new Cue(sound, volume, pitch, pitchJitter, millis);
        }
    }

    private final Cue spawn;
    private final Cue bounce;
    private final Cue breakCue;
    private final Cue fall;
    private final Cue expire;
    private final float minimumBounceSpeed;

    private UiParticleSoundProfile(Builder builder) {
        this.spawn = builder.spawn;
        this.bounce = builder.bounce;
        this.breakCue = builder.breakCue;
        this.fall = builder.fall;
        this.expire = builder.expire;
        this.minimumBounceSpeed = Math.max(0.0F, builder.minimumBounceSpeed);
    }

    public Cue spawn() { return spawn; }
    public Cue bounce() { return bounce; }
    public Cue breakCue() { return breakCue; }
    /** Exact BlockSoundGroup fall sound, used for floor impacts when available. */
    public Cue fall() { return fall; }
    public Cue expire() { return expire; }
    public float minimumBounceSpeed() { return minimumBounceSpeed; }

    public static Builder builder() { return new Builder(); }

    public static UiParticleSoundProfile forBlock(Block block) {
        return forBlock(block, true);
    }

    public static UiParticleSoundProfile forBlock(Block block, boolean breakOnExpire) {
        if (block == null) return builder().build();
        BlockSoundGroup group = block.getDefaultState().getSoundGroup();
        float volume = Math.max(0.08F, group.getVolume());
        float pitch = Math.max(0.25F, group.getPitch());
        return builder()
                .spawn(new Cue(group.getPlaceSound(), volume * 0.62F, pitch, 0.035F, 42L))
                .bounce(new Cue(group.getHitSound(), volume * 0.42F, pitch, 0.06F, 52L))
                .fall(new Cue(group.getFallSound(), volume * 0.50F, pitch, 0.055F, 62L))
                .breakCue(new Cue(group.getBreakSound(), volume * 0.78F, pitch, 0.045F, 36L))
                .expire(breakOnExpire ? new Cue(group.getBreakSound(), volume * 0.74F, pitch, 0.045F, 36L) : null)
                .minimumBounceSpeed(18.0F)
                .build();
    }

    /** Bounce uses the block hit sound, while destruction is triggered manually. */
    public static UiParticleSoundProfile forBouncingBlock(Block block) {
        if (block == null) return builder().build();
        BlockSoundGroup group = block.getDefaultState().getSoundGroup();
        float volume = Math.max(0.08F, group.getVolume());
        float pitch = Math.max(0.25F, group.getPitch());
        return builder()
                .spawn(new Cue(group.getPlaceSound(), volume * 0.58F, pitch, 0.035F, 46L))
                .bounce(new Cue(group.getHitSound(), volume * 0.44F, pitch, 0.07F, 48L))
                .fall(new Cue(group.getFallSound(), volume * 0.52F, pitch, 0.06F, 58L))
                .minimumBounceSpeed(16.0F)
                .build();
    }

    public static final class Builder {
        private Cue spawn;
        private Cue bounce;
        private Cue breakCue;
        private Cue fall;
        private Cue expire;
        private float minimumBounceSpeed = 14.0F;

        private Builder() { }

        public Builder spawn(Cue cue) { this.spawn = cue; return this; }
        public Builder bounce(Cue cue) { this.bounce = cue; return this; }
        public Builder breakCue(Cue cue) { this.breakCue = cue; return this; }
        public Builder fall(Cue cue) { this.fall = cue; return this; }
        public Builder expire(Cue cue) { this.expire = cue; return this; }
        public Builder minimumBounceSpeed(float speed) { this.minimumBounceSpeed = speed; return this; }
        public UiParticleSoundProfile build() { return new UiParticleSoundProfile(this); }
    }
}
