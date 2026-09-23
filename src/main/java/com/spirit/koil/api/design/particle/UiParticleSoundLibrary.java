package com.spirit.koil.api.design.particle;

import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * Generic Minecraft-native sound helpers for UI particles.
 *
 * <p>Effect-specific sound selection lives in JSON. Keeping this class generic
 * prevents particle definitions from drifting back into Java.</p>
 */
public final class UiParticleSoundLibrary {
    private UiParticleSoundLibrary() { }

    public static SoundEvent sound(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Sound path cannot be blank");
        }
        Identifier id = path.contains(":") ? new Identifier(path) : new Identifier("minecraft", path);
        return SoundEvent.of(id);
    }

    public static UiParticleSoundProfile.Cue cue(
            String path,
            float volume,
            float pitch,
            float jitter,
            long cooldownMs
    ) {
        return new UiParticleSoundProfile.Cue(sound(path), volume, pitch, jitter, cooldownMs);
    }
}
