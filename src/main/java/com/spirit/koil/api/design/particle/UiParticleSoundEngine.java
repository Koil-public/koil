package com.spirit.koil.api.design.particle;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Lightweight UI-sound mixer with cooldowns so particle storms do not become audio spam. */
public final class UiParticleSoundEngine {
    private static final Random RANDOM = new Random();
    private static final Map<String, Long> LAST_PLAYED = new HashMap<>();
    private static boolean enabled = true;
    private static float volumeScale = 1.0F;
    private static long globalWindowStart;
    private static int soundsThisWindow;
    private static int maxSoundsPer100Ms = 8;

    private UiParticleSoundEngine() { }

    public static void setEnabled(boolean enabled) { UiParticleSoundEngine.enabled = enabled; }
    public static boolean isEnabled() { return enabled; }
    public static void setVolumeScale(float scale) { volumeScale = Math.max(0.0F, Math.min(1.5F, scale)); }
    public static float getVolumeScale() { return volumeScale; }
    public static void setMaxSoundsPer100Ms(int max) { maxSoundsPer100Ms = Math.max(1, Math.min(32, max)); }

    public static void play(UiParticleSoundProfile.Cue cue) {
        play(cue, 1.0F, 1.0F);
    }

    public static void play(UiParticleSoundProfile.Cue cue, float volumeMultiplier, float pitchMultiplier) {
        if (!enabled || cue == null || cue.sound() == null || volumeScale <= 0.0F) return;
        long now = System.currentTimeMillis();
        if (now - globalWindowStart >= 100L) {
            globalWindowStart = now;
            soundsThisWindow = 0;
        }
        if (soundsThisWindow >= maxSoundsPer100Ms) return;

        String key = cue.sound().getId().toString();
        long last = LAST_PLAYED.getOrDefault(key, Long.MIN_VALUE / 2);
        if (now - last < cue.cooldownMillis()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getSoundManager() == null) return;

        float jitter = cue.pitchJitter() <= 0.0F ? 0.0F : (RANDOM.nextFloat() * 2.0F - 1.0F) * cue.pitchJitter();
        float pitch = Math.max(0.05F, cue.pitch() * pitchMultiplier + jitter);
        float volume = Math.max(0.0F, cue.volume() * volumeMultiplier * volumeScale);
        if (volume <= 0.001F) return;

        client.getSoundManager().play(PositionedSoundInstance.master(cue.sound(), pitch, volume));
        LAST_PLAYED.put(key, now);
        soundsThisWindow++;
    }

    public static void play(SoundEvent sound, float volume, float pitch) {
        if (sound == null) return;
        play(new UiParticleSoundProfile.Cue(sound, volume, pitch, 0.0F, 0L));
    }

    /** Stops every currently playing instance of this sound id. Used by detached jukeboxes. */
    public static void stop(SoundEvent sound) {
        if (sound == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getSoundManager() == null) return;
        client.getSoundManager().stopSounds(sound.getId(), SoundCategory.RECORDS);
    }
}
