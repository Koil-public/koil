package com.spirit.koil.api.design.particle;

/**
 * Thin integration surface for Koil's existing KTL runtime. The particle module
 * does not duplicate or parse KTL itself. KTL can pass JSON effect definitions
 * through this bridge and trigger registered effects by id.
 */
public final class UiParticleKtlBridge {
    private UiParticleKtlBridge() { }

    public static String registerJsonEffect(String json) {
        return UiParticleJsonLoader.registerJson(json);
    }

    public static boolean trigger(UiParticleEngine engine, String effectId) {
        return trigger(engine, effectId, 1.0F);
    }

    public static boolean trigger(UiParticleEngine engine, String effectId, float scale) {
        return engine != null && engine.trigger(effectId, scale);
    }
}
